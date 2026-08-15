# Cortixia KYC plugin — applied automatically via cortixia-kyc.gradle.
# The plugin class is instantiated reflectively from config.xml, so R8 sees it
# as unused and strips it without this keep (build succeeds, runtime dies).
-keep class dz.cortixia.kyc.** { *; }
# eMRTD stack (jMRTD reflection + BouncyCastle provider registration).
-keep class org.jmrtd.** { *; }
-keep class net.sf.scuba.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.jmrtd.**
-dontwarn net.sf.scuba.**
-dontwarn org.bouncycastle.**
-dontwarn javax.smartcardio.**
