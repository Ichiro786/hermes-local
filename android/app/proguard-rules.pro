# commons-compress references optional codecs we never use.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-keep class org.apache.commons.compress.archivers.tar.** { *; }
# zstd-jni: native methods and fields are looked up by name from JNI.
-keep class com.github.luben.zstd.** { *; }
