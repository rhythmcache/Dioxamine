# Preserve JavascriptInterface methods on DioxaminePluginBridge from R8 obfuscation/stripping
-keepclassmembers class io.github.rhythmcache.dioxamine.plugin.DioxaminePluginBridge {
    @android.webkit.JavascriptInterface <methods>;
}
