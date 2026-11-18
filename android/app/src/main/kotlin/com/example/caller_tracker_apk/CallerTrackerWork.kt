package com.example.caller_tracker_apk

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Receives the system call-state broadcast. Android can instantiate this
 * receiver even when the Flutter activity/process is not currently visible.
 * The receiver only schedules work; network and Call Log access happen in
 * WorkManager, not inside onReceive().
 */
class CallerTrackerPhoneStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        if (state != TelephonyManager.EXTRA_STATE_IDLE) return

        val request = OneTimeWorkRequestBuilder<CallerTrackerNativeSyncWorker>()
            .setInitialDelay(2, TimeUnit.SECONDS) // allow Android Call Log to update
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            CallerTrackerNativeSyncWorker.IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }
}

/** Native fallback worker for synchronization while Flutter UI is closed. */
class CallerTrackerNativeSyncWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        const val IMMEDIATE_WORK_NAME = "callerTrackerCallEndedSync"
        const val PERIODIC_WORK_NAME = "callerTrackerNativePeriodicSync"
        private const val TAG = "CallerTrackerNativeSync"
        private const val CONFIG_PREFS = "caller_tracker_native_config"
        private const val FLUTTER_PREFS = "FlutterSharedPreferences"
        private const val LAST_SUCCESS_KEY = "flutter.caller_tracker_last_success_ms"
        private const val DEVICE_MOBILE_KEY = "flutter.caller_tracker_device_mobile"
        private const val LOCK_KEY = "flutter.caller_tracker_sync_lock_until_ms"

        fun enqueueImmediate(context: Context) {
            val request = OneTimeWorkRequestBuilder<CallerTrackerNativeSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    override fun doWork(): Result {
        val flutterPrefs = applicationContext.getSharedPreferences(FLUTTER_PREFS, Context.MODE_PRIVATE)
        val configPrefs = applicationContext.getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        // Avoid colliding with a foreground Flutter sync already in progress.
        if (flutterPrefs.getLong(LOCK_KEY, 0L) > now) return Result.retry()

        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "READ_CALL_LOG permission is not granted; open the app and grant permission")
            return Result.failure()
        }

        val apiBaseUrl = configPrefs.getString("apiBaseUrl", "")?.trim().orEmpty().trimEnd('/')
        val companyCode = configPrefs.getString("companyCode", "").orEmpty()
        val locationCode = configPrefs.getString("locationCode", "").orEmpty()
        val createdBy = configPrefs.getString("createdBy", "").orEmpty()
        val mobileNo = normalizeMobile(flutterPrefs.getString(DEVICE_MOBILE_KEY, "").orEmpty())

        if (apiBaseUrl.isBlank() || companyCode.isBlank() || locationCode.isBlank() || createdBy.isBlank()) {
            Log.w(TAG, "Background sync configuration is missing; launch the app once")
            return Result.failure()
        }
        if (mobileNo == null) {
            Log.w(TAG, "Device SIM mobile number is unavailable; refusing to use a caller number")
            return Result.failure()
        }

        val lockUntil = now + TimeUnit.MINUTES.toMillis(3)
        if (!flutterPrefs.edit().putLong(LOCK_KEY, lockUntil).commit()) return Result.retry()

        return try {
            val config = getBackendConfig("$apiBaseUrl/callerTracker/config")
            if (!config.optBoolean("enabled", false)) return Result.success()

            val lastSuccess = flutterPrefs.getLong(LAST_SUCCESS_KEY, 0L)
            val fromMs = if (lastSuccess > 0L) lastSuccess + 1L else now - TimeUnit.HOURS.toMillis(24)
            val calls = readMissedCalls(fromMs, now)
            val batchSize = config.optInt("maxBatchSize", 100)
            if (batchSize < 1) throw IllegalStateException("Invalid backend maxBatchSize")

            var start = 0
            while (start < calls.length()) {
                val end = (start + batchSize).coerceAtMost(calls.length())
                val batch = JSONArray()
                for (index in start until end) batch.put(calls.getJSONObject(index))
                postBatch(
                    "$apiBaseUrl/syncCallerTracker",
                    mobileNo,
                    companyCode,
                    locationCode,
                    createdBy,
                    batch
                )
                start = end
            }

            // Advance only after every batch has been accepted by the backend.
            flutterPrefs.edit().putLong(LAST_SUCCESS_KEY, now).commit()
            Result.success()
        } catch (error: Exception) {
            Log.e(TAG, "Background missed-call sync failed; WorkManager will retry", error)
            Result.retry()
        } finally {
            flutterPrefs.edit().remove(LOCK_KEY).commit()
        }
    }

    private fun getBackendConfig(endpoint: String): JSONObject {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 30_000
        }
        try {
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (status != 200) throw IllegalStateException("Config API returned HTTP $status: $body")
            val root = JSONObject(body)
            if (!root.optBoolean("success", false)) throw IllegalStateException("Backend config response success=false")
            return root.optJSONObject("config") ?: throw IllegalStateException("Config object missing")
        } finally {
            connection.disconnect()
        }
    }

    private fun readMissedCalls(fromMs: Long, toMs: Long): JSONArray {
        val result = JSONArray()
        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.DATE,
            CallLog.Calls.TYPE
        )
        val selection = "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} >= ? AND ${CallLog.Calls.DATE} <= ?"
        val selectionArgs = arrayOf(
            CallLog.Calls.MISSED_TYPE.toString(),
            fromMs.toString(),
            toMs.toString()
        )

        var cursor: Cursor? = null
        try {
            val queryCursor = applicationContext.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CallLog.Calls.DATE} ASC"
            ) ?: throw IllegalStateException("Android Call Log query returned null")
            cursor = queryCursor

            val numberIndex = queryCursor.getColumnIndex(CallLog.Calls.NUMBER)
            val dateIndex = queryCursor.getColumnIndex(CallLog.Calls.DATE)
            while (queryCursor.moveToNext()) {
                val number = normalizeMobile(queryCursor.getString(numberIndex).orEmpty()) ?: continue
                val timestamp = queryCursor.getLong(dateIndex)
                val localIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US).format(Date(timestamp))
                result.put(
                    JSONObject()
                        .put("MissedCallNo", number)
                        .put("MissedCallDate", localIso)
                )
            }
        } finally {
            cursor?.close()
        }
        return result
    }

    private fun postBatch(
        endpoint: String,
        mobileNo: String,
        companyCode: String,
        locationCode: String,
        createdBy: String,
        calls: JSONArray
    ) {
        val payload = JSONObject()
            .put("MobileNo", mobileNo)
            .put("company_code", companyCode)
            .put("location_code", locationCode)
            .put("created_by", createdBy)
            .put("calls", calls)

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            if (status != 200) throw IllegalStateException("Sync API returned HTTP $status: $body")
            if (!JSONObject(body).optBoolean("success", false)) {
                throw IllegalStateException("Backend did not confirm successful sync: $body")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeMobile(raw: String): String? {
        var value = raw.replace(Regex("[^0-9]"), "")
        if (value.length == 12 && value.startsWith("91")) value = value.substring(2)
        else if (value.length == 11 && value.startsWith("0")) value = value.substring(1)
        return if (Regex("^[6-9][0-9]{9}$").matches(value)) value else null
    }
}
