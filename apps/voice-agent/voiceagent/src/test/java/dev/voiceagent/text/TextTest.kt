package dev.voiceagent.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class TextTest {
    private val samples = listOf(
        "그 시각에는 저장고에 있었습니다. 정말입니다! 왜 그러십니까?",
        "음... 잠시만요. 그날 밤은 비가 많이 왔어요.",
        "그건… 모르겠습니다. 정말요?! 네.",
        "약 3.5초 걸렸습니다. 그렇죠?",
        "없습니다",
        "첫 문장.\n둘째 문장.\u3000셋째 문장.",
        "끝에 공백이 있어요.   ",
        "",
    )

    @Test
    fun `streaming splitter matches whole-text splitting for any chunking`() {
        for (text in samples) {
            val expected = splitSentences(text)
            for (size in 1..text.length.coerceAtLeast(1)) {
                val splitter = StreamingSentenceSplitter()
                val streamed = text.chunked(size).flatMap(splitter::push) + splitter.finish()
                assertEquals("'$text' in chunks of $size", expected, streamed)
            }
        }
    }

    @Test
    fun `a sentence is released as soon as the space after its mark arrives`() {
        val splitter = StreamingSentenceSplitter()
        assertEquals(emptyList<String>(), splitter.push("지하 저장고에 있었습니다."))
        assertEquals(listOf("지하 저장고에 있었습니다."), splitter.push(" 불이"))
        assertEquals(emptyList<String>(), splitter.push(" 약 3."))
        assertEquals(emptyList<String>(), splitter.push("5초 뒤에 들어왔어요"))
        assertEquals(listOf("불이 약 3.5초 뒤에 들어왔어요"), splitter.finish())
    }

    @Test
    fun `forbidden phrases match regardless of whitespace`() {
        val phrases = listOf("제가 훔쳤어요", "화분 밑")
        assertEquals(listOf("제가 훔쳤어요"), phraseHits("사실은 제가 훔 쳤어요.", phrases))
        assertEquals(listOf("화분 밑"), phraseHits("화분\u3000밑에 뒀어요", phrases))
        assertTrue(phraseHits("저는 모르는 일입니다.", phrases).isEmpty())
    }

    @Test
    fun `forbidden phrases match through punctuation and look-alike characters`() {
        val phrases = listOf("범인은 집사입니다")
        val zeroWidthSpace = Char(0x200B)
        val disguised = listOf(
            "범인은… 집사입니다.",
            "범인은, 집사입니다!",
            "범${zeroWidthSpace}인은 집사입니다",
            Normalizer.normalize("범인은 집사입니다", Normalizer.Form.NFD),
        )
        for (text in disguised) assertEquals(text, phrases, phraseHits(text, phrases))
        assertTrue(phraseHits("아무 말이나 합니다.", listOf("...", " ")).isEmpty())
    }

    @Test
    fun `whitespace follows Python str isspace`() {
        assertEquals(listOf("서진", "씨"), pythonSplit(" 서진\u00A0씨\u3000"))
        assertEquals("말", pythonStrip("\u0085\u2028말\u001f"))
        assertEquals("제가훔쳤어요", normalizeForMatch("제가\t훔\u200A쳤어요"))
    }
}
