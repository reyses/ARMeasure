# AR Ruler App

A production-ready AR ruler application optimized for Android flagship devices, including Google Pixel 11 Pro.

## Features
- 📏 Real-time distance measurement
- 🎯 Center crosshair for accurate aiming
- 📐 Multiple units: CM, Inches, Meters, Feet
- 🎨 Clean, modern UI with adaptive Edge-to-Edge support
- ⚡ Live measurement updates
- 🔒 Lock measurements by tapping
- 📱 Fully compatible with Pixel 11 Pro display cutouts, gesture navigation, and 64-bit architecture

## Requirements
- Android Studio Ladybug or later
- Android device with ARCore support (e.g., Pixel 11 Pro, Pixel 9/10 series)
- Minimum SDK: 24 (Android 7.0)
- Target SDK: 35 (Android 15+)
- Java JDK 17

## Compatibility & Optimization Highlights
- **Pixel 11 Pro Compatible:** Supports camera punch-hole / display cutout insets and gesture navigation bar via `WindowInsetsCompat`.
- **64-bit Architecture:** Native ABI filters tuned for `arm64-v8a` and `x86_64`.
- **Target SDK 35:** Built to comply with Android 15 Edge-to-Edge display enforcement and modern Android security guidelines.
- **ARCore 1.45+:** Updated tracking and camera framework support.

## Build Instructions
1. Open project in Android Studio
2. Sync Gradle files
3. Connect an ARCore-supported device (e.g., Pixel 11 Pro)
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
1.1.0
