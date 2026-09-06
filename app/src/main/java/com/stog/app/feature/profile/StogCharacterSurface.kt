package com.stog.app.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.stog.app.ui.StogAssetImage
import com.stog.app.ui.StogUiContract
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PreferenceSurveyResultScreen(
    result: ProfileSurveyResult,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val affinities = remember(result.preferenceScores) {
        characterAffinities(result.preferenceScores)
    }
    val primary = requireNotNull(affinities.firstOrNull())

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("여행 성향 결과") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("survey_result_scroll"),
            contentPadding = PaddingValues(
                horizontal = StogUiContract.ScreenGutterDp.dp,
                vertical = StogUiContract.ScreenGutterDp.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp * 2),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp)) {
                    Text(
                        text = "당신의 여행 캐릭터",
                        color = StogInk,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        text = "설문 결과를 바탕으로 가장 가까운 캐릭터를 찾았어요.",
                        color = StogMuted,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                CharacterPrimaryCard(
                    affinity = primary,
                    imageSize = 168.dp,
                    testTag = "survey_result_primary",
                )
            }
            item {
                CharacterAffinityFeed(
                    affinities = affinities,
                    testTag = "survey_result_affinities",
                )
            }
            item {
                Button(
                    onClick = onContinue,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = StogUiContract.MinTouchTargetDp.dp),
                ) {
                    Text("여행 시작하기")
                }
            }
        }
    }
}

@Composable
internal fun ProfileCharacterSection(
    scores: ProfileScores,
    modifier: Modifier = Modifier,
) {
    val affinities = remember(scores.preferenceScores) {
        characterAffinities(scores.preferenceScores)
    }
    val primary = requireNotNull(affinities.firstOrNull())

    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("profile_character"),
        verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp * 2),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp)) {
            Text(
                text = "나의 여행 캐릭터",
                color = StogInk,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "설문에서 쌓인 여행 취향을 캐릭터로 보여드려요.",
                color = StogMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        CharacterPrimaryCard(
            affinity = primary,
            imageSize = 112.dp,
            testTag = "profile_primary_character",
        )
        CharacterAffinityFeed(
            affinities = affinities,
            testTag = "profile_character_affinities",
        )
    }
}

@Composable
private fun CharacterPrimaryCard(
    affinity: CharacterAffinity,
    imageSize: androidx.compose.ui.unit.Dp,
    testTag: String,
) {
    val character = affinity.character
    val accent = Color(character.accentArgb)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.68f)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(
            StogUiContract.LargeRadiusDp.dp,
        ),
    ) {
        Row(
            modifier = Modifier.padding(StogUiContract.BaseSpacingDp.dp * 2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StogAssetImage(
                assetPath = character.assetName,
                contentDescription = "${character.displayName} 캐릭터",
                modifier = Modifier.size(imageSize),
                contentScale = ContentScale.Fit,
                maxDimensionPx = 640,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = StogUiContract.BaseSpacingDp.dp * 2),
                verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
            ) {
                Text(
                    text = character.displayName,
                    color = StogInk,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = character.role,
                    color = accent,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = character.meaning,
                    color = StogMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = "성향 점수 ${scorePercent(affinity.score)}%",
                    color = StogInk,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun CharacterAffinityFeed(
    affinities: List<CharacterAffinity>,
    testTag: String,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp * 2),
    ) {
        Text(
            text = "전체 성향",
            color = StogInk,
            style = MaterialTheme.typography.titleSmall,
        )
        affinities.forEach { affinity ->
            CharacterAffinityRow(affinity)
        }
    }
}

@Composable
private fun CharacterAffinityRow(affinity: CharacterAffinity) {
    val character = affinity.character
    val accent = Color(character.accentArgb)
    val percentage = scorePercent(affinity.score)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("character_affinity_${character.scoreKey}")
            .semantics {
                stateDescription = "성향 점수 $percentage%"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StogAssetImage(
            assetPath = character.assetName,
            contentDescription = "${character.displayName} 캐릭터",
            modifier = Modifier.size(64.dp),
            contentScale = ContentScale.Fit,
            maxDimensionPx = 512,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = StogUiContract.BaseSpacingDp.dp * 2),
            verticalArrangement = Arrangement.spacedBy(StogUiContract.BaseSpacingDp.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = character.displayName,
                        color = StogInk,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = character.role,
                        color = StogMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = "$percentage%",
                    color = StogInk,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            LinearProgressIndicator(
                progress = { affinity.score.toFloat().coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = accent,
                trackColor = StogBorder,
            )
        }
    }
}

private fun scorePercent(score: Double): Int =
    (score.coerceIn(0.0, 1.0) * 100).roundToInt()
