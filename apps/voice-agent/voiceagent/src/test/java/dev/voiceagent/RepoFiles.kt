package dev.voiceagent

import dev.voiceagent.content.Scenario
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** Repository files for JVM unit tests, read the way the app reads its merged assets. */
object RepoFiles {
    /** Fixture case of the behavior tests; GoldenParityTest replays the app's case (DEMO_CASE_DIR). */
    const val CASE_DIR = "cases/demo-sapphire"

    /** Gradle runs unit tests in the module directory; IDEs may use another one, so walk up. */
    val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "content/cases").isDirectory && File(it, "tools/voice-eval/prompts").isDirectory }
        ?: error("repository root not found above ${File("").absolutePath}")

    /** The same directories, in the same order, as `assets.srcDirs` in voiceagent/build.gradle.kts. */
    private val assetDirs = listOf(File(root, "content"), File(root, "tools/voice-eval/prompts"))

    fun read(path: String): String = File(root, path).readText(Charsets.UTF_8)

    fun asset(path: String): String =
        assetDirs.map { File(it, path) }.firstOrNull { it.isFile }?.readText(Charsets.UTF_8)
            ?: throw FileNotFoundException(path)

    fun scenario(caseDir: String = CASE_DIR): Scenario = Scenario.load(::asset, caseDir)
}

fun JSONArray.objects(): List<JSONObject> = List(length()) { getJSONObject(it) }

fun JSONArray.strings(): List<String> = List(length()) { getString(it) }

fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
