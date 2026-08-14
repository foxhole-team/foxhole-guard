# keep kotlinx serialization generated serializers
-keepclassmembers class **$$serializer { *; }
-keepclassmembers class kotlinx.serialization.** { *; }

# JNA (lazysodium/Argon2): libjnidispatch resolves com.sun.jna.* members by NAME from
# native code — R8 renaming crashes the first Argon2 run (enabling the PIN) with
# "Can't obtain peer field ID for class com.sun.jna.Pointer".
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keep class com.goterl.lazysodium.** { *; }
-dontwarn java.awt.*

# FoxCore resolves these members of ours by name through JNI. R8 cannot see those calls, so it once
# shrank `protectSocket` away — and the
# dialer, which correctly refuses a socket it could not protect rather than putting packets on the
# wire outside the tunnel, then refused every socket. The release build could not connect at all,
# and the pending JNI exception killed the process on launch. Recorded on the bench Pixel:
# `NoSuchMethodError: no non-static method FoxholeVpnService.protectSocket(I)Z`, v66 release.
# Debug builds are not minified, which is exactly why no test caught it.
-keep class com.foxhole.core.runtime.RuntimeServiceHost {
    public boolean protectSocket(int);
    public java.lang.String signingDigestForPackage(java.lang.String);
}
-keepclassmembers class * implements com.foxhole.core.runtime.RuntimeServiceHost {
    public boolean protectSocket(int);
    public java.lang.String signingDigestForPackage(java.lang.String);
}
