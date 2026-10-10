package com.example.hinglishpdf.ui.onboarding

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.annotation.RawRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.example.hinglishpdf.R
import kotlinx.coroutines.launch

/** The onboarding text, exactly as specified. */
object OnboardingText {
    const val TITLE_1 = "Your Rules, Your Language"
    const val DESCRIPTION_1 = "Translate entire books and PDFs without any monthly subscriptions or hidden fees. " +
        "Premium translations, 100% private."

    const val TITLE_2 = "Choose Your AI Engine"
    const val DESCRIPTION_2 = "Our app is like a luxury car, but YOU choose the engine! Translation quality depends " +
        "on the AI Provider you select. Connect your own API key to pay only the actual AI cost with zero app markup."

    const val TITLE_3 = "Recommended AI Providers"
    const val DESCRIPTION_3 = "1. Google Gemini: Best for large books (generous free tier!).\n" +
        "2. OpenAI: Top-tier conversational flow.\n" +
        "3. Anthropic Claude: Best for deep literature and context."
    const val BOTTOM_3 = "Don't worry, you can switch your AI provider anytime from the settings."

    const val START = "Let's Get Started!"
}

// The icon's palette: deep indigo night, violet, lavender, pink and cyan highlights.
private val Night = Color(0xFF0B0520)
private val Indigo = Color(0xFF1C0B4B)
private val Violet = Color(0xFF3A1873)
private val Lavender = Color(0xFFC9B8FF)
private val Pink = Color(0xFFF2A7D8)
private val Cyan = Color(0xFF7FD4FF)
private val Gold = Color(0xFFFFD27A)

private const val PAGES = 3

/**
 * The 3-step first-launch tutorial (a HorizontalPager). Shown once, on the
 * very first launch; "Let's Get Started!" (or Skip) calls [onFinish], which
 * opens the main translation screen.
 */
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pager = rememberPagerState { PAGES }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES - 1

    // Light status and navigation bar icons on the dark gradient; restored afterwards.
    val activity = LocalContext.current as? ComponentActivity
    DisposableEffect(activity) {
        activity?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        onDispose { activity?.enableEdgeToEdge() }
    }
    BackHandler(enabled = pager.currentPage > 0) {
        scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Indigo, Violet, Night))),
    ) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (!last) {
                    // Large, bold and easy to hit (at least 48 dp tall).
                    TextButton(
                        onClick = onFinish,
                        modifier = Modifier.heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Text("Skip", color = Color.White.copy(alpha = 0.92f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> Slide(OnboardingText.TITLE_1, OnboardingText.DESCRIPTION_1, visualSize = 280) {
                        LoopingAnimation(R.raw.onboarding_book, "A book turning into many languages")
                    }
                    1 -> Slide(OnboardingText.TITLE_2, OnboardingText.DESCRIPTION_2, visualSize = 280) {
                        LoopingAnimation(R.raw.onboarding_chip, "A glowing AI chip connected to four AI engines")
                    }
                    else -> RecommendationsSlide()
                }
            }
            PagerDots(pager, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp))
            Button(
                onClick = {
                    if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    // The same solid white button on every slide, "Let's Get Started!" included.
                    containerColor = Color.White,
                    contentColor = Indigo,
                ),
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 20.dp)
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(
                    if (last) OnboardingText.START else "Next",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** A visual, a title and a description, centred and scrollable on small screens. */
@Composable
private fun Slide(
    title: String,
    description: String,
    visualSize: Int,
    extra: @Composable ColumnScope.() -> Unit = {},
    visual: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(visualSize.dp), contentAlignment = Alignment.Center) { visual() }
            Spacer(Modifier.height(20.dp))
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                description,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.82f),
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
            )
            extra()
        }
    }
}

@Composable
private fun RecommendationsSlide() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LoopingAnimation(R.raw.onboarding_badge, "Recommended", Modifier.size(160.dp))
            Spacer(Modifier.height(16.dp))
            Text(
                OnboardingText.TITLE_3,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            // The description, one card per line: "1. Google Gemini: Best for ...".
            val accents = listOf(Cyan, Color(0xFF7BE0A8), Color(0xFFFFB27A))
            OnboardingText.DESCRIPTION_3.lines().forEachIndexed { i, line ->
                val name = line.substringBefore(':', missingDelimiterValue = "")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(accents[i % accents.size]))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        buildAnnotatedString {
                            if (name.isNotEmpty()) {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append("$name:") }
                                append(line.substringAfter(':'))
                            } else {
                                append(line)
                            }
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                OnboardingText.BOTTOM_3,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.65f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PagerDots(pager: PagerState, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(PAGES) { i ->
            val selected = pager.currentPage == i
            val width by animateDpAsState(if (selected) 26.dp else 8.dp, label = "dot")
            val color by animateColorAsState(if (selected) Color.White else Color.White.copy(alpha = 0.35f), label = "dot")
            Box(Modifier.height(8.dp).width(width).clip(CircleShape).background(color))
        }
    }
}

// ------------------------------------------------------------------ visuals

/**
 * A looping Lottie animation from res/raw (generated by tools/onboarding_lottie.py):
 * a book turning into many languages, a glowing AI chip, a recommended badge.
 */
@Composable
private fun LoopingAnimation(@RawRes res: Int, description: String, modifier: Modifier = Modifier.fillMaxSize()) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(res))
    val progress by animateLottieCompositionAsState(composition, iterations = LottieConstants.IterateForever)
    LottieAnimation(
        composition = composition,
        progress = { progress },
        modifier = modifier.semantics { contentDescription = description },
    )
}
