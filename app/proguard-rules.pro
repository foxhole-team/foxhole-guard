# keep kotlinx serialization generated serializers
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class kotlinx.serialization.** { *; }

# libbox is invoked through reflection when a locally built aar is bundled
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }
