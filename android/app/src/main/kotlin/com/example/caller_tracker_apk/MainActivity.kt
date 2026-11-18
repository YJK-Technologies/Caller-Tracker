package com.example.caller_tracker_apk

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.TimeUnit

class MainActivity : FlutterActivity() {
    private val channelName = "com.yjk.caller_tracker/device"
    private val configPrefsName = "caller_tracker_native_config"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getDeviceMobileNumber" -> {
                        try {
                            result.success(readDeviceMobileNumber())
                        } catch (_: Exception) {
                            result.success(null)
                        }
                    }
                    "configureBackgroundSync" -> {
                        try {
                            val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
                            configureBackgroundSync(args)
                            result.success(true)
                        } catch (error: Exception) {
                            result.error("BACKGROUND_CONFIG_ERROR", error.message, null)
                        }
                    }
                    "requestImmediateSync" -> {
                        try {
                            CallerTrackerNativeSyncWorker.enqueueImmediate(this)
                            result.success(true)
                        } catch (error: Exception) {
                            result.error("BACKGROUND_SYNC_ERROR", error.message, null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun configureBackgroundSync(args: Map<*, *>) {
        val enabled = args["enabled"] as? Boolean ?: false
        val prefs = getSharedPreferences(configPrefsName, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("apiBaseUrl", args["apiBaseUrl"]?.toString().orEmpty())
            .putString("companyCode", args["companyCode"]?.toString().orEmpty())
            .putString("locationCode", args["locationCode"]?.toString().orEmpty())
            .putString("createdBy", args["createdBy"]?.toString().orEmpty())
            .putBoolean("enabled", enabled)
            .commit()

        val workManager = WorkManager.getInstance(applicationContext)
        if (!enabled) {
            workManager.cancelUniqueWork(CallerTrackerNativeSyncWorker.PERIODIC_WORK_NAME)
            return
        }

        val configuredMinutes = (args["intervalMinutes"] as? Number)?.toLong() ?: 60L
        // Android WorkManager's minimum periodic interval is 15 minutes.
        val intervalMinutes = configuredMinutes.coerceAtLeast(15L)
        val request = PeriodicWorkRequestBuilder<CallerTrackerNativeSyncWorker>(
            intervalMinutes, TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            CallerTrackerNativeSyncWorker.PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun readDeviceMobileNumber(): String? {
        val hasPhoneNumberPermission = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_PHONE_NUMBERS
        ) == PackageManager.PERMISSION_GRANTED
        val hasPhoneStatePermission = ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPhoneNumberPermission || !hasPhoneStatePermission) return null

        try {
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as SubscriptionManager
            val subscriptions = subscriptionManager.activeSubscriptionInfoList
            if (subscriptions != null) {
                for (subscription in subscriptions) {
                    val number = subscription.number?.trim()
                    if (!number.isNullOrEmpty()) return number
                }
            }
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            val number = telephonyManager.line1Number?.trim()
            if (!number.isNullOrEmpty()) return number
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
        return null
    }
}
