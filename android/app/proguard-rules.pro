# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.fcbtracker.core.** { *** Companion; }
-keepclasseswithmembers class com.fcbtracker.core.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.fcbtracker.core.**$$serializer { *; }
# jsoup optional dependency
-dontwarn com.google.re2j.**
