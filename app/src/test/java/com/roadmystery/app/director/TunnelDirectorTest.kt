package com.roadmystery.app.director

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TunnelDirectorTest {
    @Test fun `supported cabin restores after four seconds`() = runTest {
        val cabin = FakeCabinPort()
        val sound = FakeDirector()
        val director = TunnelDirector(cabin, sound, scope = this, ownsScope = false)
        director.perform(Effect.TUNNEL)
        runCurrent()
        assertEquals(25, cabin.brightness)
        assertEquals(3, cabin.fan)
        assertEquals(Effect.TUNNEL, sound.lastEffect.value)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(70, cabin.brightness)
        assertEquals(2, cabin.fan)
        assertTrue(director.status.value.contains("복원"))
    }

    @Test fun `unsupported cabin plays sound without writes`() = runTest {
        val cabin = FakeCabinPort(available = false)
        val sound = FakeDirector()
        val director = TunnelDirector(cabin, sound, scope = this, ownsScope = false)
        director.perform(Effect.TUNNEL)
        advanceUntilIdle()
        assertEquals(Effect.TUNNEL, sound.lastEffect.value)
        assertEquals(70, cabin.brightness)
        assertEquals(2, cabin.fan)
        assertTrue(director.status.value.startsWith("효과음만"))
    }

    @Test fun `hazard stop restores immediately without waiting for timer`() = runTest {
        val cabin = FakeCabinPort()
        val director = TunnelDirector(cabin, FakeDirector(), scope = this, ownsScope = false)
        director.perform(Effect.TUNNEL)
        runCurrent()
        director.stop()
        runCurrent()
        assertEquals(70, cabin.brightness)
        assertEquals(2, cabin.fan)
        assertTrue(director.status.value.contains("중단"))
    }

    @Test fun `partial write failure rolls back already changed channels`() = runTest {
        var light = 70
        val cabin = object : CabinPort {
            override suspend fun prepareTunnel() = listOf(
                CabinChange("light", light, 20, { light }, { light = it }),
                CabinChange("fan", 2, 3, { 2 }, { error("권한 없음") })
            )
        }
        val director = TunnelDirector(cabin, FakeDirector(), scope = this, ownsScope = false)
        director.perform(Effect.TUNNEL)
        advanceUntilIdle()
        assertEquals(70, light)
        assertTrue(director.status.value.startsWith("효과음만"))
    }

    @Test fun `user adjustment during effect is preserved`() = runTest {
        val cabin = FakeCabinPort()
        val director = TunnelDirector(cabin, FakeDirector(), scope = this, ownsScope = false)
        director.perform(Effect.TUNNEL)
        runCurrent()
        cabin.fan = 5
        advanceUntilIdle()
        assertEquals(70, cabin.brightness)
        assertEquals(5, cabin.fan)
        assertTrue(director.status.value.contains("사용자 변경 유지"))
    }

    @Test fun `new effect waits for previous restoration`() = runTest {
        val cabin = FakeCabinPort()
        val director = TunnelDirector(cabin, FakeDirector(), scope = this, ownsScope = false)
        director.perform(Effect.TUNNEL)
        runCurrent()
        director.perform(Effect.TUNNEL)
        runCurrent()
        advanceUntilIdle()
        assertEquals(70, cabin.brightness)
        assertEquals(2, cabin.fan)
    }

    @Test fun `preparation timeout falls back to sound`() = runTest {
        val cabin = object : CabinPort {
            override suspend fun prepareTunnel(): List<CabinChange> {
                delay(20_000)
                return emptyList()
            }
        }
        val director = TunnelDirector(cabin, FakeDirector(), scope = this, ownsScope = false)
        director.perform(Effect.TUNNEL)
        advanceUntilIdle()
        assertTrue(director.status.value.contains("시간 초과"))
    }

    @Test fun `restore failure blocks further vehicle writes`() = runTest {
        var light = 70
        var writes = 0
        val cabin = object : CabinPort {
            override suspend fun prepareTunnel() = listOf(
                CabinChange("light", light, 20, { light }, {
                    writes++
                    check(it != 70) { "복원 거부" }
                    light = it
                })
            )
        }
        val director = TunnelDirector(cabin, FakeDirector(), scope = this, ownsScope = false)
        director.perform(Effect.TUNNEL)
        advanceUntilIdle()
        assertTrue(director.status.value.contains("복원 확인 실패"))
        val previous = writes
        director.perform(Effect.TUNNEL)
        advanceUntilIdle()
        assertEquals(previous, writes)
        assertTrue(director.status.value.contains("차량 제어 보류"))
    }

    @Test fun `release waits for restoration before releasing cabin`() = runTest {
        var light = 70
        var released = false
        val cabin = object : CabinPort {
            override suspend fun prepareTunnel() = listOf(
                CabinChange("light", 70, 20, { light }, {
                    delay(100)
                    light = it
                })
            )
            override fun release() {
                assertEquals(70, light)
                released = true
            }
        }
        val director = TunnelDirector(cabin, FakeDirector(), scope = this, ownsScope = false)
        director.perform(Effect.TUNNEL)
        advanceTimeBy(200)
        director.release()
        advanceUntilIdle()
        assertTrue(released)
    }
}
