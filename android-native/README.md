# AURA for Android

This is the native Android application. It uses Kotlin and Jetpack Compose; it does not embed the website in a WebView.

## Build

Use JDK 17 or newer, Android SDK Platform 36, and run:

```sh
./gradlew assembleDebug
```

The install-compatible APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It uses the same local Android debug signing key as the APKs already distributed, so it can update those installations. This is a debug-signed APK, not a production signing identity or Google Play Protect certification.

## Publishing an update notification

Increment `versionCode` and `versionName` in `app/build.gradle.kts`, build the APK, add it to `public/` with a versioned filename, and replace `public/aura-android.apk`. Update `public/aura-update.json` with the new version, download URL, and SHA-256 for reference. Keep the same signing key so Android can update existing copies. The app checks the release manifest on launch and with a 12-hour WorkManager schedule. It posts one notification for each newer version; tapping it opens the APK download in the browser. The user completes installation through Android's normal installer flow. AURA does not download or install APKs itself.

## Included in this build

- Native sign-in and account creation connected to the AURA service.
- A home screen with Aura Points, day streak, and the interactive timetable.
- A native focus timer with an ongoing notification. It continues when the display is off or the user switches apps; AURA does not track app usage. It awards points only for completed focus intervals.
- A dedicated Subjects page with per-subject focus time and manual study logs. Manual logs are for tracking only and do not earn Aura Points.
- Subject attendance totals, percentage, and an estimate of consecutive classes needed to reach the 75% target.
- Configurable animated water reminders and notifications during focus sessions.
- A separate Classes timetable and one-time Tasks scheduler.
- Each scheduled task can have no reminder, an Android notification, or a sounding Android alarm. Reminder alarms use Android's alarm service, survive app closure and device reboot, and are restored after time changes. Notification permissions and exact-reminder access may need to be enabled in Android settings.
- No preset mission catalog or Life section.
- A native Android home-screen widget for Aura Points and day streak.
- A More page with the community leaderboard and AURA information/contact links.
- Optional social notification muting during focus. It does not inspect app usage or restrict app launches.
- A release notification checker that alerts once when a newer APK is published; users download and install it themselves.
- Dark native interface with the infinity AURA logo and “Aura-Coz you need it!” tagline.
