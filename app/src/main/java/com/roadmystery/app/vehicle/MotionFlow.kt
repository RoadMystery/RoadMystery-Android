package com.roadmystery.app.vehicle

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/** A watchdog runs even when the source stops sending samples. Each collector owns its timer. */
fun Flow<MotionSample>.classifyMotion(nowMs: () -> Long): Flow<MotionReading> = flow {
    val classifier = MotionClassifier()
    val ticks = flow {
        while (true) {
            emit(nowMs())
            delay(100)
        }
    }
    combine(this@classifyMotion, ticks) { sample, _ -> classifier.classify(sample, nowMs()) }
        .distinctUntilChanged()
        .collect { emit(it) }
}

fun Flow<MotionReading>.motionEvents(): Flow<VehicleEvent> = map { it.event }.distinctUntilChanged()
