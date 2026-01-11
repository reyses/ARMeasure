# AR Ruler App

A production-ready AR ruler application for Android.

## Features
- 📏 Real-time distance measurement
- 🎯 Center crosshair for accurate aiming
- 📐 Multiple units: CM, Inches, Meters, Feet
- 🎨 Clean, modern UI
- ⚡ Live measurement updates
- 🔒 Lock measurements by tapping

## Requirements
- Android Studio Hedgehog or later
- Android device with ARCore support
- Minimum SDK: 24 (Android 7.0)
- Target SDK: 34 (Android 14)

## Build Instructions
1. Open project in Android Studio
2. Sync Gradle files
3. Connect ARCore-supported device
4. Run the app

## Release Build
```bash
./gradlew assembleRelease
```

## Signing for Release
1. Create keystore:
```bash
keytool -genkey -v -keystore arruler.keystore -alias arruler -keyalg RSA -keysize 2048 -validity 10000
```

2. Add to `app/build.gradle`:
```gradle
android {
    signingConfigs {
        release {
            storeFile file("path/to/arruler.keystore")
            storePassword "your_password"
            keyAlias "arruler"
            keyPassword "your_password"
        }
    }
    buildTypes {
        release {
            signingConfig signingConfigs.release
        }
    }
}
```

## License
MIT License

## Version
1.0.0
