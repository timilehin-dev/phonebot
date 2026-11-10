plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.phonebot.termux"
    compileSdk = 35

    defaultConfig { minSdk = 33 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":agent-core"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("androidx.core:core-ktx:1.15.0")
    // JSch (maintained Android-compatible fork, same com.jcraft.jsch API) —
    // the app → Termux sshd (127.0.0.1:8022) transport that carries the
    // in-Termux `botd.cli` socket client (BotdSshClient).
    implementation("com.github.mwiede:jsch:0.2.22")
}
