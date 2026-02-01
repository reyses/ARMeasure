# ARCore
-keepattributes *Annotation*
-keep class com.google.ar.** { *; }
-keep class com.google.ar.sceneform.** { *; }
-dontwarn com.google.ar.**

# Sceneform
-keep class com.google.ar.sceneform.** { *; }
-keep class com.google.ar.schemas.** { *; }

# Material Components
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**