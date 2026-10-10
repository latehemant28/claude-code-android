package com.example.hinglishpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.hinglishpdf.data.ai.AIProvider
import com.example.hinglishpdf.ui.theme.brand

/** What tapping the green button agrees to. */
fun uploadConsentText(provider: AIProvider) = Phrase(
    "मैं इस फ़ाइल के शब्द ${provider.displayName} को भेजने के लिए सहमत हूँ",
    "I agree to send this file's text to ${provider.displayName}",
)

/**
 * Asked every time a book is chosen, in pictures, words and voice: a page
 * going to the AI cloud, a short explanation that is read aloud as the
 * dialog opens (🔊 repeats it), and two big answers — a green ✅ "Yes, send"
 * and a red ❌ "No". The file picker opens only after the green one.
 */
@Composable
fun UploadConsentDialog(provider: AIProvider, onAgree: () -> Unit, onDismiss: () -> Unit) {
    val guide = LocalGuide.current
    val spoken = Say.consentHelp(provider.displayName)
    LaunchedEffect(provider) { guide.say(spoken) }
    val close = {
        guide.stop()
        onDismiss()
    }

    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(28.dp), modifier = Modifier.padding(16.dp)) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The picture: your page → the AI's cloud.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PictureCircle {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.padding(horizontal = 12.dp).size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PictureCircle {
                        Icon(Icons.Filled.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
                        Text("AI", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black, fontSize = 13.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Say.consentTitle.text(),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    SpeakButton({ guide.sayNow(spoken) })
                }
                Text(provider.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Point(
                        Phrase(
                            "किताब के सिर्फ़ शब्द भेजे जाते हैं, पेज दर पेज। तस्वीरें फ़ोन में ही रहती हैं।",
                            "Only the text is sent, page by page. Pictures stay on your phone.",
                        ).text(),
                    )
                    Point(
                        Phrase(
                            "${provider.displayName} की अपनी प्राइवेसी शर्तें लागू होंगी।" +
                                if (provider == AIProvider.GEMINI) " गूगल के मुफ़्त प्लान में गूगल इसे अपने प्रोडक्ट सुधारने में इस्तेमाल कर सकता है।" else "",
                            "${provider.displayName}'s own privacy terms apply to it." +
                                if (provider == AIProvider.GEMINI) " On Google's free tier, it may be used to improve Google's products." else "",
                        ).text(),
                    )
                    Point(
                        Phrase(
                            "इस ऐप का अपना कोई सर्वर नहीं है: हम आपकी फ़ाइल न देखते हैं, न रखते हैं।",
                            "This app has no server of its own: we never see or keep your file.",
                        ).text(),
                    )
                    Point(Phrase("कोई निजी या गुप्त कागज़ न चुनें।", "Don't choose private or secret papers.").text())
                }
                Spacer(Modifier.size(4.dp))
                Button(
                    onClick = {
                        guide.stop()
                        onAgree()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669), contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(Say.yesSend.text(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    uploadConsentText(provider).text(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = close,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(Say.no.text(), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PictureCircle(content: @Composable () -> Unit) {
    Box(
        Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.brand.accent),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun Point(text: String) {
    Row {
        Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
