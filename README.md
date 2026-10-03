# AR Ruler App

A production-ready AR ruler application optimized for Android flagship devices, including Google Pixel 11 Pro.

## Features
- Distance: two-point and polyline measuring with a center crosshair, units CM / in / m / ft
- Area and volume: tap a polygon outline, close it, add a ceiling height
- Shapes: box, cylinder, cone, sphere, frustum and pile volume and surface area from a few taps
- Scan (devices with ARCore depth): sweep a room, get area, perimeter, height, volume and wall count
- Projects: save rooms into projects, view the floor plan, export it
- Recording and playback of AR sessions (MP4 datasets)

## Requirements
 - Android Studio Rabbit 1 (2026.2.1, build 262.x) or newer; this is the build the project was verified with (Google's AGP 9.4 notes do not state a minimum Studio version)
 - Gradle 9.8.0 (wrapper), Android Gradle Plugin 9.4.1, Kotlin 2.4.20
- Android device with ARCore support (e.g., Pixel 11 Pro, Pixel 9/10 series)
- Minimum SDK: 24 (Android 7.0)
 - Compile SDK: 37, Target SDK: 37
 - JDK 21: the Gradle daemon JVM is pinned in gradle/gradle-daemon-jvm.properties and auto-downloaded via the foojay toolchain resolver; Java/Kotlin target is 21. Any JDK 17+ (e.g. Android Studio's bundled JBR) can launch the wrapper.

## Compatibility & Optimization Highlights
- **Pixel 11 Pro Compatible:** Supports camera punch-hole / display cutout insets and gesture navigation bar via `WindowInsetsCompat`.
- **64-bit Architecture:** Native ABI filters tuned for `arm64-v8a` and `x86_64`.
- **Target SDK 37:** Built to comply with Android 15+ Edge-to-Edge display enforcement and modern Android security guidelines.
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
