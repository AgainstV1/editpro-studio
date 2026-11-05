# ------------------------------------------------------------------------------
# Edit Pro Studio — R8 / ProGuard rules
# ------------------------------------------------------------------------------

# Keep annotations & metadata used by Kotlin reflection / Compose runtime.
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature, SourceFile, LineNumberTable
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Kotlin
-dontwarn kotlin.**
-keepclassmembers class kotlin.Metadata { public <methods>; }

# Kotlinx Coroutines
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# kotlinx.serialization (kept even though currently unused, so the plugin stays harmless)
-keepattributes RuntimeVisibleAnnotations
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Jetpack Compose
-dontwarn androidx.compose.**

# AndroidX ViewModel / Lifecycle — constructors are reflectively instantiated
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }

# org.json — used by ProjectSerializer at runtime
-dontwarn org.json.**
-keep class org.json.** { *; }

# MediaCodec / MediaMuxer pipelines are framework classes; nothing to keep.

# Keep the app entry point (referenced from AndroidManifest).
-keep class com.editpro.studio.MainActivity { *; }

# Keep data model classes that are (de)serialised by name / used by Compose stability.
-keep class com.editpro.studio.model.** { *; }
