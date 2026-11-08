package dev.voiceagent.agent

import dev.voiceagent.RepoFiles
import dev.voiceagent.content.InvestigationStage
import dev.voiceagent.content.Suspect
import dev.voiceagent.prompt.SuspectPrompt
import dev.voiceagent.text.countedSentences
import dev.voiceagent.text.phraseHits
import dev.voiceagent.text.splitSentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigationTest {
    /** The app's case: hints, key reactions and pressure lines. */
    private val jade = RepoFiles.scenario("cases/jade-crown")

    /** The fixture case has none of them. */
    private val demo = RepoFiles.scenario()

    private val case = jade.case
    private val suspects = jade.suspects.values
    private val maid = jade.suspect("maid")
    private val niece = jade.suspect("niece")
    private val son = jade.suspect("son")
    private val backDoor = case.clue("clue-back-door")
    private val window = case.clue("clue-window")
    private val barefoot = case.clue("clue-barefoot")
    private val shoes = case.clue("clue-shoes")
    private val allClues = case.clues.map { it.id }.toSet()
    private val allKeyReactions = setOf("k-maid-back-door", "k-niece-window", "k-son-shoes")

    @Test
    fun `a question brings a clue up by a keyword, its title or a mention`() {
        assertEquals(listOf(backDoor), Investigation.mentionedClues(case, "의족 짚은 사람 봤어요?"))
        assertEquals(listOf(window), Investigation.mentionedClues(case, "창밖에서 무슨 소리 났어요?"))
        // Every title here contains a keyword or mention too, so take those away to see the title count.
        val titleOnly = case.copy(clues = listOf(backDoor.copy(keywords = emptyList(), mentions = emptyList())))
        assertEquals(titleOnly.clues, Investigation.mentionedClues(titleOnly, "부엌 뒷문 발자국 얘기 좀 해요"))
        assertEquals(emptyList<Any>(), Investigation.mentionedClues(titleOnly, "부엌 얘기 좀 해요"))
        // In case order, whatever order the question names them in.
        assertEquals(listOf(window, shoes), Investigation.mentionedClues(case, "고정태 구두 얘기랑 서재 창 얘기요"))
        // The shoe prints under the window are not 고정태's shoes.
        assertEquals(listOf(window), Investigation.mentionedClues(case, "창 아래 구두 자국은 누구 거예요?"))
    }

    @Test
    fun `the son's claims stand until a clue brings a reaction that takes them back`() {
        // He says he never left his room and only picked the crown up; the barefoot prints take the first back,
        // the shoes (with the window) both. His window reaction is replaced too, but it is no public claim.
        assertEquals(setOf("k-son-room", "k-son-crown"), Investigation.standingClaims(son, emptySet()))
        assertEquals(listOf("k-son-barefoot"), Investigation.claimBreakers(son, setOf(barefoot.id)).map { it.id })
        assertEquals(setOf("k-son-crown"), Investigation.standingClaims(son, setOf(barefoot.id)))
        assertEquals(emptySet<String>(), Investigation.standingClaims(son, setOf(window.id, shoes.id)))
        assertEquals(listOf("k-son-barefoot", "k-son-shoes"), Investigation.claimBreakers(son, allClues).map { it.id })
        // The maid says she went to bed at ten; the back-door prints bring the reaction that takes it back.
        assertEquals(setOf("k-maid-night"), Investigation.standingClaims(maid, emptySet()))
        assertEquals(listOf("k-maid-back-door"), Investigation.claimBreakers(maid, setOf(backDoor.id)).map { it.id })
        assertEquals(emptySet<String>(), Investigation.standingClaims(maid, setOf(backDoor.id)))
        // No reaction of the niece takes a line back, so she holds no claim the clues break.
        assertEquals(emptySet<String>(), Investigation.standingClaims(niece, emptySet()))
    }

    @Test
    fun `asking about 고정태 in general brings up no clue`() {
        assertEquals(emptyList<Any>(), Investigation.mentionedClues(case, "고정태는 어떤 사람이에요?"))
        assertEquals(emptyList<Any>(), Investigation.mentionedClues(case, "고정태 씨랑 친해요?"))
    }

    @Test
    fun `a key reaction is drawn out once all its clues are found and the question brings one of them up`() {
        val aboutShoes = "고정태 구두 얘기 좀 해 봐요"

        // The son's reaction needs the window as well as the shoes.
        assertEquals(emptySet<String>(), Investigation.drawnOut(case, son, setOf(shoes.id), aboutShoes))
        assertEquals(setOf("k-son-shoes"), Investigation.drawnOut(case, son, setOf(window.id, shoes.id), aboutShoes))
        // Bringing up either of its clues will do.
        assertEquals(setOf("k-son-shoes"), Investigation.drawnOut(case, son, setOf(window.id, shoes.id), "서재 창밖에 누가 있었어요?"))
        assertEquals(emptySet<String>(), Investigation.drawnOut(case, son, allClues, "그날 밤 어디 있었어요?"))
    }

    @Test
    fun `reactions that are not key never count, and only the suspect asked is confronted`() {
        // The maid's window reaction is not key.
        assertEquals(emptySet<String>(), Investigation.drawnOut(case, maid, allClues, "서재 창문 쪽에 가 봤어요?"))
        // The back door is the maid's key reaction, not the niece's.
        assertEquals(emptySet<String>(), Investigation.drawnOut(case, niece, allClues, "뒷문 얘기 해 봐요"))
        assertEquals(setOf("k-maid-back-door"), Investigation.drawnOut(case, maid, allClues, "뒷문 얘기 해 봐요"))
    }

    @Test
    fun `only a heard answer draws a reaction out, not an opener alone or the not-understood line`() {
        assertTrue(Investigation.isAnswer(maid, listOf("어머, 뒷문이요?", "사실은 그날 밤 박만수 아저씨를 잠깐 만났어요.")))
        assertFalse(Investigation.isAnswer(maid, emptyList()))
        assertFalse(Investigation.isAnswer(maid, listOf("어머, 뒷문이요?")))
        // The line has a counted sentence, so only the not-understood rule keeps it out, spacing aside.
        val notUnderstood = splitSentences(maid.fallbackNotUnderstood)
        assertTrue(countedSentences(notUnderstood) > 0)
        assertFalse(Investigation.isAnswer(maid, notUnderstood))
        assertFalse(Investigation.isAnswer(maid, listOf(maid.fallbackNotUnderstood.replace(" ", ""))))
    }

    @Test
    fun `the stage goes from opening to ready as clues are found and key reactions drawn out`() {
        fun stage(unlocked: Set<String>, confronted: Set<String> = emptySet()) = Investigation.stage(case, suspects, unlocked, confronted)

        assertEquals(allKeyReactions, suspects.flatMap { it.unlockable }.filter { it.key }.map { it.id }.toSet())
        assertEquals(InvestigationStage.OPENING, stage(emptySet()))
        assertEquals(InvestigationStage.GATHERING, stage(setOf(backDoor.id)))
        // Every key reaction drawn out still waits for the last clue.
        assertEquals(InvestigationStage.GATHERING, stage(allClues - barefoot.id, allKeyReactions))
        assertEquals(InvestigationStage.PRESSING, stage(allClues))
        assertEquals(InvestigationStage.PRESSING, stage(allClues, allKeyReactions - "k-son-shoes"))
        assertEquals(InvestigationStage.READY, stage(allClues, allKeyReactions))
    }

    @Test
    fun `a case without key reactions is ready once every clue is found`() {
        val clues = demo.case.clues.map { it.id }

        assertEquals(InvestigationStage.GATHERING, Investigation.stage(demo.case, demo.suspects.values, setOf(clues.first()), emptySet()))
        assertEquals(InvestigationStage.READY, Investigation.stage(demo.case, demo.suspects.values, clues.toSet(), emptySet()))
    }

    @Test
    fun `key reactions within reach come in the order the story brings their clues, then in cast order`() {
        fun pending(unlocked: Set<String>, confronted: Set<String> = emptySet(), among: Collection<Suspect> = suspects) =
            Investigation.pendingKeyReactions(case, among, unlocked, confronted).map { (suspect, fact) -> suspect.id to fact.id }

        // The cast lists the son first, but his reaction needs the last clue.
        assertEquals(listOf("maid" to "k-maid-back-door", "niece" to "k-niece-window", "son" to "k-son-shoes"), pending(allClues))
        assertEquals(listOf("son" to "k-son-shoes"), pending(allClues, setOf("k-maid-back-door", "k-niece-window")))
        assertEquals(listOf("niece" to "k-niece-window"), pending(setOf(window.id)))
        // Two reactions needing the same last clue go in cast order, whatever order the suspects come in.
        val maidWindowKey = maid.copy(unlockable = maid.unlockable.map { if (it.id == "k-maid-window") it.copy(key = true) else it })
        assertEquals(
            listOf("niece" to "k-niece-window", "maid" to "k-maid-window"),
            pending(setOf(window.id), among = listOf(maidWindowKey, son, niece)),
        )
    }

    @Test
    fun `asking who did it is answer-seeking, asking about the night is not`() {
        val seeking = listOf(
            "하린 씨는 누가 그랬다고 생각해요?",
            "옥분 씨는 누가 범인 같아요?",
            "그냥 누가 가져간 건지 말해 줘요. 준기 씨는 알잖아요.",
            "누가 화관 가져갔어요?",
            "누가 화관을 훔쳤어요?",
            "화관 누가 훔쳐 갔어요?",
            "도둑이 누구예요?",
            "누구 소행이에요?",
            // As the recognizer spells them.
            "누가 가져가써요",
            "누가 훔쳐써요",
            "정답 알려줘",
            "범인 누구야",
            "누가 손댔어요?",
        )
        val investigating = listOf(
            "준기 씨랑 하린 씨는 정답게 지냈어요?",
            "서랍에 누가 손댄 흔적 있었어요?",
            "누가 그런 말을 했어요?",
            "그날 밤 어디 있었어요?",
            "뒷문에서 누구 만났어요?",
            "고정태는 어떤 사람이에요?",
        )

        for (question in seeking) assertTrue(question, Investigation.isAnswerSeeking(question))
        for (question in investigating) assertFalse(question, Investigation.isAnswerSeeking(question))
    }

    @Test
    fun `the hint points at a reaction within reach first, then at the first clue not found, then gives the case's own`() {
        fun hint(unlocked: Set<String>, confronted: Set<String> = emptySet(), tried: Set<String> = emptySet()) =
            NarratorLines.hint(case, suspects, unlocked, confronted, tried)

        // Nothing within reach: the first clue not found, whatever a cut answer was about to say.
        assertEquals(backDoor.hint, hint(emptySet()))
        // ... the first one that has a hint.
        val firstWithoutHint = case.copy(clues = case.clues.map { if (it.id == backDoor.id) it.copy(hint = null) else it })
        assertEquals(window.hint, NarratorLines.hint(firstWithoutHint, suspects, emptySet(), emptySet()))
        // A reaction within reach comes before any clue's hint.
        assertEquals(NarratorLines.confrontHint(backDoor), hint(setOf(backDoor.id)))
        assertEquals(window.hint, hint(setOf(backDoor.id), setOf("k-maid-back-door")))
        // The reaction the question just went after is not suggested straight back.
        assertEquals(window.hint, hint(setOf(backDoor.id), tried = setOf("k-maid-back-door")))
        // Every clue found: the reactions in story order, then the case's own hint.
        assertEquals(NarratorLines.confrontHint(backDoor), hint(allClues))
        assertEquals(NarratorLines.confrontHint(shoes), hint(allClues, setOf("k-maid-back-door", "k-niece-window")))
        assertEquals(case.spoilerHint, hint(allClues, allKeyReactions))
        // The case's own hint is for a finished investigation: with only the reaction just tried pending, none.
        assertNull(hint(allClues, allKeyReactions - "k-son-shoes", tried = setOf("k-son-shoes")))
        // A case without hints has none to give, so its suspects keep their deflections.
        assertNull(NarratorLines.hint(demo.case, demo.suspects.values, emptySet(), emptySet()))
        assertNull(NarratorLines.hint(demo.case, demo.suspects.values, demo.case.clues.map { it.id }.toSet(), emptySet()))
    }

    @Test
    fun `the narrator's own lines give nothing away, since they skip the spoiler guard`() {
        // The formats are pinned: a confront hint names the clue only, and the nudge only says the
        // evidence is in, since the accusation itself comes after arrival.
        assertEquals("탐정님, 부엌 뒷문 발자국 이야기를 아직 물어보지 않은 사람이 있어요.", NarratorLines.confrontHint(backDoor))
        assertEquals("탐정님, 이제 범인을 가려낼 단서와 증언이 다 모인 것 같아요.", NarratorLines.ACCUSE_NUDGE)
        val names = case.suspects.flatMap { it.aliases + it.name }
        for (suspect in suspects) {
            for (fact in suspect.unlockable.filter { it.key }) {
                val found = fact.unlockedBy.toSet()
                // With the other reactions drawn out, this one is the hint, at the earliest it can come.
                val hint = NarratorLines.hint(case, suspects, found, allKeyReactions - fact.id).orEmpty()
                val secret = SuspectPrompt.lockedKeywords(case, found) + case.forbiddenPhrases

                assertEquals(NarratorLines.confrontHint(case.clues.last { it.id in found }), hint)
                assertEquals("${fact.id}: $hint", emptyList<String>(), phraseHits(hint, secret))
                // Naming whose reaction is left would point at the culprit when it is hers.
                assertEquals("${fact.id}: $hint", emptyList<String>(), names.filter { it in hint })
            }
        }
        assertEquals(emptyList<String>(), phraseHits(NarratorLines.ACCUSE_NUDGE, case.forbiddenPhrases))
    }
}
