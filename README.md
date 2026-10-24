# Signal Scanner for Android

Source code for Signal Scanner for Android 1.6, made by Victor.

Scans Wi-Fi, Bluetooth (LE and classic), NFC, cell, GNSS and nearby trackers while the app is open. Everything stays on the phone; there is no background service.

## Download

Download and install the app for free from [kodiakking.com](https://kodiakking.com).

## Build

Requirements: JDK 17 or 21, Android SDK (compileSdk 35), or Android Studio.

```
./gradlew assembleDebug        # debug APK: app/build/outputs/apk/debug/
./gradlew assembleRelease      # release APK: app/build/outputs/apk/release/
```

Windows: use `gradlew.bat` instead of `./gradlew`. Create a `local.properties` with `sdk.dir=...` if `ANDROID_HOME` is not set (Android Studio does this for you).

### Signing (optional)

Release signing reads `keystore/keystore.properties`, which is not in the repository. Copy `keystore/keystore.properties.example` to `keystore/keystore.properties`, put your own keystore in `keystore/`, and fill in the values. Without it the release build is unsigned.

## License

No license has been chosen for this project yet.
