# Keep OkHttp / Okio platform classes quiet.
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class com.hound.app.data.** { *; }
