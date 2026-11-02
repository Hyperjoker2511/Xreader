# Xreader release rules (Phase 1).
#
# Libraries with their own consumer rules: Room, WorkManager, OkHttp,
# kotlinx.serialization, Compose — their AAR rules are applied
# automatically and need nothing here.

# kotlinx.serialization (official rules): models parsed reflectively through
# generated serializers must survive R8 — JVM tests (unminified) would stay
# green while the device build failed, so keep them explicitly.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Phase 2 libraries with OPTIONAL platform pieces that are simply not
# bundled (R8-generated suggestions, verified by hand):
#  - PDFBox's JPEG-2000 filter references the Gemalto JP2 decoder,
#    which PDFBox-Android intentionally doesn't ship — scanned-JPX
#    images in PDFs degrade to "image not decoded", nothing else uses it.
#  - junrar logs through slf4j; no binding is bundled (logging off).
-dontwarn com.gemalto.jp2.JP2Decoder
-dontwarn org.slf4j.impl.StaticLoggerBinder

# Phase 3: the extension API is a LINK-TIME contract — Dex-loaded
# packages resolve app.xreader.extensionapi classes by name at
# runtime, so neither the API nor host classes a factory can return
# may be renamed or stripped.
-keep class app.xreader.extensionapi.** { *; }
-keep class app.xreader.data.extension.DeclarativeConfig { *; }
-keep class app.xreader.data.extension.**$$serializer { *; }
# Cash QuickJS binds Java interfaces reflectively (set/get proxies).
-keep class app.cash.quickjs.** { *; }
-keep interface app.xreader.data.extension.QuickJsHostBinding { *; }
-keep interface app.xreader.data.extension.QuickJsInvoker { *; }

# Keiyoushi player (v1.4.0): Injekt (uy.kohesive.injekt, mihon fork)
# resolves types through TypeReference subclasses whose GENERIC
# SUPERTYPE SIGNATURE is read reflectively at runtime. v1.4.0's R8
# pass stripped that Signature and the app died in AppContainer.<init>
# with "TypeReference constructed without actual type information".
# Release builds no longer minify at all (see app/build.gradle.kts —
# the user's standing rule), so these keeps are documentation: if
# minification is ever re-enabled deliberately, ALL of the rules
# below (copied from Komikku's app/proguard-rules.pro, which ships
# the same mechanism in production) must stay with it.
-keep,allowoptimization class uy.kohesive.injekt.** { public protected *; }
-keep class uy.kohesive.injekt.** { *; }
# R8 full mode (donor): the Signature attribute is the mechanism.
-keepattributes Signature
-keep,allowoptimization class * extends uy.kohesive.injekt.api.TypeReference
# The mihon fork's registrar patch (dev.mihon.injekt.patchInjekt,
# called first by PlayerRegistrar) swaps the default registrar
# reflectively — keep its package beside the upstream one.
-keep class dev.mihon.injekt.** { *; }
