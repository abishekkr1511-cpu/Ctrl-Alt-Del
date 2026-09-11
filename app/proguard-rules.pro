# Proguard rules for Disaster Alert

# Keep Vosk and Kaldi JNI classes
-keep class org.vosk.** { *; }
-keep class com.alphacephei.vosk.** { *; }

# Keep ONNX Runtime JNI classes for Silero VAD
-keep class ai.onnxruntime.** { *; }

# Keep Room database entities and DAOs
-keep class com.offline.ble.mesh.data.model.** { *; }
-keep interface com.offline.ble.mesh.data.local.** { *; }
-keep class com.offline.ble.mesh.data.local.** { *; }
