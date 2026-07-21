-keep class app.muka.bonsai.llama.* { *; }
-keep class app.muka.bonsai.llama.gguf.* { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class kotlin.Metadata { *; }
