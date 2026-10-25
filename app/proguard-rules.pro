# Keep Vosk classes
-keep class org.vosk.** { *; }

# Keep MLC LLM JNI
-keep class com.adam.assistant.llm.LlmEngine { native <methods>; }

# Keep Accessibility Service
-keep class com.adam.assistant.accessibility.AdamAccessibilityService { *; }

# Keep data classes
-keep class com.adam.assistant.core.AssistantCommand { *; }
-keep class com.adam.assistant.stt.SttResult { *; }
