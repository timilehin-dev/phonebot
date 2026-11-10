plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.phonebot.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Ktor for the cloud LLM client (no local LLM, OpenAI-compatible only)
    val ktor = "3.0.3"
    api("io.ktor:ktor-client-android:$ktor")
    api("io.ktor:ktor-client-content-negotiation:$ktor")
    api("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines for SSE streaming
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Design tokens (shared sealed object)
    sourceSets {
        getByName("main") {
            java.srcDir(project.file("../../design-tokens"))
        }
    }
}
