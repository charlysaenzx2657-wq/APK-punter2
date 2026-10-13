package com.example.puntero

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val Bg = Color(0xFF0E0F14)
private val Card = Color(0xFF181A22)
private val Muted = Color(0xFFB8BCCB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PointerState.load(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7C5CFF), background = Bg, surface = Card)) {
                Screen()
            }
        }
    }
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val cfg by PointerState.config.collectAsState()
    val pos by PointerState.pos.collectAsState()
    val on by PointerState.running.collectAsState()

    Column(
        Modifier.fillMaxSize().background(Bg).statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Puntero", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)

        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(if (on) Color(0xFF3DDC84) else Color(0xFFFF5C5C)))
            Spacer(Modifier.width(12.dp))
            Text(if (on) "Servicio activo" else "Servicio apagado", color = Color.White, modifier = Modifier.weight(1f))
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                Text(if (on) "Ajustes" else "Activar")
            }
        }

        Preview(cfg, pos)

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp)
        ) {
            Text("Coordenadas (en % de la pantalla)", color = Color.White, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Param("Parada X", cfg.stopX, cfg.left..cfg.right) { PointerState.update(ctx, cfg.copy(stopX = it)) }
            Param("Parada Y", cfg.stopY, cfg.top..cfg.bottom) { PointerState.update(ctx, cfg.copy(stopY = it)) }
            Param("Límite izquierdo", cfg.left, 0f..(cfg.right - 0.1f)) { PointerState.update(ctx, cfg.copy(left = it)) }
            Param("Límite derecho", cfg.right, (cfg.left + 0.1f)..1f) { PointerState.update(ctx, cfg.copy(right = it)) }
            Param("Límite superior", cfg.top, 0f..(cfg.bottom - 0.1f)) { PointerState.update(ctx, cfg.copy(top = it)) }
            Param("Límite inferior", cfg.bottom, (cfg.top + 0.1f)..1f) { PointerState.update(ctx, cfg.copy(bottom = it)) }
            OutlinedButton(onClick = { PointerState.reset(ctx) }, modifier = Modifier.fillMaxWidth()) {
                Text("Restablecer valores por defecto")
            }
        }
    }
}

@Composable
fun Param(label: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label: ${(v * 100).roundToInt()}%", color = Muted, fontSize = 13.sp)
        Slider(value = v.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}

@Composable
fun Preview(cfg: Config, pos: Pair<Float, Float>) {
    val px by animateFloatAsState(pos.first, spring(stiffness = Spring.StiffnessMedium), label = "x")
    val py by animateFloatAsState(pos.second, spring(stiffness = Spring.StiffnessMedium), label = "y")
    val accent = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.height(380.dp).aspectRatio(0.5f)
                .clip(RoundedCornerShape(28.dp)).background(Card)
                .border(2.dp, Color(0xFF2A2D3A), RoundedCornerShape(28.dp))
        ) {
            val w = size.width; val h = size.height
            drawRect(Color.White.copy(alpha = 0.05f), Offset(0f, h * 0.65f), Size(w, h * 0.35f)) // touchpad
            val tl = Offset(cfg.left * w, cfg.top * h)
            val sz = Size((cfg.right - cfg.left) * w, (cfg.bottom - cfg.top) * h)
            drawRect(accent.copy(alpha = 0.08f), tl, sz)
            drawRect(accent.copy(alpha = 0.6f), tl, sz, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            drawCircle(Color.White.copy(alpha = 0.5f), 14f, Offset(cfg.stopX * w, cfg.stopY * h), style = Stroke(3f))
            val p = Offset(px * w, py * h)
            drawCircle(accent.copy(alpha = 0.25f), 30f, p)
            drawCircle(accent, 12f, p)
        }
    }
}
