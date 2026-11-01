plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.voiceagent.speechbench"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.voiceagent.speechbench"
        // Pleos Connect requires API 28+ (signing key rotation / lineage support).
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
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
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")

    testImplementation("junit:junit:4.13.2")
}
