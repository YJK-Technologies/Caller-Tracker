import 'dart:async';
import 'dart:io';

import 'package:call_log/call_log.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'dart:convert';

/// Supply these values at build time:
/// --dart-define=CALLER_TRACKER_API_BASE_URL=https://your-host:5547
/// --dart-define=CALLER_TRACKER_COMPANY_CODE=YJKT
/// --dart-define=CALLER_TRACKER_LOCATION_CODE=PON001
/// --dart-define=CALLER_TRACKER_CREATED_BY=USERCODE
class CallerTrackerSyncService {
  static const String apiBaseUrl = String.fromEnvironment(
    'CALLER_TRACKER_API_BASE_URL',
    defaultValue: 'http://192.168.29.123:5547',
  );

  static const String companyCode = String.fromEnvironment(
    'CALLER_TRACKER_COMPANY_CODE',
    defaultValue: 'YJKT',
  );

  static const String locationCode = String.fromEnvironment(
    'CALLER_TRACKER_LOCATION_CODE',
    defaultValue: 'YJKT001',
  );

  static const String createdBy = String.fromEnvironment(
    'CALLER_TRACKER_CREATED_BY',
    defaultValue: 'YJKADMIN',
  );

  static const String _configPath = '/callerTracker/config';
  static const String _syncPath = '/syncCallerTracker';
  static const String _lastSuccessKey = 'caller_tracker_last_success_ms';
  static const String _deviceMobileKey = 'caller_tracker_device_mobile';
  static const String _syncLockKey = 'caller_tracker_sync_lock_until_ms';
  static const MethodChannel _deviceChannel = MethodChannel(
    'com.yjk.caller_tracker/device',
  );

  static bool _running = false;

  static Future<void> initialize() async {
    if (!Platform.isAndroid) return;
    await refreshScheduleFromBackend();
  }

  static Future<void> refreshScheduleFromBackend() async {
    if (!Platform.isAndroid || apiBaseUrl.isEmpty) return;

    try {
      final config = await _getBackendConfig();
      final enabled = config['enabled'] == true;
      final int configuredMinutes =
          (config['intervalMinutes'] as num?)?.toInt() ?? 60;

      // Native Android WorkManager keeps this schedule alive when Flutter UI
      // is closed, and the PHONE_STATE receiver enqueues an immediate scan
      // after a call ends. WorkManager enforces a 15-minute periodic minimum.
      await _deviceChannel.invokeMethod<bool>(
        'configureBackgroundSync',
        <String, dynamic>{
          'enabled': enabled,
          'apiBaseUrl': apiBaseUrl,
          'companyCode': companyCode,
          'locationCode': locationCode,
          'createdBy': createdBy,
          'intervalMinutes': configuredMinutes,
        },
      );
    } catch (e) {
      // Keep the existing registered native schedule if config is temporarily
      // unavailable. The next app launch will try to refresh it again.
    }
  }

  static Future<Map<String, dynamic>> _getBackendConfig() async {
    final uri = Uri.parse('$apiBaseUrl$_configPath');
    final response = await http.get(uri).timeout(const Duration(seconds: 30));

    if (response.statusCode != 200) {
      throw Exception('Config API failed: ${response.statusCode}');
    }

    final decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic> || decoded['success'] != true) {
      throw Exception('Invalid backend configuration response');
    }

    final config = decoded['config'];
    if (config is! Map<String, dynamic>) {
      throw Exception('Backend config object is missing');
    }
    return config;
  }

  static Future<String?> _getDeviceMobileNumber() async {
    final prefs = await SharedPreferences.getInstance();

    // Reuse only a previously obtained number; never ask the user to type it.
    final cached = _normalizeMobile(prefs.getString(_deviceMobileKey) ?? '');
    if (cached != null) return cached;

    try {
      final raw = await _deviceChannel.invokeMethod<String>(
        'getDeviceMobileNumber',
      );
      final normalized = _normalizeMobile(raw ?? '');
      if (normalized != null) {
        await prefs.setString(_deviceMobileKey, normalized);
      }
      return normalized;
    } on PlatformException {
      return null;
    } on MissingPluginException {
      return null;
    }
  }

  static String? _normalizeMobile(String raw) {
    var value = raw.replaceAll(RegExp(r'[^0-9]'), '');
    if (value.length == 12 && value.startsWith('91')) {
      value = value.substring(2);
    } else if (value.length == 11 && value.startsWith('0')) {
      value = value.substring(1);
    }

    if (!RegExp(r'^[6-9][0-9]{9}$').hasMatch(value)) return null;
    return value;
  }

  static Future<bool> syncNow() async {
    if (!Platform.isAndroid || _running) return false;
    if (apiBaseUrl.isEmpty ||
        companyCode.isEmpty ||
        locationCode.isEmpty ||
        createdBy.isEmpty) {
      return false;
    }

    _running = true;
    bool acquiredSyncLock = false;
    try {
      final config = await _getBackendConfig();
      if (config['enabled'] != true) return true;

      // The app must already have runtime permissions. A background worker
      // cannot reliably show a permission prompt.
      final callPermission = await Permission.phone.status;
      if (!callPermission.isGranted) return false;

      final deviceMobile = await _getDeviceMobileNumber();
      if (deviceMobile == null) {
        // Android/carrier may not expose the SIM's own number.
        // Never substitute a caller number for MobileNo.
        return false;
      }

      final prefs = await SharedPreferences.getInstance();
      final now = DateTime.now();
      final nowMs = now.millisecondsSinceEpoch;

      // Share a short lock with the native WorkManager worker so foreground
      // and background syncs are less likely to post the same range together.
      if ((prefs.getInt(_syncLockKey) ?? 0) > nowMs) return false;
      await prefs.setInt(_syncLockKey, nowMs + const Duration(minutes: 3).inMilliseconds);
      acquiredSyncLock = true;

      // First run: look back 24 hours. Later runs: strictly after last
      // successful watermark. A small overlap is intentionally avoided.
      final lastSuccess = prefs.getInt(_lastSuccessKey);
      final fromMs =
          lastSuccess ??
          now.subtract(const Duration(hours: 24)).millisecondsSinceEpoch;

      final entries = await CallLog.query(
        dateFrom: fromMs + (lastSuccess == null ? 0 : 1),
        dateTo: nowMs,
        type: CallType.missed,
      );

      final List<Map<String, String>> calls = [];
      for (final entry in entries) {
        final number = _normalizeMobile(entry.number ?? '');
        final timestamp = entry.timestamp;
        if (number == null || timestamp == null) continue;

        calls.add({
          'MissedCallNo': number,
          'MissedCallDate': DateTime.fromMillisecondsSinceEpoch(
            timestamp,
          ).toIso8601String(),
        });
      }

      final int batchSize = (config['maxBatchSize'] as num?)?.toInt() ?? 100;
      if (batchSize < 1) throw Exception('Invalid backend batch size');

      // Do not move the watermark until every batch succeeds.
      for (var start = 0; start < calls.length; start += batchSize) {
        final end = (start + batchSize < calls.length)
            ? start + batchSize
            : calls.length;
        final batch = calls.sublist(start, end);
        await _postBatch(deviceMobile, batch);
      }

      // If there are no valid calls, this still records a successful scan.
      await prefs.setInt(_lastSuccessKey, nowMs);
      return true;
    } catch (e) {
      // Keep the old watermark. The next run retries this time range.
      return false;
    } finally {
      if (acquiredSyncLock) {
        try {
          final prefs = await SharedPreferences.getInstance();
          await prefs.remove(_syncLockKey);
        } catch (_) {}
      }
      _running = false;
    }
  }

  static Future<void> _postBatch(
    String mobileNo,
    List<Map<String, String>> calls,
  ) async {
    final uri = Uri.parse('$apiBaseUrl$_syncPath');
    final response = await http
        .post(
          uri,
          headers: {'Content-Type': 'application/json'},
          body: jsonEncode({
            'MobileNo': mobileNo,
            'company_code': companyCode,
            // Keep the field name used by the uploaded controller/SP.
            'location_code': locationCode,
            'created_by': createdBy,
            'calls': calls,
          }),
        )
        .timeout(const Duration(seconds: 60));

    if (response.statusCode != 200) {
      throw Exception(
        'Sync API failed (${response.statusCode}): ${response.body}',
      );
    }

    final decoded = jsonDecode(response.body);
    if (decoded is! Map<String, dynamic> || decoded['success'] != true) {
      throw Exception('Backend did not confirm successful sync');
    }
  }
}

