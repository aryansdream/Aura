# AURA for Android

This is the native Android application. It uses Kotlin and Jetpack Compose; it does not embed the website in a WebView.

## Build

Use JDK 17 or newer, Android SDK Platform 36, and run:

```sh
./gradlew assembleDebug
```

The install-compatible APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It uses the same local Android debug signing key as the APKs already distributed, so it can update those installations. This is a debug-signed APK, not a production signing identity or Google Play Protect certification.

## Publishing an APK

Increment `versionCode` and `versionName` in `app/build.gradle.kts`, build the APK, add it to `public/` with a versioned filename, and replace `public/aura-android.apk`. Update `public/aura-update.json` for the website's download label and checksum. Keep the same signing key so Android can update existing copies. The native app has no update checker or update notifications; users install APKs manually from the website.

## Included in this build

- Native sign-in and account creation connected to the AURA service.
- A home screen with Aura Points, day streak, and the interactive timetable.
- A native focus timer with an ongoing notification. It continues when the display is off or the user switches apps; AURA does not track app usage. It awards points only for completed focus intervals.
- A dedicated Subjects page with per-subject focus time and manual study logs. Manual logs are for tracking only and do not earn Aura Points.
- Delete a subject from its card; related class times, manual study logs, and attendance records are removed with confirmation.
- Attendance controls on each class in the home timetable, with date navigation to record or change present/absent for today or another scheduled day. Subject cards show saved attendance totals and percentage.
- Configurable animated water reminders and notifications during focus sessions.
- A separate Classes timetable and one-time Tasks scheduler.
- Each scheduled task can have no reminder, an Android notification, or a sounding Android alarm. Reminder alarms use Android's alarm service, survive app closure and device reboot, and are restored after time changes. Notification permissions and exact-reminder access may need to be enabled in Android settings.
- No preset mission catalog or Life section.
- A native Android home-screen widget for Aura Points and day streak.
- A More page with the community leaderboard and AURA information/contact links.
- Focus mode does not inspect app usage or restrict app launches.
- Dark native interface with the infinity AURA logo and “Aura-Coz you need it!” tagline.
