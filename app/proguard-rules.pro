# Anvex ProGuard/R8 rules

# kotlinx-serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

-keep,includedescriptorclasses class com.anvex.**$$serializer { *; }
-keepclassmembers class com.anvex.** {
    *** Companion;
}
-keepclasseswithmembers class com.anvex.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Keep enums used in reflection-ish serialization paths
-keepclassmembers enum com.anvex.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# JavaScript bridges: WebView discovers @JavascriptInterface methods reflectively.
# Covers the Preview, Python-runner AND Harness bridges (Harness lives in
# com.anvex.core.android.harness; the old rule only matched the preview package,
# so release builds would have renamed the Harness bridge methods).
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
