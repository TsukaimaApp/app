# Project-specific R8 rules.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> { static <1> INSTANCE; kotlinx.serialization.KSerializer serializer(...); }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
    private static final ** $$serializer;
}
-if @kotlinx.serialization.Serializable class ** { static **$* *; }
-keepclassmembers class <2>$<3> { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class labs.suchattai.tsukaima.**$$serializer { *; }

# OkHttp / Okio
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okio.**

# Tink (androidx.security.crypto) references compile-time-only annotations
-dontwarn com.google.errorprone.annotations.**

# JLatexMath (ru.noties:jlatexmath-android, no consumer rules): MacroInfo reaches classes and methods by name
# (Class.forName + getDeclaredMethod, e.g. NewCommandMacro.executeMacro for \newcommand), which R8 renames or
# strips. Its fonts and XML are assets, which R8 and resource shrinking leave alone.
-keep class org.scilab.forge.jlatexmath.** { *; }
-keep class ru.noties.jlatexmath.** { *; }
