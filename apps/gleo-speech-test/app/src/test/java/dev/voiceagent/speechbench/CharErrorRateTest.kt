package dev.voiceagent.speechbench

import org.junit.Assert.assertEquals
import org.junit.Test

class CharErrorRateTest {

    @Test
    fun identicalTextHasNoErrors() {
        assertEquals(0.0, characterErrorRate("집사한테 물어봐", "집사한테 물어봐"), 1e-9)
    }

    @Test
    fun spacingAndPunctuationAreIgnored() {
        assertEquals(0.0, characterErrorRate("정전됐을 때 뭐 하고 계셨어요?", "정전됐을때 뭐하고 계셨어요"), 1e-9)
    }

    @Test
    fun oneWrongSyllableOutOfFive() {
        assertEquals(0.2, characterErrorRate("집사한테요", "집사한태요"), 1e-9)
    }

    @Test
    fun missingSyllablesCountAsErrors() {
        assertEquals(0.5, characterErrorRate("멈춰멈춰", "멈춰"), 1e-9)
    }

    @Test
    fun emptyExpectation() {
        assertEquals(0.0, characterErrorRate("", ""), 1e-9)
        assertEquals(1.0, characterErrorRate("", "뭔가"), 1e-9)
    }
}
