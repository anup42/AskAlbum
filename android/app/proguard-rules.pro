# Preserve the JNI class and method names used by the native vector scanner.
-keep class io.github.anup42.askalbum.NativeVectorScanner { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Model packs are loaded by the app's typed runtime APIs; keep their public
# entry points stable while allowing unrelated application code to shrink.
-keep class io.github.anup42.askalbum.LiteRt* { *; }
-keep class io.github.anup42.askalbum.Embedded* { *; }
