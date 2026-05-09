package io.legado.app.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.Status
import io.legado.app.data.entities.Book
import io.legado.app.model.ReadAloud
import io.legado.app.model.ReadBook
import io.legado.app.service.BaseReadAloudService
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.cover.buildCoverImageRequest
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.utils.eventBus.FlowEventBus
import io.legado.app.utils.startActivityForBook
import org.koin.compose.koinInject

@Composable
fun ReadAloudMiniPlayer(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var status by remember {
        mutableIntStateOf(
            when {
                !BaseReadAloudService.isRun -> Status.STOP
                BaseReadAloudService.pause -> Status.PAUSE
                else -> Status.PLAY
            }
        )
    }
    var book by remember { mutableStateOf(ReadBook.book) }
    var chapterTitle by remember { mutableStateOf(ReadBook.curTextChapter?.title.orEmpty()) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        FlowEventBus.with<Int>(EventBus.ALOUD_STATE).collect { state ->
            status = state
            book = ReadBook.book
            chapterTitle = ReadBook.curTextChapter?.title.orEmpty()
            if (state == Status.STOP) {
                expanded = false
            }
        }
    }
    LaunchedEffect(Unit) {
        FlowEventBus.with<Int>(EventBus.TTS_PROGRESS).collect {
            book = ReadBook.book
            chapterTitle = ReadBook.curTextChapter?.title.orEmpty()
        }
    }

    val currentBook = book
    if (status == Status.STOP || currentBook == null) return

    val isPlaying = status == Status.PLAY
    Surface(
        modifier = modifier
            .animateContentSize(),
        shape = if (expanded) RoundedCornerShape(28.dp) else CircleShape,
        color = LegadoTheme.colorScheme.surfaceContainerHigh,
        contentColor = LegadoTheme.colorScheme.onSurface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        if (expanded) {
            ExpandedReadAloudMiniPlayer(
                book = currentBook,
                chapterTitle = chapterTitle,
                isPlaying = isPlaying,
                onCollapse = { expanded = false },
                onTogglePlay = {
                    if (isPlaying) {
                        ReadAloud.pause(context)
                    } else {
                        ReadAloud.resume(context)
                    }
                },
                onStop = {
                    ReadAloud.stop(context)
                    expanded = false
                },
                onOpenReader = {
                    context.startActivityForBook(currentBook)
                    expanded = false
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clickable { expanded = true }
                    .padding(5.dp),
                contentAlignment = Alignment.Center
            ) {
                RotatingCoverDisc(
                    book = currentBook,
                    isPlaying = isPlaying,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

@Composable
private fun ExpandedReadAloudMiniPlayer(
    book: Book,
    chapterTitle: String,
    isPlaying: Boolean,
    onCollapse: () -> Unit,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit,
    onOpenReader: () -> Unit
) {
    Row(
        modifier = Modifier
            .clickable(onClick = onCollapse)
            .padding(start = 10.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RotatingCoverDisc(
            book = book,
            isPlaying = isPlaying,
            modifier = Modifier.size(52.dp)
        )
        Column(
            modifier = Modifier.width(142.dp)
        ) {
            AppText(
                text = book.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            AppText(
                text = chapterTitle.ifBlank { stringResource(R.string.read_aloud) },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(2.dp))
        IconButton(onClick = onTogglePlay) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.PauseCircleOutline else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.resume)
            )
        }
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = stringResource(R.string.stop)
            )
        }
        IconButton(onClick = onOpenReader) {
            Icon(
                imageVector = Icons.Default.Book,
                contentDescription = "返回听书页"
            )
        }
    }
}

@Composable
private fun RotatingCoverDisc(
    book: Book,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    imageLoader: ImageLoader = koinInject()
) {
    val context = LocalContext.current
    val infiniteTransition = rememberInfiniteTransition(label = "readAloudCoverRotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 9000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "readAloudCoverRotationValue"
    )

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(LegadoTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .graphicsLayer {
                rotationZ = if (isPlaying) rotation else 0f
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Book,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        AnimatedVisibility(
            visible = !book.getDisplayCover().isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            AsyncImage(
                model = buildCoverImageRequest(
                    context = context,
                    data = book.getDisplayCover(),
                    sourceOrigin = book.origin,
                    loadOnlyWifi = false,
                    crossfade = false
                ),
                imageLoader = imageLoader,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
