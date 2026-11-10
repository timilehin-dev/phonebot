# agent-core consumer rules — applied to any app that depends on this module.
# agent-core exposes kotlinx-serialized envelopes (InvokeEnvelope, LlmClient
# models) and a Ktor streaming client. Keep those surfaces intact for
# downstream R8.

-keepattributes *Annotation*
-keepattributes Signature

# kotlinx.serialization model classes crossing the socket
-keep class com.multibot.core.** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    <init>(...);
}

# Ktor client core used by LlmClient
-keep class io.ktor.client.** { *; }
-keep class io.ktor.http.** { *; }
-keep class io.ktor.serialization.** { *; }
