# R8 rules for the release build.
#
# The one thing that genuinely breaks without rules here is
# kotlinx.serialization: the generated `$$serializer` classes and the
# `Companion.serializer()` methods are only ever reached by reflection, so R8
# happily removes them - and the app then fails on its very first API call with
# a serializer-not-found error. Every other dependency (OkHttp, Media3, Coil)
# ships its own consumer rules.

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

# The serializers the compiler plugin generates for @Serializable classes.
-keep,includedescriptorclasses class eu.flopsyan.sonorus.**$$serializer { *; }
-keepclassmembers class eu.flopsyan.sonorus.** {
    *** Companion;
}
-keepclasseswithmembers class eu.flopsyan.sonorus.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# The data classes themselves: their field names are the JSON keys, so
# obfuscating them would rename the wire format.
#
# Not only `data.model`: the offline index under `data.download` is JSON too,
# and that one is written by one build of the app and read by the *next*. A
# field R8 renames differently between two releases would make the index
# unreadable - which is to say: an update would silently throw away every
# download on the phone. So the rule covers everything marked @Serializable.
-keep @kotlinx.serialization.Serializable class eu.flopsyan.sonorus.** { *; }

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp pulls in optional Conscrypt/BouncyCastle hooks it does not need here.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
