package com.example.hinglishpdf.ui.onboarding

import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hinglishpdf.R
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
                    TextButton(onClick = onFinish) { Text("Skip", color = Color.White.copy(alpha = 0.75f)) }
                }
            }
            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                when (page) {
                    0 -> Slide(OnboardingText.TITLE_1, OnboardingText.DESCRIPTION_1, visualSize = 280) { BookIntoLanguages() }
                    1 -> Slide(OnboardingText.TITLE_2, OnboardingText.DESCRIPTION_2, visualSize = 280) { EngineChip() }
                    else -> RecommendationsSlide()
                }
            }
            PagerDots(pager, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp))
            val gradient = Brush.horizontalGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)))
            Button(
                onClick = {
                    if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (last) Color.Transparent else Color.White,
                    contentColor = if (last) Color.White else Indigo,
                ),
                contentPadding = ButtonDefaults.ContentPadding,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 20.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .then(if (last) Modifier.clip(RoundedCornerShape(28.dp)).background(gradient) else Modifier),
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
            PremiumBadge()
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
 * Slide 1: the app's PDF → अ artwork floating and tilting in 3D, with letters
 * from many scripts orbiting around it (behind it, then in front of it).
 */
@Composable
private fun BookIntoLanguages() {
    val transition = rememberInfiniteTransition(label = "orbit")
    val angle by transition.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(14_000, easing = LinearEasing)),
        label = "angle",
    )
    val float by transition.animateFloat(
        -1f, 1f,
        infiniteRepeatable(tween(2_600), RepeatMode.Reverse),
        label = "float",
    )
    val glyphs = listOf("A", "अ", "あ", "Ñ", "Ж", "ع", "中", "한", "ß", "அ")
    val density = LocalDensity.current

    Box(
        Modifier.fillMaxSize().semantics { contentDescription = "A book turning into many languages" },
        contentAlignment = Alignment.Center,
    ) {
        Glow(Lavender, 0.35f)
        // Behind the book: the far half of the orbit.
        OrbitingGlyphs(glyphs, angle, front = false)
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(300.dp)
                .graphicsLayer {
                    rotationY = sin(angle * 2) * 14f
                    rotationX = cos(angle * 2) * 6f
                    translationY = float * with(density) { 8.dp.toPx() }
                    cameraDistance = 14f * density.density
                },
        )
        OrbitingGlyphs(glyphs, angle, front = true)
    }
}

@Composable
private fun OrbitingGlyphs(glyphs: List<String>, angle: Float, front: Boolean) {
    val density = LocalDensity.current
    val radiusX = with(density) { 120.dp.toPx() }
    val radiusY = with(density) { 44.dp.toPx() }
    val colors = listOf(Lavender, Pink, Cyan, Gold)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        glyphs.forEachIndexed { i, glyph ->
            val a = angle + i * (2 * PI / glyphs.size).toFloat()
            val depth = sin(a) // -1 far ... 1 near
            if ((depth >= 0f) != front) return@forEachIndexed
            val scale = 0.72f + 0.38f * (depth + 1f) / 2f
            Box(
                Modifier
                    .offset { IntOffset((cos(a) * radiusX).roundToInt(), (depth * radiusY - 10f).roundToInt()) }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.45f + 0.55f * (depth + 1f) / 2f
                    }
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.06f))))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(glyph, color = colors[i % colors.size], fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * Slide 2: a processor chip ("the engine") wired to four AI engines; a pulse
 * runs to each in turn, lighting up the one "selected".
 */
@Composable
private fun EngineChip() {
    val transition = rememberInfiniteTransition(label = "engine")
    val t by transition.animateFloat(0f, 4f, infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "t")
    val selected = t.toInt().coerceIn(0, 3)
    val progress = t - selected // 0..1 along the selected trace
    val engines = listOf("Gemini", "OpenAI", "Claude", "Groq")
    // Corners: top-left, top-right, bottom-right, bottom-left (dp from the centre).
    val corners = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1)
    val density = LocalDensity.current

    Box(
        Modifier.fillMaxSize().semantics { contentDescription = "A processor chip connected to four AI engines" },
        contentAlignment = Alignment.Center,
    ) {
        Glow(Color(0xFF8B5CF6), 0.4f)
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val chip = 56.dp.toPx() // half size of the chip
            val pin = 14.dp.toPx()
            // Pins on each side.
            for (k in -2..2) {
                val o = k * 18.dp.toPx()
                val pinColor = Lavender.copy(alpha = 0.7f)
                drawLine(pinColor, Offset(c.x + o, c.y - chip), Offset(c.x + o, c.y - chip - pin), 3.dp.toPx(), StrokeCap.Round)
                drawLine(pinColor, Offset(c.x + o, c.y + chip), Offset(c.x + o, c.y + chip + pin), 3.dp.toPx(), StrokeCap.Round)
                drawLine(pinColor, Offset(c.x - chip, c.y + o), Offset(c.x - chip - pin, c.y + o), 3.dp.toPx(), StrokeCap.Round)
                drawLine(pinColor, Offset(c.x + chip, c.y + o), Offset(c.x + chip + pin, c.y + o), 3.dp.toPx(), StrokeCap.Round)
            }
            // Traces to the four engines, and the pulse on the selected one.
            corners.forEachIndexed { i, (sx, sy) ->
                val start = Offset(c.x + sx * (chip + pin), c.y + sy * 36.dp.toPx())
                val bend = Offset(c.x + sx * 98.dp.toPx(), start.y)
                val end = Offset(bend.x, c.y + sy * 92.dp.toPx())
                val active = i == selected
                val color = if (active) Cyan else Color.White.copy(alpha = 0.22f)
                drawLine(color, start, bend, 2.dp.toPx(), StrokeCap.Round)
                drawLine(color, bend, end, 2.dp.toPx(), StrokeCap.Round)
                if (active) {
                    val first = (bend - start).getDistance()
                    val total = first + (end - bend).getDistance()
                    val d = progress * total
                    val p = if (d <= first) start + (bend - start) * (d / first) else bend + (end - bend) * ((d - first) / (total - first))
                    drawCircle(Cyan.copy(alpha = 0.35f), 9.dp.toPx(), p)
                    drawCircle(Color.White, 4.dp.toPx(), p)
                }
            }
            // The chip body, with a glossy top light and an inner die.
            drawRoundRect(
                Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFF2B1B6B)), Offset(c.x - chip, c.y - chip), Offset(c.x + chip, c.y + chip)),
                topLeft = Offset(c.x - chip, c.y - chip),
                size = androidx.compose.ui.geometry.Size(chip * 2, chip * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),
            )
            drawRoundRect(
                Color.White.copy(alpha = 0.35f),
                topLeft = Offset(c.x - chip, c.y - chip),
                size = androidx.compose.ui.geometry.Size(chip * 2, chip * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()),
                style = Stroke(1.5.dp.toPx()),
            )
            val die = 32.dp.toPx()
            drawRoundRect(
                Brush.radialGradient(listOf(Color(0xFF3B2A8F), Color(0xFF160A3D)), c, die * 1.4f),
                topLeft = Offset(c.x - die, c.y - die),
                size = androidx.compose.ui.geometry.Size(die * 2, die * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
            )
        }
        Text("AI", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        engines.forEachIndexed { i, name ->
            val (sx, sy) = corners[i]
            val active = i == selected
            val background by animateColorAsState(if (active) Cyan else Color.White.copy(alpha = 0.1f), label = "engine")
            val text by animateColorAsState(if (active) Indigo else Color.White.copy(alpha = 0.8f), label = "engineText")
            Box(
                Modifier
                    .offset { with(density) { IntOffset((sx * 98.dp.toPx()).roundToInt(), (sy * 112.dp.toPx()).roundToInt()) } }
                    .clip(RoundedCornerShape(12.dp))
                    .background(background)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(name, color = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Slide 3: a glowing, gently pulsing "recommended" badge. */
@Composable
private fun PremiumBadge() {
    val transition = rememberInfiniteTransition(label = "badge")
    val pulse by transition.animateFloat(0.92f, 1.06f, infiniteRepeatable(tween(1_400), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Glow(Gold, 0.35f)
        Box(
            Modifier
                .size(84.dp)
                .graphicsLayer { scaleX = pulse; scaleY = pulse }
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color(0xFFFFE29A), Color(0xFFF59E0B))))
                .border(2.dp, Color.White.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.WorkspacePremium, contentDescription = "Recommended", tint = Indigo, modifier = Modifier.size(46.dp))
        }
    }
}

/** A soft radial light behind a visual. */
@Composable
private fun Glow(color: Color, alpha: Float) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center, size.minDimension / 2),
            radius = size.minDimension / 2,
        )
    }
}
