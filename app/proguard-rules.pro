# kotlinx.serialization generuje serializatory jako pola towarzyszące klas @Serializable.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class pl.rzepisko.pilot.core.** {
    *** Companion;
}
-keepclasseswithmembers class pl.rzepisko.pilot.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp odwołuje się do opcjonalnych klas Conscrypt/BouncyCastle, których nie dołączamy.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
