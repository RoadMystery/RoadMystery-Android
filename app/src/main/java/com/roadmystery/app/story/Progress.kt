package com.roadmystery.app.story

import kotlinx.serialization.Serializable

@Serializable
data class Testimony(
    val suspectId: String,
    val question: String,
    val answer: String,
)

/** 이어하기용 저장 데이터. */
@Serializable
data class Progress(
    val scenarioId: String,
    val chapterId: String,
    /** 끝까지 듣지 못한 장면. null이면 장면 사이에서 멈춤. */
    val lastSceneId: String? = null,
    val lastSentenceIndex: Int = 0,
    val playedSceneIds: List<String> = emptyList(),
    val cluesFound: List<String> = emptyList(),
    val testimonies: List<Testimony> = emptyList(),
    val choices: Map<String, String> = emptyMap(),
)
