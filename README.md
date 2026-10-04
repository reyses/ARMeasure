# AR Ruler App

A production-ready AR ruler application optimized for Android flagship devices, including Google Pixel 11 Pro.

## Features
- Distance: two-point and polyline measuring with a center crosshair, units CM / in / m / ft
- Area and volume: tap a polygon outline, close it, add a ceiling height
- Shapes: box, cylinder, cone, sphere, frustum and pile volume and surface area from a few taps
- Scan (devices with ARCore depth): sweep a room, get area, perimeter, height, volume and wall count
- Object (devices with ARCore depth): place a box on the floor or a table, circle the object with a coverage dome as guide, get footprint, height and volume with a range, plus a 3D mesh to view, share (OBJ, PLY) and save
- Object, tap to box: tap the object and ML Kit (on the phone, offline) finds it in the camera image and fits the box to its depth points; the coarse label ('Home good · 82 %') shows briefly, and where ML finds nothing the depth-only fit takes over
- Object, shape recognition: a trained classifier plus least-squares fits name the object's simple shape (box, cylinder, sphere, cone) with its dimensions and formula volume on the result card, the detail page and in the exports; 'No simple shape fits' when none does
- Object, textured meshes: the walk-around takes sharp keyframe photos (largest camera image up to 1920x1080 that keeps depth), and after the mesh is built they are baked onto it (a texture atlas on mid / high phones, a colour per vertex on low ones). The 3D view shows the coloured mesh, the best photo is the gallery thumbnail, and OBJ + MTL + texture PNG and a vertex-colour PLY are saved with the object
- Object, Detailed quality (needs your PC): the walk-around photos with their ARCore poses go to the PC's photogrammetry; the PC's textured mesh (and its stats: photos registered, time) replaces the phone result
- Object, Spin and Hybrid (need your PC): put the phone on a stand and turn the object (two turns, the second with the phone tilted down about 25°; a banner warns when the phone moved), or walk around once and then spin; the photos with box masks go to the PC as one job, and the result card shows walk-only against fused numbers when the PC reports both
- Object, capture video: the AR session is recorded to one MP4 per capture (the whole walk and spin of a Hybrid is one file), kept with the saved object and played from its detail page
- Downloads export: every save also copies to Download/ARMeasure/<project>: the mesh as OBJ (+ MTL + texture PNG when textured) and PLY (+ a vertex-colour PLY), measurements.json / .txt (with the shape line), and the capture video
- PC processing: pair your PC with a QR code (Settings), then big scans and fine object meshes run on the PC (Auto, Phone or PC in Settings)
- Diagnostics (Settings -> Diagnostics): the phone checks its own GPU kernels against the CPU, lists its cameras (ToF, lens baseline, concurrent cameras), ARCore and device info, and shares the report as text; GPU kernels are used only where they verified on that phone (docs/GPU.md)
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
