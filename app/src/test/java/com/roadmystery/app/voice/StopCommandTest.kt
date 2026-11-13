package com.roadmystery.app.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopCommandTest {
    @Test
    fun `명령만 정지로 처리한다`() {
        assertTrue(isStopCommand(" 멈춰! "))
        assertTrue(isStopCommand("일시 정지"))
        assertFalse(isStopCommand("왜 차를 멈춰 세웠나요?"))
        assertFalse(isStopCommand("멈추지 마"))
        assertFalse(isStopCommand(""))
    }
}
