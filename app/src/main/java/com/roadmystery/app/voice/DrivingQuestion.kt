package com.roadmystery.app.voice

import com.roadmystery.app.story.Suspect

private val noQuestionWords = setOf(
    "없어", "없어요", "없습니다", "질문", "아니", "아니요", "아뇨", "괜찮아", "괜찮아요",
    "괜찮습니다", "됐어요", "계속", "계속해", "계속해요", "계속해줘", "계속해주세요",
    "들려주세요", "다음", "다음으로", "넘어가", "넘어가요", "그냥", "해주세요", "주세요"
)
private val wordBreak = Regex("[^\\p{L}\\p{N}]+")

/** 팀 VoiceAgent 모듈이 붙기 전에도 시연 시 이름을 부른 질문을 받을 수 있다. */
internal fun resolveDrivingQuestionSuspect(question: String, suspects: List<Suspect>, previousSuspectId: String?): String? {
    val words = question.split(wordBreak).filter(String::isNotBlank)
    if (words.all { it in noQuestionWords }) return null

    val named = suspects.filter { suspect ->
        val fullName = suspect.name.replace(wordBreak, "")
        val givenName = fullName.drop(1).takeIf { fullName.length >= 3 }
        words.any { word -> word.startsWith(fullName) || (givenName != null && word.startsWith(givenName)) }
    }
    return when (named.size) {
        0 -> suspects.firstOrNull { it.id == previousSuspectId }?.id
        1 -> named.single().id
        else -> null
    }
}
