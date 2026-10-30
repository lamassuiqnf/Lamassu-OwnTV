package tv.own.owntv.lamassu

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import tv.own.owntv.ui.theme.StageColors
import tv.own.owntv.ui.theme.mpx
import tv.own.owntv.ui.theme.stageText

@Composable
fun LamassuHomeBanners(
    contentStart: Dp,
    bannerIntervalSeconds: Int = 5,
    modifier: Modifier = Modifier,
) {
    val repo: HomeContentRepository = koinInject()
    val entitlement: EntitlementCoordinator = koinInject()
    val slides by repo.slides.collectAsStateWithLifecycle()
    val sections by repo.sections.collectAsStateWithLifecycle()
    val mode by entitlement.snapshot.collectAsStateWithLifecycle()
    val interval = entitlement.remoteConfig.collectAsStateWithLifecycle().value.bannerInterval
        .coerceAtLeast(bannerIntervalSeconds)
        .coerceIn(2, 60)

    LaunchedEffect(Unit) { repo.refresh() }

    val visibleSections = HomeContentRepository.filterSectionsForMode(
        sections,
        mode?.mode.orEmpty(),
    )
    if (slides.isEmpty() && visibleSections.isEmpty()) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = contentStart, end = 48.mpx),
        verticalArrangement = Arrangement.spacedBy(16.mpx),
    ) {
        if (slides.isNotEmpty()) {
            BannerPager(slides = slides, autoSlideSeconds = interval)
        }
        visibleSections.take(6).forEach { section ->
            SectionCard(section)
        }
    }
}

@Composable
private fun BannerPager(slides: List<HomeSlide>, autoSlideSeconds: Int) {
    val pagerState = rememberPagerState(pageCount = { slides.size })
    LaunchedEffect(slides, autoSlideSeconds) {
        if (slides.size <= 1) return@LaunchedEffect
        while (true) {
            delay(autoSlideSeconds * 1_000L)
            val next = (pagerState.currentPage + 1) % slides.size
            pagerState.animateScrollToPage(next)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(21f / 9f)
            .clip(RoundedCornerShape(18.mpx))
            .focusable(),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(0.dp),
        ) { page ->
            val slide = slides[page]
            Box(Modifier.fillMaxSize()) {
                AsyncImage(
                    model = slide.imageUrl,
                    contentDescription = slide.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, LamassuBrand.Navy.copy(alpha = 0.85f)),
                            ),
                        ),
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(20.mpx),
                ) {
                    if (!slide.title.isNullOrBlank()) {
                        Text(
                            slide.title,
                            style = stageText(26, 700),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (!slide.subtitle.isNullOrBlank()) {
                        Spacer(Modifier.height(4.mpx))
                        Text(
                            slide.subtitle,
                            style = stageText(16, 400),
                            color = Color.White.copy(alpha = 0.85f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (slides.size > 1) {
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.mpx),
                horizontalArrangement = Arrangement.spacedBy(6.mpx),
            ) {
                repeat(slides.size) { index ->
                    Box(
                        Modifier
                            .size(if (index == pagerState.currentPage) 10.mpx else 8.mpx)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) LamassuBrand.Orange
                                else Color.White.copy(alpha = 0.45f),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionCard(section: HomeSection) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.mpx))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(12.mpx),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.mpx),
    ) {
        if (!section.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = section.imageUrl,
                contentDescription = section.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(96.mpx)
                    .height(64.mpx)
                    .clip(RoundedCornerShape(10.mpx)),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                section.title,
                style = stageText(20, 700),
                color = StageColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!section.subtitle.isNullOrBlank()) {
                Text(
                    section.subtitle,
                    style = stageText(14, 400),
                    color = StageColors.Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
