package com.roadmystery.app.story

import android.content.res.AssetManager

fun interface ScenarioRepository {
    fun load(scenarioId: String): Scenario
}

/** assets/scenarios/{id}.json 을 읽는다. */
class AssetScenarioRepository(
    private val assets: AssetManager,
) : ScenarioRepository {
    override fun load(scenarioId: String): Scenario =
        assets.open("scenarios/$scenarioId.json").bufferedReader().use { ScenarioParser.parse(it.readText()) }
}
