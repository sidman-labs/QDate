# Keep methods exposed to the WebView via @JavascriptInterface (minify is off for now,
# but this guards a future enable).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
