plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.voiceagent"
    compileSdk = 35

    defaultConfig {
        // Pleos Connect requires API 28+ (signing key rotation / lineage support).
        minSdk = 28
    }

    buildTypes {
        debug {
            // :voiceagent:createDebugUnitTestCoverageReport
            enableUnitTestCoverage = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Scenario content and prompt templates are shared with tools/voice-eval, never copied,
    // so the app renders exactly the prompts that were evaluated.
    sourceSets["main"].assets.srcDirs("../../../content", "../../../tools/voice-eval/prompts")

    // The core is plain Kotlin; a stray android.util call must not crash JVM tests.
    testOptions.unitTests.isReturnDefaultValues = true
}

// The JVM tests read the shared content, templates and golden file at run time; declare them so a
// content or template change reruns GoldenParityTest instead of reusing an up-to-date result.
tasks.withType<Test>().configureEach {
    inputs.dir("../../../content")
    inputs.dir("../../../tools/voice-eval/prompts")
    inputs.file("../../../tools/voice-eval/golden/voice-agent-golden.json")
}

dependencies {
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // Android's org.json is a stub in local unit tests; use the real implementation there.
    testImplementation("org.json:json:20240303")
}
