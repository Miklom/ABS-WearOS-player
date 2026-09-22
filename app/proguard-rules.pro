# kotlinx.serialization keeps its generated serializers via @Serializable companions.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class org.wearabs.net.** {
    *** Companion;
}
-keepclasseswithmembers class org.wearabs.net.** {
    kotlinx.serialization.KSerializer serializer(...);
}
