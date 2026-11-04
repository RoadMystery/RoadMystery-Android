package dev.voiceagent.platform

import android.content.res.AssetManager
import dev.voiceagent.content.Scenario

/**
 * The demo case until 최재령's scenario replaces it (draft: docs/voice-agent/scenario-jade-crown.md).
 * The golden file is built from the same case, so GoldenParityTest checks the prompts the app sends.
 */
const val DEMO_CASE_DIR = "cases/jade-crown"

/**
 * Loads a case from the app's assets. The library packs content/ and the voice-eval prompt
 * templates as assets (voiceagent/build.gradle.kts), so the paths match the repository layout.
 */
fun Scenario.Companion.fromAssets(assets: AssetManager, caseDir: String = DEMO_CASE_DIR): Scenario =
    Scenario.load({ path -> assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }, caseDir)
