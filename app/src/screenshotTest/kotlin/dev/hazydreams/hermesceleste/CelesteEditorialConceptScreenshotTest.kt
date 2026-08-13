package dev.hazydreams.hermesceleste

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.tools.screenshot.PreviewTest

private val EditorialPaper = Color(0xFFF3F0E7)
private val EditorialInk = Color(0xFF132031)
private val EditorialMuted = Color(0xFF68717D)
private val EditorialCobalt = Color(0xFF3756C8)
private val EditorialCoral = Color(0xFFE76F51)

/**
 * Recovered executable source for Celeste's selected early visual direction.
 *
 * This is intentionally a concept screen rather than production UI. It preserves the original
 * composition as an iteration anchor while the product shell evolves around real app states.
 */
@PreviewTest
@Preview(name = "Celeste · Editorial concept", widthDp = 390, heightDp = 844, showBackground = true)
@Composable
fun CelesteEditorialConceptScreenshot() {
    Box(Modifier.fillMaxSize().background(EditorialPaper)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.91f, size.height * 0.05f)
            drawCircle(EditorialCobalt.copy(alpha = 0.10f), 230f, center, style = Stroke(2f))
            drawCircle(EditorialCobalt.copy(alpha = 0.08f), 310f, center, style = Stroke(1f))
            drawArc(
                EditorialCoral,
                startAngle = 128f,
                sweepAngle = 48f,
                useCenter = false,
                topLeft = Offset(center.x - 230f, center.y - 230f),
                size = Size(460f, 460f),
                style = Stroke(5f, cap = StrokeCap.Round),
            )
            drawCircle(
                EditorialCobalt,
                6f,
                Offset(size.width * 0.83f, size.height * 0.13f),
            )
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 44.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "HERMES CELESTE",
                    color = EditorialInk,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                )
                Text(
                    "REMOTE / PRIVATE",
                    color = EditorialCobalt,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.height(78.dp))
            Text(
                text = "Your Hermes,\ncarried forward.",
                color = EditorialInk,
                fontFamily = FontFamily.Serif,
                fontSize = 47.sp,
                lineHeight = 49.sp,
                letterSpacing = (-1.4).sp,
            )
            Text(
                text = "Open the same conversations from wherever your day takes you—without copying them anywhere else.",
                color = EditorialMuted,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(top = 22.dp, end = 20.dp),
            )

            Spacer(Modifier.weight(1f))
            Text(
                "DASHBOARD ADDRESS",
                color = EditorialCobalt,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
            Text(
                "hermes.example.net",
                color = EditorialInk,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 13.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(EditorialInk.copy(alpha = 0.34f)),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                EditorialNote("DIRECT LINK", "No relay")
                EditorialNote("ONE HISTORY", "Shared sessions")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 28.dp)
                    .background(EditorialInk, RoundedCornerShape(50.dp))
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Find my Hermes",
                    color = EditorialPaper,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Box(
                    modifier = Modifier.size(30.dp).background(EditorialCoral, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("→", color = EditorialPaper, fontSize = 18.sp)
                }
            }
            Text(
                "A private window into the Hermes you already run.",
                color = EditorialMuted,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun EditorialNote(label: String, detail: String) {
    Column {
        Text(
            label,
            color = EditorialInk,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Text(
            detail,
            color = EditorialMuted,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
