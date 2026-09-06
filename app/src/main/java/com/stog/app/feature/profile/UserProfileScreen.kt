package com.stog.app.feature.profile

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.util.Log
import com.stog.app.R
import com.stog.app.feature.record.ArchivePhoto
import com.stog.app.feature.record.PhotoApiClient
import com.stog.app.feature.record.PhotoVisibility
import com.stog.app.feature.social.SocialThumbnail
import com.stog.app.feature.social.SocialThumbnailMedia
import com.stog.app.feature.social.SocialThumbnailMediaStatus
import com.stog.app.feature.space.MapMenu
import com.stog.app.feature.space.StogMainScaffold
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceAction
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogYellow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ScreenGutter = 24.dp
private val SectionRadius = 16.dp
private val HeroRadius = 24.dp

@Composable
internal fun UserProfileScreen(
    nickname: String?,
    authenticated: Boolean,
    onLogin: () -> Unit,
    onSearch: () -> Unit,
    onMenuSelected: (MapMenu) -> Unit,
    onOpenStobee: () -> Unit,
    modifier: Modifier = Modifier,
    baseUrl: String = "",
    accessToken: String? = null,
    onLogout: () -> Unit = {},
) {
    val context = LocalContext.current
    val profileApi = remember(baseUrl) { ProfileApiClient(baseUrl) }
    val photoApi = remember(baseUrl) { PhotoApiClient(baseUrl) }
    val imageStore = remember(context) { ProfileImageStore(context) }
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<ProfileSummary?>(null) }
    var profileScores by remember { mutableStateOf<ProfileScores?>(null) }
    var profilePhotos by remember { mutableStateOf<List<ArchivePhoto>>(emptyList()) }
    var selectedPhoto by remember { mutableStateOf<ProfileGridPhoto?>(null) }
    var uploadingAvatar by remember { mutableStateOf(false) }
    var avatarRevision by remember { mutableIntStateOf(0) }
    var profileMessage by remember { mutableStateOf<String?>(null) }
    var photoMessage by remember { mutableStateOf<String?>(null) }
    var profileLoading by remember { mutableStateOf(false) }
    var profileLoadError by remember { mutableStateOf(false) }
    var profileScoresLoading by remember { mutableStateOf(false) }
    var profileScoresLoadError by remember { mutableStateOf(false) }
    var photosLoading by remember { mutableStateOf(false) }
    var photosLoadError by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    val avatarPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val token = accessToken
        if (uri == null || token.isNullOrBlank()) return@rememberLauncherForActivityResult
        scope.launch {
            uploadingAvatar = true
            profileMessage = null
            var file: File? = null
            runCatching {
                file = withContext(Dispatchers.IO) { imageStore.prepare(uri) }
                withContext(Dispatchers.IO) {
                    profileApi.uploadAvatar(token, requireNotNull(file))
                }
            }.onSuccess { loaded ->
                profile = loaded
                avatarRevision++
                profileMessage = "프로필 사진이 변경됐어요."
            }.onFailure { error ->
                Log.e("STOG.Upload", "profile avatar upload failed", error)
                profileMessage = "프로필 사진을 변경하지 못했어요."
            }
            withContext(Dispatchers.IO) { file?.delete() }
            uploadingAvatar = false
        }
    }

    LaunchedEffect(authenticated, accessToken, baseUrl, reloadKey) {
        val token = accessToken
        if (!authenticated || token.isNullOrBlank()) {
            profile = null
            profileScores = null
            profilePhotos = emptyList()
            profileLoading = false
            profileLoadError = false
            profileScoresLoading = false
            profileScoresLoadError = false
            photosLoading = false
            photosLoadError = false
            return@LaunchedEffect
        }
        profileLoading = true
        profileScoresLoading = true
        photosLoading = true
        profileLoadError = false
        profileScoresLoadError = false
        photosLoadError = false
        profile = null
        profileScores = null
        profilePhotos = emptyList()
        val profileResult = runCatching {
            withContext(Dispatchers.IO) { profileApi.summary(token) }
        }
        profile = profileResult.getOrNull()
        profileLoadError = profileResult.isFailure
        profileLoading = false
        val profileScoresResult = runCatching {
            withContext(Dispatchers.IO) { profileApi.profile(token) }
        }
        profileScores = profileScoresResult.getOrNull()
        profileScoresLoadError = profileScoresResult.isFailure
        profileScoresLoading = false
        val photosResult = runCatching {
            withContext(Dispatchers.IO) { photoApi.mine(token) }
        }
        profilePhotos = photosResult.getOrDefault(emptyList())
        photosLoadError = photosResult.isFailure
        photosLoading = false
    }

    BackHandler(enabled = selectedPhoto != null) { selectedPhoto = null }
    BackHandler(enabled = selectedPhoto == null) { onMenuSelected(MapMenu.HOME) }
    val displayNickname = profile?.nickname ?: nickname

    StogMainScaffold(
        selectedMenu = MapMenu.RECOMMENDATIONS,
        onSearch = onSearch,
        onMenuSelected = onMenuSelected,
        onOpenStobee = onOpenStobee,
        showHeader = false,
        modifier = modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("profile_scroll")
                .statusBarsPadding(),
            contentPadding = PaddingValues(
                start = ScreenGutter,
                top = ScreenGutter,
                end = ScreenGutter,
                bottom = ScreenGutter,
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            item {
                ProfileHeader(
                    authenticated = authenticated,
                    onLogout = onLogout,
                )
            }
            if (selectedPhoto != null) {
                item {
                    ProfilePhotoDetail(
                        photo = requireNotNull(selectedPhoto),
                        onClose = { selectedPhoto = null },
                        message = photoMessage,
                        onVisibilityChange = { visibility ->
                            val token = accessToken ?: return@ProfilePhotoDetail
                            val ownedPhoto = profilePhotos.firstOrNull {
                                it.id == selectedPhoto?.id
                            } ?: return@ProfilePhotoDetail
                            photoMessage = null
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        photoApi.changeVisibility(token, ownedPhoto.id, visibility)
                                    }
                                }.onSuccess { updated ->
                                    profilePhotos = profilePhotos.map { photo ->
                                        if (photo.id == updated.id) {
                                            photo.copy(visibility = updated.visibility)
                                        } else {
                                            photo
                                        }
                                    }
                                    selectedPhoto = selectedPhoto?.copy(
                                        visibility = updated.visibility,
                                    )
                                }.onFailure {
                                    photoMessage = "사진 공개 상태를 변경하지 못했어요."
                                }
                            }
                        },
                    )
                }
            }
            item {
                ProfileSummary(
                    nickname = displayNickname,
                    authenticated = authenticated,
                    onLogin = onLogin,
                    profileImageUrl = profile?.profileImageUrl,
                    avatarRevision = avatarRevision,
                    avatarUploading = uploadingAvatar,
                    onAvatarClick = {
                        if (authenticated && !uploadingAvatar) {
                            avatarPicker.launch(
                                PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly,
                                ),
                            )
                        } else if (!authenticated) {
                            onLogin()
                        }
                    },
                )
            }
            if (profileMessage != null) {
                item {
                    Text(
                        text = requireNotNull(profileMessage),
                        color = StogMuted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("profile_feedback"),
                    )
                }
            }
            if (authenticated) {
                item {
                    if (profileLoadError) {
                        StogStatePanel(
                            state = StogSurfaceState.ERROR,
                            title = "프로필 정보를 불러오지 못했어요.",
                            detail = "잠시 후 다시 시도해 주세요.",
                            action = StogSurfaceAction.RETRY,
                            actionLabel = "다시 시도",
                            onAction = { reloadKey++ },
                        )
                    } else {
                        ProfileStatsSection(
                            tripCount = profile?.tripCount,
                            visitedCellCount = profile?.visitedCellCount,
                            honeyBalance = profile?.honeyBalance,
                            loading = profileLoading,
                        )
                    }
                }
                item {
                    when {
                        profileScoresLoading -> StogStatePanel(
                            state = StogSurfaceState.LOADING,
                            title = "여행 성향을 불러오는 중이에요.",
                            detail = "설문 결과를 정리하고 있어요.",
                        )
                        profileScoresLoadError -> StogStatePanel(
                            state = StogSurfaceState.ERROR,
                            title = "여행 성향을 불러오지 못했어요.",
                            detail = "잠시 후 다시 시도해 주세요.",
                            action = StogSurfaceAction.RETRY,
                            actionLabel = "다시 시도",
                            onAction = { reloadKey++ },
                        )
                        profileScores != null -> ProfileCharacterSection(
                            scores = requireNotNull(profileScores),
                        )
                    }
                }
                item {
                    ProfilePhotoGridSection(
                        photos = profilePhotos.map(ArchivePhoto::toProfileGridPhoto),
                        loading = photosLoading,
                        error = photosLoadError,
                        onRetry = { reloadKey++ },
                        onPhotoClick = { selectedPhoto = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(
    authenticated: Boolean,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("프로필", color = StogInk, style = MaterialTheme.typography.headlineSmall)
        if (authenticated) {
            TextButton(
                onClick = onLogout,
                modifier = Modifier.stogTouchTarget(),
            ) {
                Text("로그아웃")
            }
        }
    }
}

private data class ProfileGridPhoto(
    val id: Long,
    val objectKey: String,
    val media: SocialThumbnailMedia,
    val caption: String?,
    val cellId: String?,
    val latitude: Double?,
    val longitude: Double?,
    val takenAt: String?,
    val visibility: PhotoVisibility?,
    val owned: Boolean,
)

private fun ArchivePhoto.toProfileGridPhoto() = ProfileGridPhoto(
    id = id,
    objectKey = thumbnailUrl ?: "photo-$id",
    media = thumbnailMedia(thumbnailUrl),
    caption = caption,
    cellId = cellId,
    latitude = latitude,
    longitude = longitude,
    takenAt = takenAt,
    visibility = visibility,
    owned = true,
)

private fun thumbnailMedia(url: String?): SocialThumbnailMedia =
    if (url.isNullOrBlank()) {
        SocialThumbnailMedia(SocialThumbnailMediaStatus.UNAVAILABLE, null)
    } else {
        SocialThumbnailMedia(SocialThumbnailMediaStatus.AVAILABLE, url)
    }

@Composable
private fun ProfilePhotoGridSection(
    photos: List<ProfileGridPhoto>,
    loading: Boolean,
    error: Boolean,
    onRetry: () -> Unit,
    onPhotoClick: (ProfileGridPhoto) -> Unit,
) {
    Column(
        modifier = Modifier.testTag("profile_photo_grid"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "내 사진",
                color = StogInk,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (loading || error) "—" else "${photos.size}장",
                color = StogMuted,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        when {
            loading -> StogStatePanel(
                state = StogSurfaceState.LOADING,
                title = "사진을 불러오는 중이에요.",
                detail = "여행 기록을 정리하고 있어요.",
            )
            error -> StogStatePanel(
                state = StogSurfaceState.ERROR,
                title = "사진을 불러오지 못했어요.",
                detail = "네트워크를 확인한 뒤 다시 시도해 주세요.",
                action = StogSurfaceAction.RETRY,
                actionLabel = "다시 시도",
                onAction = onRetry,
            )
            photos.isEmpty() -> Text(
                text = "카메라에서 사진을 남기면 이곳에 모여요.",
                color = StogMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> ProfilePhotoGrid(photos, onPhotoClick)
        }
    }
}

@Composable
private fun ProfilePhotoGrid(
    photos: List<ProfileGridPhoto>,
    onPhotoClick: (ProfileGridPhoto) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        photos.chunked(3).forEach { rowPhotos ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rowPhotos.forEach { photo ->
                    val visibilityLabel = if (photo.visibility == PhotoVisibility.PUBLIC) {
                        "전체 공개"
                    } else {
                        "비공개"
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { onPhotoClick(photo) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = photo.caption
                                    ?.takeIf(String::isNotBlank)
                                    ?.let { "사진 $it" }
                                    ?: "사진 상세"
                                role = Role.Button
                                if (photo.owned) {
                                    stateDescription = visibilityLabel
                                }
                            },
                    ) {
                        SocialThumbnail(
                            photoId = photo.id,
                            objectKey = photo.objectKey,
                            media = photo.media,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (photo.owned && photo.visibility != PhotoVisibility.PUBLIC) {
                            Text(
                                text = "비공개",
                                color = StogCanvas,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp),
                            )
                        }
                    }
                }
                repeat(3 - rowPhotos.size) {
                    Box(modifier = Modifier.weight(1f).aspectRatio(1f))
                }
            }
        }
    }
}

@Composable
private fun ProfilePhotoDetail(
    photo: ProfileGridPhoto,
    onClose: () -> Unit,
    message: String?,
    onVisibilityChange: (PhotoVisibility) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_photo_detail")
            .border(1.dp, StogBorder, RoundedCornerShape(SectionRadius))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("사진 상세", color = StogInk, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onClose, modifier = Modifier.stogTouchTarget()) { Text("닫기") }
        }
        SocialThumbnail(
            photoId = photo.id,
            objectKey = photo.objectKey,
            media = photo.media,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(12.dp)),
        )
        photo.caption?.takeIf(String::isNotBlank)?.let {
            Text(it, color = StogInk, style = MaterialTheme.typography.bodyMedium)
        }
        photo.cellId?.let {
            Text("CELL $it", color = StogMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (photo.latitude != null && photo.longitude != null) {
            Text(
                "위치 ${"%.5f".format(photo.latitude)}, ${"%.5f".format(photo.longitude)}",
                color = StogMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        photo.takenAt?.let {
            Text("촬영 시각 $it", color = StogMuted, style = MaterialTheme.typography.bodySmall)
        }
        if (photo.owned) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("전체 공개", color = StogInk)
                Switch(
                    checked = photo.visibility == PhotoVisibility.PUBLIC,
                    onCheckedChange = {
                        onVisibilityChange(
                            if (it) PhotoVisibility.PUBLIC else PhotoVisibility.PRIVATE,
                        )
                    },
                    modifier = Modifier
                        .stogTouchTarget()
                        .semantics { contentDescription = "사진 전체 공개" },
                )
            }
        }
        message?.let {
            Text(
                text = it,
                color = StogMuted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ProfileSummary(
    nickname: String?,
    authenticated: Boolean,
    onLogin: () -> Unit,
    profileImageUrl: String? = null,
    avatarRevision: Int = 0,
    avatarUploading: Boolean = false,
    onAvatarClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, StogBorder, RoundedCornerShape(HeroRadius))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(StogYellow.copy(alpha = 0.16f))
                    .border(1.dp, StogBorder, CircleShape)
                    .clickable(onClick = onAvatarClick)
                    .semantics(mergeDescendants = true) {
                        contentDescription = if (authenticated) {
                            "프로필 사진 변경"
                        } else {
                            "로그인하고 프로필 사진 설정"
                        }
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center,
            ) {
                if (profileImageUrl.isNullOrBlank()) {
                    Image(
                        painter = painterResource(R.drawable.stog_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        colorFilter = ColorFilter.tint(StogYellow),
                    )
                } else {
                    key(avatarRevision, profileImageUrl) {
                        SocialThumbnail(
                            photoId = 0L,
                            objectKey = profileImageUrl,
                            media = thumbnailMedia(profileImageUrl),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                if (avatarUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        color = StogYellow,
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = nickname?.takeIf(String::isNotBlank) ?: "게스트 여행자",
                    color = StogInk,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!authenticated) {
            Button(
                onClick = onLogin,
                modifier = Modifier
                    .fillMaxWidth()
                    .stogTouchTarget(),
                shape = RoundedCornerShape(SectionRadius),
            ) {
                Text("로그인하고 기록 연결")
            }
        }
    }
}

@Composable
private fun ProfileStatsSection(
    tripCount: Long?,
    visitedCellCount: Long?,
    honeyBalance: Long?,
    loading: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "여행 기록",
            color = StogInk,
            style = MaterialTheme.typography.titleMedium,
        )
        ProfileStats(
            tripCount = tripCount,
            visitedCellCount = visitedCellCount,
            honeyBalance = honeyBalance,
            loading = loading,
        )
    }
}

@Composable
private fun ProfileStats(
    tripCount: Long?,
    visitedCellCount: Long?,
    honeyBalance: Long?,
    loading: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("profile_stats")
            .border(1.dp, StogBorder, RoundedCornerShape(SectionRadius))
            .padding(vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileStat("내 여행", if (loading) "—" else tripCount?.toString() ?: "—", Modifier.weight(1f))
        VerticalDivider(modifier = Modifier.size(width = 1.dp, height = 40.dp), color = StogBorder)
        ProfileStat(
            "나의 셀",
            if (loading) "—" else visitedCellCount?.toString() ?: "—",
            Modifier.weight(1f),
        )
        VerticalDivider(modifier = Modifier.size(width = 1.dp, height = 40.dp), color = StogBorder)
        ProfileStat("꿀", if (loading) "—" else honeyBalance?.toString() ?: "—", Modifier.weight(1f))
    }
}

@Composable
private fun ProfileStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, color = StogMuted, style = MaterialTheme.typography.labelMedium)
        Text(value, color = StogInk, style = MaterialTheme.typography.titleMedium)
    }
}



