package com.roadmystery.app.story

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

interface ProgressStore {
    suspend fun load(scenarioId: String): Progress?

    suspend fun save(progress: Progress)

    suspend fun clear(scenarioId: String)
}

class InMemoryProgressStore : ProgressStore {
    private val saved = mutableMapOf<String, Progress>()

    override suspend fun load(scenarioId: String): Progress? = saved[scenarioId]

    override suspend fun save(progress: Progress) {
        saved[progress.scenarioId] = progress
    }

    override suspend fun clear(scenarioId: String) {
        saved.remove(scenarioId)
    }
}

private val Context.progressDataStore by preferencesDataStore(name = "progress")

class DataStoreProgressStore(
    context: Context,
) : ProgressStore {
    private val store = context.applicationContext.progressDataStore
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(scenarioId: String) = stringPreferencesKey("progress_$scenarioId")

    override suspend fun load(scenarioId: String): Progress? {
        val text = store.data.first()[key(scenarioId)] ?: return null
        return runCatching { json.decodeFromString(Progress.serializer(), text) }.getOrNull()
    }

    override suspend fun save(progress: Progress) {
        store.edit { it[key(progress.scenarioId)] = json.encodeToString(Progress.serializer(), progress) }
    }

    override suspend fun clear(scenarioId: String) {
        store.edit { it.remove(key(scenarioId)) }
    }
}
