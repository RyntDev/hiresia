# Keep JNI methods and AudioServer interfaces for Hiresia
-keepclassmembers class * {
    native <methods>;
}
-keep class com.hiresia.audioplayer.native.** { *; }
-keep class com.hiresia.audioplayer.** { *; }
