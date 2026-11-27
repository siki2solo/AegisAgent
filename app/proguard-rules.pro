# Preserve AegisAgent data classes, JSON payloads, and dual local engine bridges
-keepattributes *Annotation*
-keep class com.siki2solo.aegisagent.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }
-keep class dev.ffmpegkit.llama.** { *; }
