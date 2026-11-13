package com.roadmystery.app.voice

/** 완성된 인식 결과에만 적용. 질문 속 단어를 정지 명령으로 오인하지 않는다. */
fun isStopCommand(text: String): Boolean =
    text.trim().trimEnd('.', '!', '?', '。').replace(Regex("\\s+"), "") in
        setOf("멈춰", "멈춰줘", "멈춰주세요", "중지", "일시정지", "정지해줘")
