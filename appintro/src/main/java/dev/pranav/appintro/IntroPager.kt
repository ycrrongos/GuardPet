package dev.pranav.appintro

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlin.math.absoluteValue

/**
 * An enhanced version of the AppIntro pager with advanced page transitions
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun IntroPager(
    pages: List<IntroPage>,
    pagerState: PagerState,
    modifier: Modifier = Modifier
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize(),
        pageSpacing = 0.dp,
        userScrollEnabled = false, // Disable user scrolling for controlled transitions
        pageContent = { page ->
            val pageOffset =
                ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue

            IntroPageContent(
                page = pages[page],
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = 1f - (0.1f * pageOffset.coerceIn(0f, 1f))
                        scaleX = scale
                        scaleY = scale

                        alpha = 1f - (0.5f * pageOffset.coerceIn(0f, 1f))

                        rotationY = 8f * pageOffset.coerceIn(-1f, 1f)

                        cameraDistance = 8f * density
                    },
                isVisible = page == pagerState.currentPage
            )
        }
    )
}
