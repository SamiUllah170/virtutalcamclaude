# Keep OkHttp / okio classes (used via reflection in some paths)
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep our service classes (referenced by Manifest + reflection-free bindings)
-keep class com.virtualcam.service.** { *; }
-keep class com.virtualcam.camera.** { *; }
