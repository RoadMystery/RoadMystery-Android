plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val defaultProxyUrl = "http://10.0.2.2:8787"

/**
 * VOICE_PROXY_TOKEN for the development proxy, taken from the environment or from
 * tools/voice-eval/.env parsed like voice_eval/env.py (first non-empty value wins). The file is
 * never committed and the value is never printed; only the debug build carries it.
 */
fun devProxyToken(): String {
    providers.environmentVariable("VOICE_PROXY_TOKEN").orNull?.takeIf { it.isNotEmpty() }?.let { return it }
    val env = rootDir.resolve("../../tools/voice-eval/.env")
    if (!env.isFile) return ""
    return env.readText(Charsets.UTF_8).removePrefix("\uFEFF").lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
        .map { it.removePrefix("export ").trimStart() }
        .mapNotNull { line ->
            val key = line.substringBefore("=").trim()
            val value = envValue(line.substringAfter("=").trim())
            value.takeIf { key == "VOICE_PROXY_TOKEN" && it.isNotEmpty() }
        }
        .firstOrNull()
        .orEmpty()
}

/** Like voice_eval/env.py: a quoted value ends at its closing quote, an unquoted one at " #". */
fun envValue(raw: String): String {
    if (raw.isNotEmpty() && raw[0] in "'\"") {
        val closing = raw.indexOf(raw[0], 1)
        return if (closing > 0) raw.substring(1, closing) else raw
    }
    return raw.split(Regex("\\s+#"), limit = 2)[0].trimEnd()
}

fun javaString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "dev.voiceagent.demo"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.voiceagent.demo"
        // Pleos Connect requires API 28+ (signing key rotation / lineage support).
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The emulator reaches the PC's dev proxy at 10.0.2.2; override with -PvoiceProxyUrl=...
        buildConfigField("String", "PROXY_URL", javaString(providers.gradleProperty("voiceProxyUrl").getOrElse(defaultProxyUrl)))
        buildConfigField("String", "PROXY_TOKEN", javaString(""))
    }

    buildTypes {
        debug {
            buildConfigField("String", "PROXY_TOKEN", javaString(devProxyToken()))
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // Recorded questions (stt/*.pcm) from the speech bench, so the demo can run STT without a microphone.
    sourceSets["main"].assets.srcDirs("../../gleo-speech-test/app/src/main/assets")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":voiceagent"))
}
