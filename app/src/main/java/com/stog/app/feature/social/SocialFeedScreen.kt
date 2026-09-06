package com.stog.app.feature.social

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.stog.app.R
import com.stog.app.feature.space.MapCellTarget
import com.stog.app.feature.space.MapMenu
import com.stog.app.feature.space.StogMainScaffold
import com.stog.app.ui.StogMediaPlaceholder
import com.stog.app.ui.StogMediaPlaceholderKind
import com.stog.app.ui.StogStatePanel
import com.stog.app.ui.StogSurfaceAction
import com.stog.app.ui.StogSurfaceState
import com.stog.app.ui.stogTouchTarget
import com.stog.app.ui.theme.StogBorder
import com.stog.app.ui.theme.StogCanvas
import com.stog.app.ui.theme.StogInk
import com.stog.app.ui.theme.StogMuted
import com.stog.app.ui.theme.StogSurface
import com.stog.app.ui.theme.StogYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.net.HttpURLConnection
import java.net.URL

@Composable
internal fun DiscoverScreen(
    baseUrl: String,
    accessToken: String?,
    onLoginRequired: () -> Unit,
    onSearch: () -> Unit,
    onMenuSelected: (MapMenu) -> Unit,
    onOpenMap: (MapCellTarget) -> Unit = {},
    onOpenStobee: () -> Unit,
    modifier: Modifier = Modifier,
    fixturePosts: List<DiscoverFeedPost>? = null,
    onFixturePostChanged: (DiscoverFeedPost) -> Unit = {},
    viewerNickname: String? = null,
) {
    BackHandler { onMenuSelected(MapMenu.HOME) }

    StogMainScaffold(
        selectedMenu = MapMenu.SOCIAL,
        onSearch = onSearch,
        onMenuSelected = onMenuSelected,
        onOpenStobee = onOpenStobee,
        showHeader = false,
        modifier = modifier.fillMaxSize(),
    ) {
        SocialFeedScreen(
            baseUrl = baseUrl,
            accessToken = accessToken,
            onLoginRequired = onLoginRequired,
            fixturePosts = fixturePosts,
            onFixturePostChanged = onFixturePostChanged,
            viewerNickname = viewerNickname,
            onOpenMap = onOpenMap,
            modifier = Modifier
                .fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SocialFeedScreen(
    baseUrl: String,
    accessToken: String?,
    onLoginRequired: () -> Unit,
    onOpenMap: (MapCellTarget) -> Unit = {},
    modifier: Modifier = Modifier,
    initialState: SocialFeedUiState = initialSocialFeedState(),
    autoRefresh: Boolean = true,
    fixturePosts: List<DiscoverFeedPost>? = null,
    onFixturePostChanged: (DiscoverFeedPost) -> Unit = {},
    viewerNickname: String? = null,
) {
    if (fixturePosts != null) {
        FixtureSocialFeedScreen(
            posts = fixturePosts,
            viewerNickname = viewerNickname,
            onPostChanged = onFixturePostChanged,
            modifier = modifier,
        )
        return
    }

    val client = remember(baseUrl) { SocialApiClient(baseUrl) }
    val scope = rememberCoroutineScope()
    var state by remember(initialState) { mutableStateOf(initialState) }
    var refreshing by remember { mutableStateOf(false) }
    var pendingLikes by remember { mutableStateOf(emptySet<Long>()) }
    var pendingSaves by remember { mutableStateOf(emptySet<Long>()) }
    var selectedCommentPhotoId by remember { mutableStateOf<Long?>(null) }
    var comments by remember { mutableStateOf<List<SocialComment>>(emptyList()) }
    var commentsLoading by remember { mutableStateOf(false) }
    var commentsMessage by remember { mutableStateOf<String?>(null) }
    var commentDraft by remember { mutableStateOf("") }
    var commentPosting by remember { mutableStateOf(false) }

    suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        state = SocialFeedUiState.Loading
        state = runCatching {
            withContext(Dispatchers.IO) { client.feed(accessToken, cursor = null) }
        }.fold(
            onSuccess = ::socialRefreshSucceeded,
            onFailure = ::socialRefreshFailed,
        )
        refreshing = false
    }

    suspend fun loadNext(content: SocialFeedUiState.Content) {
        val cursor = content.nextCursor ?: return
        if (content.loadingNext) return
        val loadingState = socialNextPageStarted(content)
        state = loadingState
        state = runCatching {
            withContext(Dispatchers.IO) { client.feed(accessToken, cursor) }
        }.fold(
            onSuccess = { page -> socialNextPageSucceeded(loadingState, page) },
            onFailure = { socialNextPageFailed(loadingState) },
        )
    }

    LaunchedEffect(baseUrl, accessToken, autoRefresh) {
        if (autoRefresh) refresh()
    }

    fun toggleLike(item: SocialFeedItem) {
        val token = accessToken
        if (token.isNullOrBlank()) {
            onLoginRequired()
            return
        }
        val content = state as? SocialFeedUiState.Content ?: return
        if (item.photoId in pendingLikes) return
        pendingLikes = pendingLikes + item.photoId
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (item.likedByViewer) client.unlike(token, item.photoId)
                    else client.like(token, item.photoId)
                }
            }.onSuccess { likeState ->
                val current = state as? SocialFeedUiState.Content ?: return@onSuccess
                state = socialLikeSucceeded(current, likeState)
            }.onFailure { error ->
                if (error is SocialRequestException && error.statusCode == 401) {
                    onLoginRequired()
                } else {
                    val current = state as? SocialFeedUiState.Content
                    if (current != null) state = socialLikeFailed(current)
                }
            }
            pendingLikes = pendingLikes - item.photoId
        }
    }

    fun toggleSave(item: SocialFeedItem) {
        val token = accessToken
        if (token.isNullOrBlank()) {
            onLoginRequired()
            return
        }
        val content = state as? SocialFeedUiState.Content ?: return
        if (item.photoId in pendingSaves) return
        pendingSaves = pendingSaves + item.photoId
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    if (item.savedByViewer) client.unsave(token, item.photoId)
                    else client.save(token, item.photoId)
                }
            }.onSuccess { saveState ->
                val current = state as? SocialFeedUiState.Content ?: return@onSuccess
                state = socialSaveSucceeded(current, saveState)
            }.onFailure { error ->
                if (error is SocialRequestException && error.statusCode == 401) {
                    onLoginRequired()
                } else {
                    val current = state as? SocialFeedUiState.Content
                    if (current != null) state = socialSaveFailed(current)
                }
            }
            pendingSaves = pendingSaves - item.photoId
        }
    }

    fun openComments(item: SocialFeedItem) {
        selectedCommentPhotoId = item.photoId
        comments = emptyList()
        commentsMessage = null
        commentDraft = ""
        commentsLoading = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { client.comments(accessToken, item.photoId) }
            }.onSuccess {
                comments = it
            }
            commentsLoading = false
        }
    }

    fun submitComment() {
        val photoId = selectedCommentPhotoId ?: return
        val token = accessToken
        if (token.isNullOrBlank()) {
            onLoginRequired()
            return
        }
        val body = commentDraft.trim()
        if (body.isBlank() || commentPosting) return
        commentPosting = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { client.addComment(token, photoId, body) }
            }.onSuccess { comment ->
                comments = comments + comment
                commentDraft = ""
                val current = state as? SocialFeedUiState.Content
                if (current != null) {
                    state = current.copy(
                        items = current.items.map { item ->
                            if (item.photoId == photoId) {
                                item.copy(commentCount = item.commentCount + 1)
                            } else {
                                item
                            }
                        },
                    )
                }
            }.onFailure {
                commentsMessage = "댓글을 등록하지 못했어요."
            }
            commentPosting = false
        }
    }

    val feedListState = rememberLazyListState()
    LaunchedEffect(feedListState, state) {
        snapshotFlow {
            val layout = feedListState.layoutInfo
            layout.visibleItemsInfo.lastOrNull()?.index to layout.totalItemsCount
        }.distinctUntilChanged().collectLatest { (lastVisible, total) ->
            val content = state as? SocialFeedUiState.Content ?: return@collectLatest
            if (
                content.nextCursor != null &&
                    !content.loadingNext &&
                    total > 0 &&
                    lastVisible != null &&
                    lastVisible >= total - 2
            ) {
                loadNext(content)
            }
        }
    }

    val offlineFailure = state as? SocialFeedUiState.Failure
    val emptyFeed = state == SocialFeedUiState.Empty
    val centeredStatus = when {
        state == SocialFeedUiState.Loading -> {
            "여행 기록을 불러오는 중" to "공개된 여행의 사진을 확인하고 있어요."
        }
        offlineFailure?.reason == SocialFeedFailure.OFFLINE -> {
            "네트워크 연결을 확인해 주세요" to "잠시 후 다시 확인해 주세요."
        }
        else -> null
    }
    if (centeredStatus != null || emptyFeed) {
        Column(
            modifier = modifier.fillMaxSize().background(StogCanvas),
        ) {
            DiscoverEditorialHero()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (emptyFeed) {
                        Icon(
                            painter = painterResource(R.drawable.ic_post_empty),
                            contentDescription = "새로운 게시물 없음",
                            modifier = Modifier.size(48.dp),
                            tint = StogMuted,
                        )
                        Text(
                            "아직 새로운 게시물이 없습니다.",
                            color = StogInk,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "새로운 공개 여행 게시물이 올라오면 이곳에서 확인할 수 있어요.",
                            color = StogMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        val status = checkNotNull(centeredStatus)
                        if (state == SocialFeedUiState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(36.dp),
                                color = StogInk,
                                strokeWidth = 3.dp,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_network_error),
                                contentDescription = "네트워크 연결 필요",
                                modifier = Modifier.size(40.dp),
                                tint = StogInk,
                            )
                        }
                        Text(
                            status.first,
                            color = StogInk,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            status.second,
                            color = StogMuted,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    } else {
        LazyColumn(
            state = feedListState,
            modifier = modifier.fillMaxSize().background(StogCanvas),
            contentPadding = PaddingValues(
                top = 0.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            item {
                DiscoverEditorialHero()
            }
            when (val current = state) {
            SocialFeedUiState.Loading -> item {
                StogStatePanel(
                    state = StogSurfaceState.LOADING,
                    title = "여행 기록을 불러오는 중",
                    detail = "공개된 여행의 사진을 확인하고 있어요.",
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
            SocialFeedUiState.Empty -> Unit
                is SocialFeedUiState.Failure -> item {
                    val surfaceState = when (current.reason) {
                        SocialFeedFailure.AUTH_EXPIRED -> StogSurfaceState.AUTH_EXPIRED
                        SocialFeedFailure.OFFLINE -> StogSurfaceState.OFFLINE
                        SocialFeedFailure.UNKNOWN -> StogSurfaceState.ERROR
                    }
                    StogStatePanel(
                        state = surfaceState,
                        title = when (current.reason) {
                            SocialFeedFailure.AUTH_EXPIRED -> "로그인이 만료되었어요"
                            SocialFeedFailure.OFFLINE -> "네트워크 연결을 확인해 주세요"
                            SocialFeedFailure.UNKNOWN -> "여행 기록을 불러오지 못했어요"
                        },
                        detail = "잠시 후 다시 확인해 주세요.",
                        modifier = Modifier.padding(horizontal = 24.dp),
                        action = StogSurfaceAction.NONE,
                    )
                }
            is SocialFeedUiState.Content -> {
                itemsIndexed(current.items, key = { _, item -> item.photoId }) { index, item ->
                    SocialFeedCard(
                        item = item,
                        likePending = item.photoId in pendingLikes,
                        savePending = item.photoId in pendingSaves,
                        onLike = { toggleLike(item) },
                        onSave = { toggleSave(item) },
                        onComments = { openComments(item) },
                        onOpenMap = {
                            val cellId = item.cellId
                            val latitude = item.latitude
                            val longitude = item.longitude
                            if (
                                cellId != null &&
                                    latitude != null &&
                                    longitude != null
                            ) {
                                onOpenMap(MapCellTarget(cellId, latitude, longitude))
                            }
                        },
                    )
                    if (index < current.items.lastIndex) {
                        HorizontalDivider(color = StogBorder)
                    }
                }
                current.notice?.let { notice ->
                    item {
                        Text(
                            when (notice) {
                                SocialFeedNotice.NEXT_PAGE_FAILED -> "다음 기록을 불러오지 못했어요."
                                SocialFeedNotice.LIKE_FAILED -> "좋아요 상태를 바꾸지 못했어요."
                                SocialFeedNotice.SAVE_FAILED -> "저장 상태를 바꾸지 못했어요."
                            },
                            color = StogMuted,
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
                if (current.nextCursor != null) {
                    item {
                        OutlinedButton(
                            onClick = { scope.launch { loadNext(current) } },
                            enabled = !current.loadingNext,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp)
                                .stogTouchTarget(),
                        ) {
                            Text(if (current.loadingNext) "불러오는 중..." else "더 보기")
                        }
                    }
                }
            }
            }
        }
    }

    selectedCommentPhotoId?.let { photoId ->
        SocialCommentsSheet(
            comments = comments,
            loading = commentsLoading,
            message = commentsMessage,
            draft = commentDraft,
            viewerNickname = viewerNickname,
            posting = commentPosting,
            onDraftChanged = { commentDraft = it },
            onSubmit = ::submitComment,
            onDismiss = { selectedCommentPhotoId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SocialCommentsSheet(
    comments: List<SocialComment>,
    loading: Boolean,
    message: String?,
    draft: String,
    viewerNickname: String?,
    posting: Boolean,
    onDraftChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Color.White,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .size(width = 68.dp, height = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(StogBorder),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.5f)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("댓글", style = MaterialTheme.typography.titleLarge, color = StogInk)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    loading -> CircularProgressIndicator(
                        color = StogInk,
                    )
                    comments.isEmpty() -> Text("아직 댓글이 없습니다", color = StogMuted)
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(comments, key = SocialComment::id) { comment ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Surface(
                                    modifier = Modifier.size(32.dp),
                                    shape = CircleShape,
                                    color = StogYellow.copy(alpha = 0.3f),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = comment.authorName.firstOrNull()?.toString() ?: "여",
                                            color = StogInk,
                                            style = MaterialTheme.typography.labelMedium,
                                        )
                                    }
                                }
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        comment.authorName,
                                        color = StogInk,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(comment.body, color = StogMuted)
                                }
                            }
                        }
                    }
                }
            }
            message?.let { Text(it, color = StogMuted) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = StogYellow.copy(alpha = 0.3f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = viewerNickname?.firstOrNull()?.toString() ?: "나",
                            color = StogInk,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = onDraftChanged,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    singleLine = true,
                    enabled = !posting,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StogBorder,
                    ),
                )
                IconButton(
                    onClick = onSubmit,
                    enabled = draft.isNotBlank() && !posting,
                    modifier = Modifier.stogTouchTarget(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_social_send),
                        contentDescription = "댓글 보내기",
                        tint = if (draft.isNotBlank()) StogInk else StogBorder,
                    )
                }
            }
        }
    }
}

@Composable
private fun FixtureSocialFeedScreen(
    posts: List<DiscoverFeedPost>,
    viewerNickname: String?,
    onPostChanged: (DiscoverFeedPost) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeCommentPostId by remember { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(StogCanvas)
            .semantics { stateDescription = "FE 미리보기" },
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { DiscoverEditorialHero() }
        itemsIndexed(posts, key = { _, post -> post.item.photoId }) { index, post ->
            FixtureSocialFeedCard(
                post = post,
                viewerNickname = viewerNickname,
                onPostChanged = onPostChanged,
                commentsOpen = activeCommentPostId == post.item.photoId,
                onCommentsOpenChanged = { open ->
                    activeCommentPostId = if (open) post.item.photoId else null
                },
            )
            if (index < posts.lastIndex) {
                HorizontalDivider(color = StogBorder)
            }
        }
    }
}

@Composable
private fun DiscoverEditorialHero() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.stog_discover_hero),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 36.dp, end = 180.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "기록은 다른\n여행의 길이 된다",
                color = StogInk,
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "오늘의 추천 여행 이야기를 만나보세요.",
                color = StogMuted,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FixtureSocialFeedCard(
    post: DiscoverFeedPost,
    viewerNickname: String?,
    onPostChanged: (DiscoverFeedPost) -> Unit,
    commentsOpen: Boolean,
    onCommentsOpenChanged: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = StogSurface,
    ) {
        Column(modifier = Modifier) {
            if (commentsOpen) {
                DiscoverCommentsPanel(
                    post = post,
                    viewerNickname = viewerNickname,
                    onDismiss = { onCommentsOpenChanged(false) },
                    onCommentAdded = { body ->
                        onPostChanged(
                            post.addComment(
                                authorName = viewerNickname?.takeIf(String::isNotBlank) ?: "게스트 여행자",
                                body = body,
                            ),
                        )
                        onCommentsOpenChanged(false)
                },
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 16.dp, end = 24.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(post.localImageRes),
                contentDescription = "${post.authorName} 프로필",
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = post.authorName,
                    color = StogInk,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                socialPostDate(post.item.createdAt)?.let { date ->
                    Text(
                        text = date,
                        color = StogMuted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = post.locationLabel,
                    color = StogMuted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(
                onClick = { onPostChanged(post.copy(savedByViewer = !post.savedByViewer)) },
                modifier = Modifier.stogTouchTarget(),
            ) {
                Icon(
                    painter = painterResource(
                        if (post.savedByViewer) {
                            R.drawable.ic_social_bookmark_filled
                        } else {
                            R.drawable.ic_social_bookmark_outline
                        },
                    ),
                    modifier = Modifier.size(24.dp),
                    contentDescription = if (post.savedByViewer) "저장 취소" else "피드 저장",
                    tint = if (post.savedByViewer) StogYellow else StogInk,
                )
            }
        }
        Image(
            painter = painterResource(post.localImageRes),
            contentDescription = "${post.authorName} 여행 사진",
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
            contentScale = ContentScale.Crop,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            IconButton(
                onClick = { onPostChanged(post.toggleLike()) },
                modifier = Modifier.stogTouchTarget(),
            ) {
                Icon(
                    painter = painterResource(
                        if (post.item.likedByViewer) {
                            R.drawable.ic_social_favorite_filled
                        } else {
                            R.drawable.ic_social_favorite_outline
                        },
                    ),
                    modifier = Modifier.size(24.dp),
                    contentDescription = if (post.item.likedByViewer) "좋아요 취소" else "좋아요",
                    tint = if (post.item.likedByViewer) StogYellow else StogInk,
                )
            }
            Text(
                text = post.item.likeCount.toString(),
                color = StogInk,
                style = MaterialTheme.typography.bodySmall,
            )
            IconButton(
                onClick = { onCommentsOpenChanged(true) },
                modifier = Modifier.stogTouchTarget(),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_social_comment),
                    modifier = Modifier.size(24.dp),
                    contentDescription = "댓글 보기",
                    tint = StogInk,
                )
            }
            Text(
                text = post.commentCount.toString(),
                color = StogInk,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(post.authorName)
                }
                post.item.caption?.takeIf(String::isNotBlank)?.let {
                    append("  ")
                    append(it)
                }
            },
            color = StogInk,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        )
            Text(
                text = "#여행기록  #여행일상  #STOG",
            color = StogMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 16.dp),
        )
            HorizontalDivider(color = StogBorder)
        }
    }
}

@Composable
private fun DiscoverCommentsPanel(
    post: DiscoverFeedPost,
    viewerNickname: String?,
    onDismiss: () -> Unit,
    onCommentAdded: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember(post.item.photoId) { mutableStateOf("") }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag("discover_comments_dialog"),
        shape = RoundedCornerShape(16.dp),
        color = StogSurface,
        border = BorderStroke(1.dp, StogBorder),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "댓글 ${post.commentCount}",
                    color = StogInk,
                    style = MaterialTheme.typography.titleSmall,
                )
                TextButton(onClick = onDismiss) {
                    Text("닫기")
                }
            }
            post.comments.takeLast(8).forEach { comment ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(32.dp),
                        shape = CircleShape,
                        color = StogYellow.copy(alpha = 0.3f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = comment.authorName.firstOrNull()?.toString() ?: "여",
                                color = StogInk,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = comment.authorName,
                            color = StogInk,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            text = comment.body,
                            color = StogMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Text(
                text = "FE 미리보기 · 이 기기에만 저장됩니다.",
                color = StogMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = StogYellow.copy(alpha = 0.3f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = viewerNickname?.firstOrNull()?.toString() ?: "나",
                            color = StogInk,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StogBorder,
                    ),
                )
                IconButton(
                    onClick = { onCommentAdded(draft.trim()) },
                    enabled = draft.isNotBlank(),
                    modifier = Modifier.stogTouchTarget(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_social_send),
                        contentDescription = "댓글 보내기",
                        tint = if (draft.isNotBlank()) StogInk else StogBorder,
                    )
                }
            }
        }
    }
}

private val SOCIAL_POST_DATE_FORMAT =
    DateTimeFormatter.ofPattern("yyyy년 M월 d일", Locale.KOREAN)

private fun socialPostDate(createdAt: String?): String? =
    createdAt?.let { value ->
        runCatching {
            Instant.parse(value)
                .atZone(ZoneId.systemDefault())
                .format(SOCIAL_POST_DATE_FORMAT)
        }.getOrNull()
    }

private fun socialFeedAuthorName(item: SocialFeedItem): String =
    item.ownerNickname?.trim()?.takeIf(String::isNotBlank)
        ?: "여행자"

@Composable
private fun SocialAuthorAvatar(item: SocialFeedItem) {
    val imageUrl = item.ownerProfileImageUrl?.trim()?.takeIf(String::isNotBlank)
    var state by remember(imageUrl) { mutableStateOf<SocialThumbnailState?>(null) }
    LaunchedEffect(imageUrl) {
        state = imageUrl?.let { url -> loadSocialThumbnail(url) }
    }

    Surface(
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        color = StogYellow.copy(alpha = 0.16f),
    ) {
        when (val current = state) {
            is SocialThumbnailState.Available -> Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = "${socialFeedAuthorName(item)} 프로필",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            else -> Image(
                painter = painterResource(R.drawable.stog_launcher_foreground),
                contentDescription = "${socialFeedAuthorName(item)} 프로필",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                colorFilter = ColorFilter.tint(StogYellow),
            )
        }
    }
}

@Composable
private fun SocialFeedCard(
    item: SocialFeedItem,
    likePending: Boolean,
    savePending: Boolean,
    onLike: () -> Unit,
    onSave: () -> Unit,
    onComments: () -> Unit,
    onOpenMap: () -> Unit,
) {
    var mapActionVisible by remember(item.photoId) { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(0.dp),
        color = StogSurface,
    ) {
        Column(
            modifier = Modifier,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SocialAuthorAvatar(item)
                Column(
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        socialFeedAuthorName(item),
                        color = StogInk,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    socialPostDate(item.createdAt)?.let { date ->
                        Text(
                            date,
                            color = StogMuted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f),
            ) {
                SocialThumbnail(
                    photoId = item.photoId,
                    objectKey = item.thumbnailKey,
                    media = item.thumbnailMedia,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable { mapActionVisible = true },
                    )
                if (mapActionVisible) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(56.dp)
                            .clickable(onClick = onOpenMap),
                        color = StogMuted.copy(alpha = 0.9f),
                        shape = CircleShape,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_cell),
                            contentDescription = "지도에서 장소 보기",
                            tint = StogCanvas,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onLike,
                        enabled = !likePending,
                        modifier = Modifier.stogTouchTarget(),
                    ) {
                        Icon(
                            painter = painterResource(
                                if (item.likedByViewer) {
                                    R.drawable.ic_social_favorite_filled
                                } else {
                                    R.drawable.ic_social_favorite_outline
                                },
                            ),
                            contentDescription = if (item.likedByViewer) "좋아요 취소" else "좋아요",
                            tint = StogInk,
                        )
                    }
                    Text(
                        text = item.likeCount.toString(),
                        color = StogMuted,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                    IconButton(
                        onClick = onComments,
                        modifier = Modifier.stogTouchTarget(),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_social_comment),
                            contentDescription = "댓글 ${item.commentCount}개",
                            tint = StogInk,
                        )
                    }
                    Text(
                        text = item.commentCount.toString(),
                        color = StogMuted,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
                IconButton(
                    onClick = onSave,
                    enabled = !savePending,
                    modifier = Modifier.stogTouchTarget(),
                ) {
                    Icon(
                        painter = painterResource(
                            if (item.savedByViewer) {
                                R.drawable.ic_social_bookmark_filled
                            } else {
                                R.drawable.ic_social_bookmark_outline
                            },
                        ),
                        contentDescription = if (item.savedByViewer) "저장 취소" else "피드 저장",
                        tint = StogInk,
                    )
                }
            }
            item.caption?.takeIf(String::isNotBlank)?.let { caption ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(socialFeedAuthorName(item))
                        }
                        append("  ")
                        append(caption)
                    },
                    color = StogInk,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
                )
            }
        }
    }
}

internal sealed interface SocialThumbnailState {
    data object Loading : SocialThumbnailState
    data class Available(val bitmap: Bitmap) : SocialThumbnailState
    data object Unavailable : SocialThumbnailState
    data object Failed : SocialThumbnailState
}

@Composable
internal fun SocialThumbnail(
    photoId: Long,
    objectKey: String,
    media: SocialThumbnailMedia,
    modifier: Modifier,
    loader: suspend (String) -> SocialThumbnailState = ::loadSocialThumbnail,
) {
    var state by remember(objectKey, media) {
        mutableStateOf(initialSocialThumbnailState(media))
    }
    LaunchedEffect(objectKey, media) {
        if (media.status == SocialThumbnailMediaStatus.AVAILABLE) {
            state = loader(checkNotNull(media.signedUrl))
        }
    }
    SocialThumbnailContent(
        photoId = photoId,
        state = state,
        modifier = modifier,
    )
}

@Composable
internal fun SocialThumbnailContent(
    photoId: Long,
    state: SocialThumbnailState,
    modifier: Modifier = Modifier,
) {
    val machineState = when (state) {
        SocialThumbnailState.Loading -> SOCIAL_THUMBNAIL_LOADING
        is SocialThumbnailState.Available -> SOCIAL_THUMBNAIL_AVAILABLE
        SocialThumbnailState.Unavailable -> SOCIAL_THUMBNAIL_UNAVAILABLE
        SocialThumbnailState.Failed -> SOCIAL_THUMBNAIL_FAILED
    }
    Box(
        modifier = modifier
            .testTag(socialThumbnailTestTag(photoId))
            .background(StogBorder)
            .semantics {
                stateDescription = machineState
                if (state is SocialThumbnailState.Available) role = Role.Image
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            SocialThumbnailState.Loading -> CircularProgressIndicator(color = StogYellow)
            is SocialThumbnailState.Available -> Image(
                bitmap = state.bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            SocialThumbnailState.Unavailable,
            SocialThumbnailState.Failed,
            -> StogMediaPlaceholder(
                kind = StogMediaPlaceholderKind.TOURISM,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

internal fun initialSocialThumbnailState(media: SocialThumbnailMedia): SocialThumbnailState =
    when (media.status) {
        SocialThumbnailMediaStatus.AVAILABLE -> SocialThumbnailState.Loading
        SocialThumbnailMediaStatus.UNAVAILABLE -> SocialThumbnailState.Unavailable
        SocialThumbnailMediaStatus.FAILED -> SocialThumbnailState.Failed
    }

internal suspend fun loadSocialThumbnail(signedUrl: String): SocialThumbnailState =
    loadSocialThumbnail(signedUrl) { url -> url.openConnection() as HttpURLConnection }

internal suspend fun loadSocialThumbnail(
    signedUrl: String,
    openConnection: (URL) -> HttpURLConnection,
): SocialThumbnailState = withContext(Dispatchers.IO) {
        val connection = runCatching {
            openConnection(URL(signedUrl))
        }.getOrElse { return@withContext SocialThumbnailState.Failed }
        try {
            connection.connectTimeout = SOCIAL_NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = SOCIAL_NETWORK_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            if (connection.responseCode !in 200..299) {
                return@withContext SocialThumbnailState.Failed
            }
            val bitmap = connection.inputStream.use(BitmapFactory::decodeStream)
                ?: return@withContext SocialThumbnailState.Failed
            SocialThumbnailState.Available(bitmap)
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            SocialThumbnailState.Failed
        } finally {
            connection.disconnect()
        }
    }

internal fun socialThumbnailTestTag(photoId: Long): String = "social_thumbnail_$photoId"

internal const val SOCIAL_THUMBNAIL_LOADING = "social_media_loading"
internal const val SOCIAL_THUMBNAIL_AVAILABLE = "social_media_available"
internal const val SOCIAL_THUMBNAIL_UNAVAILABLE = "social_media_unavailable"
internal const val SOCIAL_THUMBNAIL_FAILED = "social_media_failed"
