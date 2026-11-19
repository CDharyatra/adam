# Keep Vosk classes
-keep class org.vosk.** { *; }

# Keep MLC LLM JNI
-keep class com.adam.assistant.llm.LlmEngine { native <methods>; }

# Keep Accessibility Service
-keep class com.adam.assistant.accessibility.AdamAccessibilityService { *; }

# Keep data classes
-keep class com.adam.assistant.core.AssistantCommand { *; }
-keep class com.adam.assistant.stt.SttResult { *; }

# MediaPipe LLM inference references compile-time-only annotation types.
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue
-dontwarn com.google.protobuf.Internal$ProtoNonnullApi
-dontwarn com.google.protobuf.ProtoField
-dontwarn com.google.protobuf.ProtoPresenceBits
-dontwarn com.google.protobuf.ProtoPresenceCheckedField
