-keep class com.crystalvisa.app.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}