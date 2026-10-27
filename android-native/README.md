# AURA for Android

This is the native Android application. It uses Kotlin and Jetpack Compose; it does not embed the website in a WebView.

## Build

Use JDK 17 or newer, Android SDK Platform 36, and run:

```sh
./gradlew assembleDebug
```

The install-compatible master APK is written to `app/build/outputs/apk/release/app-release.apk`. It uses the existing local Android signing key so it can update compatible AURA installations. It is not signed with a Play Store production key and has not been submitted to Google Play.

## Publishing an APK

Increment `versionCode` and `versionName` in `app/build.gradle.kts`, build the APK, add it to `public/` with a versioned filename, and replace `public/aura-android.apk`. Update `public/aura-update.json` for the website's download label and checksum. Keep the same signing key so Android can update existing copies. The current website default remains the known-installable 1.1.8 until a master release is explicitly published.

## Included in this build

- Native sign-in and account creation connected to the AURA service.
- A home screen with Aura Points, day streak, and the interactive timetable.
- A native focus timer with an ongoing notification. It continues when the display is off or the user switches apps. It awards points only for completed focus intervals.
- Optional social app blocking during active focus sessions through an Android Accessibility Service. The service checks only foreground app package names and covers the selected social apps; it does not read screen contents. ChatGPT, Gemini, YouTube, and other non-social apps remain available.
- Optional social notification blocking during focus sessions through Android Notification Access. Both blocker features require the user to enable the corresponding Android setting and can be switched off in AURA.
- A dedicated Subjects page with per-subject focus time and manual study logs. Manual logs are for tracking only and do not earn Aura Points.
- Delete a subject from its card; related class times, manual study logs, and attendance records are removed with confirmation.
- Attendance controls on each class in the home timetable, with date navigation to record or change present/absent for today or another scheduled day. Subject cards show saved attendance totals and percentage.
- Configurable water reminders (15–90 minutes) during focus sessions, with an animated in-app water break and a notification when AURA is in the background.
- An update notice checks the published release metadata on app launch and periodically, then sends a notification linking to the APK download page. It never silently downloads or installs an update.
- A separate Classes timetable and one-time Tasks scheduler.
- Each scheduled task can have no reminder, an Android notification, or a sounding Android alarm. Reminder alarms use Android's alarm service, survive app closure and device reboot, and are restored after time changes. Notification permissions and exact-reminder access may need to be enabled in Android settings.
- No preset mission catalog or Life section.
- A native Android home-screen widget for Aura Points and day streak.
- A More page with the community leaderboard and AURA information/contact links.
- AURA does not request Android Usage Access. Optional social app blocking uses Accessibility and optional notification blocking uses Notification Access; both are disabled until the user turns them on and grants access.
- Dark native interface with the infinity AURA logo and “Aura-Coz you need it!” tagline.
