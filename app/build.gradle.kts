plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.armeasure"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.armeasure"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }
    buildToolsVersion = "35.0.0"
}

dependencies {
    // Standard Android dependencies
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")

    // ARCore
    implementation("com.google.ar:core:1.50.0")

    // Sceneform (for 3D rendering in AR)
    implementation("com.google.ar.sceneform.ux:sceneform-ux:1.17.1") {
        // Exclude bundled libraries to prevent conflicts
        exclude(group = "com.google.ar", module = "core")
        exclude(group = "com.android.support")
    }

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}