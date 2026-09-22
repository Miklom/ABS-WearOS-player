# Most dependencies here (kotlinx.serialization, Room, Media3, OkHttp, Coil,
# WorkManager) ship their own consumer rules. What follows covers this app's own
# reflectively-reached entry points, plus a couple of belt-and-braces keeps.

# --- kotlinx.serialization --------------------------------------------------
# The wire types are only ever reached through generated serializers.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.wearabs.net.** {
    *** Companion;
}
-keepclasseswithmembers class org.wearabs.net.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.wearabs.net.**$$serializer { *; }

# --- WorkManager ------------------------------------------------------------
# Workers are constructed by name from the persisted work spec, so their
# constructors must survive even though nothing calls them directly.
-keep class org.wearabs.work.DownloadWorker { <init>(...); }
-keep class org.wearabs.work.SyncWorker { <init>(...); }

# --- Room -------------------------------------------------------------------
# The generated implementation is looked up by name from the @Database class.
-keep class org.wearabs.data.AppDatabase_Impl { <init>(); }

# --- Media3 -----------------------------------------------------------------
# The session service is resolved through its manifest intent filter.
-keep class org.wearabs.player.PlayerService { *; }

# Media3 probes for optional decoder extensions that are not on the classpath.
-dontwarn androidx.media3.decoder.**
-dontwarn androidx.media3.exoplayer.ext.**

# Coil's okhttp integration references optional Ktor engines.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Keep names -------------------------------------------------------------
# Renaming saves about half a megabyte out of ~4.9 MB. That is not worth losing
# readable stack traces on a sideloaded app, and keeping names also rules out a
# whole class of reflection breaking silently.
-dontobfuscate
