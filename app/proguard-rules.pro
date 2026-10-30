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

# Glance identifies each provider by its concrete widget class name. R8 class merging made the
# connection and Web Apps providers share one implementation, so an update for one could render
# the other provider's content in an existing launcher widget.
-keep class com.foxhole.guard.widget.StatusWidget { *; }
-keep class com.foxhole.guard.widget.WebAppsWidget { *; }
-keep class com.foxhole.guard.widget.FoxStatusWidget { *; }

-keep class com.foxhole.guard.sentinel.nativeengine.NativeYara {
    native <methods>;
}

# JSch loads negotiated SSH algorithms by class name.
-keep class com.jcraft.jsch.** { *; }

# JSch optional desktop agents, Kerberos and logger adapters are not used by router SSH.
-dontwarn com.sun.jna.platform.win32.BaseTSD$ULONG_PTR
-dontwarn com.sun.jna.platform.win32.Kernel32
-dontwarn com.sun.jna.platform.win32.User32
-dontwarn com.sun.jna.platform.win32.WinBase$SECURITY_ATTRIBUTES
-dontwarn com.sun.jna.platform.win32.WinBase
-dontwarn com.sun.jna.platform.win32.WinDef$HWND
-dontwarn com.sun.jna.platform.win32.WinDef$LPARAM
-dontwarn com.sun.jna.platform.win32.WinDef$LRESULT
-dontwarn com.sun.jna.platform.win32.WinDef$WPARAM
-dontwarn com.sun.jna.platform.win32.WinNT$HANDLE
-dontwarn com.sun.jna.platform.win32.WinUser$COPYDATASTRUCT
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.MessageProp
-dontwarn org.ietf.jgss.Oid
-dontwarn org.newsclub.net.unix.AFUNIXServerSocketChannel
-dontwarn org.newsclub.net.unix.AFUNIXSocketAddress
-dontwarn org.newsclub.net.unix.AFUNIXSocketChannel
-dontwarn org.slf4j.Logger
-dontwarn org.slf4j.LoggerFactory
