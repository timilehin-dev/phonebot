plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.phonebot.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.phonebot.app"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI signing: pass -Psigning.storeFile/-storePass/-keyAlias/-keyPass
    // (see .github/workflows/release.yml). The botd payload / APK are
    // re-signed on the release channel; debug builds stay unsigned.
    signingConfigs {
        create("ciRelease") {
            val storeFileProp = project.findProperty("signing.storeFile")
            if (storeFileProp != null) {
                storeFile = file(storeFileProp.toString())
                storePassword = project.findProperty("signing.storePass")?.toString()
                keyAlias = project.findProperty("signing.keyAlias")?.toString()
                keyPassword = project.findProperty("signing.keyPass")?.toString()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (project.hasProperty("signing.storeFile")) {
                signingConfig = signingConfigs.getByName("ciRelease")
            }
        }
        debug {
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
    buildFeatures {
        compose = true
    }
    sourceSets {
        getByName("main") {
            // Design tokens live in the repo-level design-tokens/ folder (single
            // sealed object, shared by every module). Promote to a proper
            // :design-tokens module when the second consumer lands in Phase 2.
            java.srcDir(project.file("../design-tokens"))
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.00")
    implementation(composeBom)

    // Compose (Material 3, dark-only chrome)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Ktor — cloud LLM client (OpenAI-compatible)
    val ktor = "3.0.3"
    implementation("io.ktor:ktor-client-android:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-client-logging:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Room — app-side state (conversation threads, approvals, routine mirror)
    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    // WorkManager — catch-up / background nudges
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    // DataStore — preferences + wizard state
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Local project modules
    implementation(project(":agent-core"))
    implementation(project(":termux-bridge"))
    implementation(project(":scheduling"))
    implementation(project(":credentials"))
    implementation(project(":wizard"))

    // Note: design tokens are pulled in via sourceSets (java.srcDir ../design-tokens).
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
