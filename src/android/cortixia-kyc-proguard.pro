# Cortixia KYC plugin — applied automatically via cortixia-kyc.gradle.
#
# Everything here is loaded reflectively, so R8 sees it as unused and strips
# it; the build succeeds and the app dies at runtime.

# The Cordova runtime itself: plugin classes come from config.xml strings and
# the WebView engine (SystemWebViewEngine) is constructed by reflected name —
# without this, a minified Cordova app crashes at launch with
# "Failed to create webview" long before any plugin code runs.
-keep class org.apache.cordova.** { *; }

# This plugin's classes (instantiated by Cordova from config.xml).
-keep class dz.cortixia.kyc.** { *; }

# eMRTD stack (jMRTD reflection + BouncyCastle provider registration).
-keep class org.jmrtd.** { *; }
-keep class net.sf.scuba.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.jmrtd.**
-dontwarn net.sf.scuba.**
-dontwarn org.bouncycastle.**
-dontwarn javax.smartcardio.**
