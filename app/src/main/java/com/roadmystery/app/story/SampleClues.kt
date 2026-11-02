package com.roadmystery.app.story

/** 임시 데모용 단서. 시나리오 데이터 형식이 정해지면 assets로 옮긴다. */
object SampleClues {
    val all: List<Clue> =
        listOf(
            Clue(
                id = "clue_receipt",
                title = "찢어진 영수증",
                description = "밤 11시 42분, 휴게소 카페에서 커피 두 잔이 결제됐다. 피해자는 혼자였다고 했다.",
            ),
            Clue(
                id = "clue_dashcam",
                title = "블랙박스의 공백",
                description = "23시 50분부터 15분 동안 블랙박스 녹화가 끊겨 있다. 누군가 전원을 뽑았다.",
            ),
            Clue(
                id = "clue_key",
                title = "젖은 차 열쇠",
                description = "그날 밤 비는 오지 않았다. 그런데 피해자의 차 열쇠고리가 축축하게 젖어 있었다.",
            ),
        )
}
