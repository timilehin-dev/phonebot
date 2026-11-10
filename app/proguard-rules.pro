# Phonebot app — release ProGuard / R8 rules.
#
# `proguard-android-optimize.txt` (default) already keeps all of the
# application's own classes and most AndroidX. We only need the additions
# below for the reflective / serialization surfaces that R8 would otherwise
# drop or rename.

# --- kotlinx.serialization (used by agent-core / termux-bridge envelopes) ---
-keepattributes *Annotation*
-keepattributes Signature
-keepclassmembers,allowoptimization @kotlinx.serialization.Serializable class * {
    static void <init>(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class * {
    <init>();
}
-keepclassmembers @kotlinx.serialization.SerialName class * {
    <init>(...);
}

# --- Ktor Android engine (retrofit-free, but reflective engine factory) ---
-keep class io.ktor.client.engine.android.** { *; }
-keep class io.ktor.client.engine.** { *; }
-keep class io.ktor.serialization.** { *; }
# Ktor + okhttp (Ktor Android engine dep) is sensitive to minification.
-dontwarn io.ktor.**

# --- Android Keystore / crypto (SecretVault uses reflection into JCA) ---
-keep class javax.crypto.** { *; }
-keep class android.security.keystore.** { *; }
-keep class java.security.** { *; }

# --- WorkManager (catch-up nudges) ---
-keep class androidx.work.** { *; }
-keep class kotlinx.coroutines.** { *; }

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# --- Compose ---
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** {
    @androidx.compose.runtime.Composable <methods>
}

# --- Our own model classes that cross the socket (kotlinx-serialized) ---
-keep class com.phonebot.core.** { *; }
-keep class com.phonebot.termux.** { *; }
-keep class com.phonebot.credentials.** { *; }

# Keep the splash / service entry points.
-keep class com.phonebot.app.service.BotdNudgeService { *; }
