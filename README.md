# YJK Caller Tracker

## Background missed-call synchronization

The Android implementation uses two layers:

1. `CallerTrackerPhoneStateReceiver` listens for `android.intent.action.PHONE_STATE` and queues a unique WorkManager task when the phone returns to `IDLE` (after the call ends). The task is delayed by two seconds to give Android time to update the Call Log.
2. `CallerTrackerNativeSyncWorker` reads missed-call entries from Android's Call Log and posts them to the existing `/syncCallerTracker` API. It uses the same payload fields, mobile-number normalization, first-run 24-hour lookback, last-success watermark, and batch-size configuration as the Flutter implementation.

A native periodic WorkManager task is also scheduled using the backend's `intervalMinutes` setting. Android WorkManager periodic work has a minimum interval of 15 minutes and may be delayed by Doze/battery/network constraints.

## First-run requirements

- Launch the app at least once after installation.
- Grant the requested Phone / Call Log / Phone Number permissions.
- The device SIM number must be readable and cached by the existing Flutter flow. Some carriers do not expose the SIM's own number; in that case synchronization deliberately stops instead of using a caller's number as `MobileNo`.
- Backend configuration must be reachable and enabled.
- Network connectivity is required for upload.

## Android limitation

This works for normal backgrounding, removing the app from the recent-apps screen on devices that permit background work, and process recreation by Android. If the user explicitly force-stops the app from Android Settings, Android blocks its receivers and scheduled work until the user manually launches the app again. OEM battery managers can also delay background execution.

## Files changed / added

- `lib/caller_tracker_sync_service.dart`: configures native WorkManager and adds a shared sync lock for foreground synchronization.
- `lib/todays_missed_calls_screen.dart`: removes the in-process hourly timer; initializes permissions before the first sync and refreshes on resume.
- `android/app/src/main/kotlin/com/example/caller_tracker_apk/MainActivity.kt`: configures native background scheduling.
- `android/app/src/main/kotlin/com/example/caller_tracker_apk/CallerTrackerWork.kt`: call-state receiver and native background worker.
- `android/app/src/main/AndroidManifest.xml`: declares the phone-state receiver and required permissions.
- `android/app/build.gradle.kts`: adds AndroidX WorkManager.
- `pubspec.yaml`: removes the Flutter Workmanager plugin because scheduling is now native Android WorkManager.
