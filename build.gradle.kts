// Root build script for multibot-android.
// Kotlin 2.x + Compose BOM 2024+ + AGP 8.x. Application logic in :app,
// shared agent primitives in :agent-core, Termux IPC in :termux-bridge.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
    id("com.google.devtools.ksp") version "2.1.2-1.0.31" apply false // Room KSP
}

val composeBom = platform("androidx.compose:compose-bom:2024.12.00")

dependencies {
    // shared constraint for all modules (modules still declare their own deps)
    constraints {
        classpath(composeBom)
    }
}

// Common Kotlin/Android options applied to all modules that apply them.
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xcontext-parameters") // Kotlin 2.x context params
        }
    }
}
