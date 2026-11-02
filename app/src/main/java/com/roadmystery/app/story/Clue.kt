package com.roadmystery.app.story

import kotlinx.serialization.Serializable

@Serializable
data class Clue(
    val id: String,
    val title: String,
    val description: String,
)
