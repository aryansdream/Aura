# AURA for Android

This is the native Android application. It uses Kotlin and Jetpack Compose; it does not embed the website in a WebView.

## Build

Use JDK 17 or newer, Android SDK Platform 36, and run:

```sh
./gradlew assembleDebug
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Publishing an in-app update

Increment `versionCode` and `versionName` in `app/build.gradle.kts`, build the APK, then replace `public/aura-android.apk`. Update `public/aura-update.json` with the new version values and the APK's SHA-256 hash. Keep the same signing key used for the currently published APK so Android can install updates over existing copies. Vercel serves the APK and release manifest without caching; installed apps check the manifest from the About page and open Android's installer after verifying the downloaded APK hash.

## Included in this build

- Native sign-in and account creation connected to the AURA service.
- A home screen with Aura Points, day streak, and the interactive timetable.
- A native focus timer with an ongoing notification. It continues while the display is off, stops when the app is backgrounded while the display stays on, and awards points only for completed focus intervals.
- A dedicated Subjects page with per-subject focus time and manual study logs. Manual logs are for tracking only and do not earn Aura Points.
- A separate Classes timetable and one-time Tasks scheduler.
- Each scheduled task can have no reminder, an Android notification, or a sounding Android alarm. Reminder alarms use Android's alarm service, survive app closure and device reboot, and are restored after time changes. Notification permissions and exact-reminder access may need to be enabled in Android settings.
- No preset mission catalog or Life section.
- A native Android home-screen widget for Aura Points and day streak.
- A More page with the community leaderboard and AURA information/contact links.
- Social app detection during focus. ChatGPT, Gemini, YouTube and other non-social apps remain allowed; opening a social app or the home screen ends focus. Usage Access is required for app-switch detection.
- Dark native interface with the infinity AURA logo and “Aura-Coz you need it!” tagline.
