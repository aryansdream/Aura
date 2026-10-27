# AURA for Android

This is the native Android application. It uses Kotlin and Jetpack Compose; it does not embed the website in a WebView.

## Build

Use JDK 17 or newer, Android SDK Platform 36, and run:

```sh
./gradlew assembleDebug
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

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
