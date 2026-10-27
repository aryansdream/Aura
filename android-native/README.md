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
- Today’s missions, AURA totals, and the community leaderboard.
- Native focus timer with a foreground notification. It continues while the display is off, stops when the app is backgrounded while the display stays on, and posts a sound-and-vibration alarm when the session ends.
- Steps from Health Connect and daily screen time from Android Usage Access. Both require the user to grant access; device readings sync to the account when the Life page is opened or resumed. Calories remain an editable daily intake log.
- Native dark interface, AURA information and contact links.

The native Study screen currently reads saved subjects from the account. Subject and timetable editing remain available in the web app and are not yet native controls.
