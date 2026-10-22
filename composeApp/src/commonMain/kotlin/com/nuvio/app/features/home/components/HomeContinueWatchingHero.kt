package com.nuvio.app.features.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.LocalDynamicArtworkBackgroundColor
import com.nuvio.app.features.watchprogress.ContinueWatchingItem

/**
 * Hero for [com.nuvio.app.features.home.HomeHeroStyle.CONTINUE_WATCHING]: the title the user was last
 * watching, with a progress bar and a Resume button. It is sized with [netflixHeroLayout], so it occupies
 * exactly the same space as the Netflix hero (which Home falls back to when there is nothing to resume).
 */
@Composable
internal fun HomeContinueWatchingHero(
    item: ContinueWatchingItem,
    modifier: Modifier = Modifier,
    viewportHeight: Dp? = null,
    mobileBelowSectionHeightHint: Dp? = null,
    onResumeClick: ((ContinueWatchingItem) -> Unit)? = null,
    onActiveArtworkChange: ((String?) -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val layout = netflixHeroLayout(
            maxWidthDp = maxWidth.value,
            viewportHeightDp = viewportHeight?.value,
            mobileBelowSectionHeightHintDp = mobileBelowSectionHeightHint?.value,
        )
        // Backdrops only: posters carry the title, which would double up with the logo.
        val artworkUrl = listOf(
            item.background,
            item.rawBackgroundUrl,
            item.imageUrl,
            item.episodeThumbnail,
            item.poster,
        ).firstOrNull { !it.isNullOrBlank() }

        LaunchedEffect(onActiveArtworkChange, artworkUrl) {
            onActiveArtworkChange?.invoke(artworkUrl)
        }

        val fadeColorState = LocalDynamicArtworkBackgroundColor.current
        val themeBackground = MaterialTheme.colorScheme.background
        val isResuming = item.resumePositionMs > 0L && !item.isNextUp
        val progress = item.progressFraction.coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(layout.heroHeight),
        ) {
            if (artworkUrl != null) {
                AsyncImage(
                    model = artworkUrl,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    alignment = if (layout.isTablet) Alignment.TopCenter else Alignment.Center,
                )
            }

            // Legibility scrim: left-to-right when the text sits on the start side, otherwise bottom-heavy.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        if (layout.sideContent) {
                            drawRect(
                                Brush.horizontalGradient(
                                    0f to Color.Black.copy(alpha = 0.84f),
                                    0.45f to Color.Black.copy(alpha = 0.55f),
                                    0.8f to Color.Black.copy(alpha = 0.10f),
                                    1f to Color.Transparent,
                                ),
                            )
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Transparent,
                                    0.55f to Color.Transparent,
                                    1f to Color.Black.copy(alpha = 0.55f),
                                ),
                            )
                        } else {
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.18f),
                                    0.4f to Color.Transparent,
                                    0.72f to Color.Black.copy(alpha = 0.55f),
                                    1f to Color.Black.copy(alpha = 0.88f),
                                ),
                            )
                        }
                    },
            )
            // Blend the bottom edge into the screen background so catalog rows sit flush under the hero.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(layout.bottomFadeHeight)
                    .align(Alignment.BottomCenter)
                    .drawBehind {
                        val fade = fadeColorState?.value ?: themeBackground
                        drawRect(Brush.verticalGradient(colors = listOf(fade.copy(alpha = 0f), fade)))
                    },
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .then(
                        if (layout.isLandscape) {
                            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        } else {
                            Modifier
                        },
                    )
                    .padding(
                        horizontal = layout.contentHorizontalPadding,
                        vertical = layout.contentVerticalPadding,
                    ),
                horizontalAlignment = Alignment.Start,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth(layout.contentWidthFraction)
                        .widthIn(max = layout.contentMaxWidth),
                    horizontalAlignment = Alignment.Start,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        text = if (isResuming) "CONTINUE WATCHING" else "UP NEXT",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.78f),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp,
                        maxLines = 1,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    val logoUrl = item.logo?.takeIf { it.isNotBlank() }
                    var logoFailed by remember(logoUrl) { mutableStateOf(false) }
                    if (logoUrl != null && !logoFailed) {
                        AsyncImage(
                            model = logoUrl,
                            contentDescription = item.title,
                            modifier = Modifier
                                .width(layout.logoWidth)
                                .heightIn(max = layout.logoWidth / 2.6f),
                            contentScale = ContentScale.Fit,
                            alignment = Alignment.CenterStart,
                            onError = { logoFailed = true },
                        )
                    } else {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    val details = listOfNotNull(
                        item.subtitle.takeIf { it.isNotBlank() },
                        remainingTimeLabel(item).takeIf { isResuming },
                    ).joinToString("  \u00B7  ")
                    if (details.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = details,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.9f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (isResuming && progress > 0f) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.28f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progress)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier
                            .then(if (layout.buttonsFillWidth) Modifier.fillMaxWidth() else Modifier)
                            .height(layout.buttonHeight)
                            .clip(RoundedCornerShape(layout.buttonCornerRadius))
                            .background(Color.White)
                            .clickable(enabled = onResumeClick != null, role = Role.Button) {
                                onResumeClick?.invoke(item)
                            }
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PlayArrow,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isResuming) "Resume" else "Play",
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** "23 min left" / "1h 5m left", or an empty string when the duration is unknown. */
private fun remainingTimeLabel(item: ContinueWatchingItem): String {
    if (item.durationMs <= 0L) return ""
    val remainingMinutes = ((item.durationMs - item.resumePositionMs).coerceAtLeast(0L) / 60_000L).toInt()
    return when {
        remainingMinutes <= 0 -> "Almost done"
        remainingMinutes >= 60 -> "${remainingMinutes / 60}h ${remainingMinutes % 60}m left"
        else -> "$remainingMinutes min left"
    }
}
