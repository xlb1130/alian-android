package com.alian.assistant.presentation.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alian.assistant.R
import com.alian.assistant.presentation.ui.theme.BaoziTheme
import com.alian.assistant.presentation.ui.theme.Primary
import kotlinx.coroutines.launch

data class OnboardingPage(
    val icon: ImageVector,
    @androidx.annotation.StringRes val titleRes: Int,
    @androidx.annotation.StringRes val descriptionRes: Int,
    val iconColor: Color = Primary
)

@Composable
fun rememberOnboardingPages(): List<OnboardingPage> {
    return remember {
        listOf(
            OnboardingPage(
                icon = Icons.Outlined.Star,
                titleRes = R.string.onboarding_welcome,
                descriptionRes = R.string.onboarding_welcome_desc,
                iconColor = Color(0xFF6366F1) // Indigo
            ),
            OnboardingPage(
                icon = Icons.Outlined.Settings,
                titleRes = R.string.onboarding_ai,
                descriptionRes = R.string.onboarding_ai_desc,
                iconColor = Color(0xFF8B5CF6) // Violet
            ),
            OnboardingPage(
                icon = Icons.Outlined.Home,
                titleRes = R.string.onboarding_easy,
                descriptionRes = R.string.onboarding_easy_desc,
                iconColor = Color(0xFF06B6D4) // Cyan
            ),
            OnboardingPage(
                icon = Icons.Filled.Lock,
                titleRes = R.string.onboarding_safe,
                descriptionRes = R.string.onboarding_safe_desc,
                iconColor = Color(0xFF10B981) // Emerald
            )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val colors = BaoziTheme.colors
    val onboardingPages = rememberOnboardingPages()
    val pagerState = rememberPagerState(pageCount = { onboardingPages.size })
    val coroutineScope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 页面内容
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { page ->
                OnboardingPageContent(
                    page = onboardingPages[page],
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 指示器
            Row(
                modifier = Modifier.padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(onboardingPages.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .size(if (isSelected) 24.dp else 8.dp, 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) Primary else colors.textHint.copy(alpha = 0.3f)
                            )
                            .animateContentSize()
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 底部按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // 跳过按钮
                TextButton(
                    onClick = onComplete,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.onboarding_skip),
                        color = colors.textSecondary,
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                // 下一步/开始按钮
                Button(
                    onClick = {
                        if (pagerState.currentPage < onboardingPages.size - 1) {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        } else {
                            onComplete()
                        }
                    },
                    modifier = Modifier
                        .weight(2f)
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary
                    )
                ) {
                    Text(
                        text = stringResource(
                            if (pagerState.currentPage < onboardingPages.size - 1) 
                                R.string.onboarding_next 
                            else 
                                R.string.onboarding_start
                        ),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun OnboardingPageContent(
    page: OnboardingPage,
    modifier: Modifier = Modifier
) {
    val colors = BaoziTheme.colors

    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 动画图标
        val infiniteTransition = rememberInfiniteTransition(label = "icon")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scale"
        )

        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            page.iconColor.copy(alpha = 0.15f),
                            page.iconColor.copy(alpha = 0.05f),
                            colors.background.copy(alpha = 0f)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = page.icon,
                contentDescription = stringResource(page.titleRes),
                modifier = Modifier.size((72 * scale).dp),
                tint = page.iconColor
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        // 标题
        Text(
            text = stringResource(page.titleRes),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colors.textPrimary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 描述
        Text(
            text = stringResource(page.descriptionRes),
            fontSize = 16.sp,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            lineHeight = 26.sp
        )
    }
}
