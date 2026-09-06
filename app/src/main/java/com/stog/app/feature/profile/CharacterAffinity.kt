package com.stog.app.feature.profile

internal enum class StogCharacter(
    val scoreKey: String,
    val displayName: String,
    val role: String,
    val meaning: String,
    val assetName: String,
    val accentArgb: Long,
) {
    ACTI(
        scoreKey = "experience",
        displayName = "액티",
        role = "체험 활동가",
        meaning = "열정 · 에너지 · 도전 · 활동성",
        assetName = "character_acti.png",
        accentArgb = 0xFFE53935L,
    ),
    HONEY(
        scoreKey = "food",
        displayName = "허니",
        role = "미식 기록가",
        meaning = "즐거움 · 활기 · 미식 · 친화력",
        assetName = "character_honey.png",
        accentArgb = 0xFFFB8C00L,
    ),
    WING(
        scoreKey = "culture",
        displayName = "윙",
        role = "시간 여행자",
        meaning = "호기심 · 지적 탐구 · 발견",
        assetName = "character_wing.png",
        accentArgb = 0xFFFBC02DL,
    ),
    BUZZ(
        scoreKey = "nature",
        displayName = "버즈",
        role = "숲길 탐험가",
        meaning = "자연 · 탐방 · 치유 · 균형",
        assetName = "character_buzz.png",
        accentArgb = 0xFF43A047L,
    ),
    LILY(
        scoreKey = "relaxation",
        displayName = "릴리",
        role = "휴식 여행자",
        meaning = "평온 · 여유 · 휴식 · 재충전",
        assetName = "character_lily.png",
        accentArgb = 0xFF1E88E5L,
    ),
    PICK(
        scoreKey = "shopping",
        displayName = "픽",
        role = "쇼핑 탐험가",
        meaning = "취향 · 개성 · 발견 · 쇼핑",
        assetName = "character_pick.png",
        accentArgb = 0xFF8E24AAL,
    ),
}

internal data class CharacterAffinity(
    val character: StogCharacter,
    val score: Double,
)

internal fun characterAffinities(
    preferenceScores: Map<String, Double>,
): List<CharacterAffinity> =
    StogCharacter.entries
        .map { character ->
            CharacterAffinity(
                character = character,
                score = preferenceScores[character.scoreKey] ?: 0.0,
            )
        }
        .sortedWith(
            compareByDescending<CharacterAffinity> { it.score }
                .thenBy { it.character.ordinal },
        )
