package com.example.puntero

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color as AColor
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import rikka.shizuku.Shizuku

private val ColBg0 = Color(0xFF0B0C10)
private val ColBg1 = Color(0xFF16122A)
private val ColPanel = Color(0xFF1A1B26)
private val ColLine = Color(0xFF2B2D3E)
private val ColAccent = Color(0xFF8B6CFF)
private val ColAccent2 = Color(0xFF36D6C3)
private val ColHi = Color(0xFFF2F3F8)
private val ColLo = Color(0xFF9A9DB2)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT)
        )
        PointerState.load(this)
        Shizuku.addBinderReceivedListenerSticky { PointerState.refreshShizuku() }
        Shizuku.addBinderDeadListener { PointerState.refreshShizuku() }
        Shizuku.addRequestPermissionResultListener { _, _ -> PointerState.refreshShizuku() }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = ColAccent, background = ColBg0, surface = ColPanel)) {
                Screen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        PointerState.refreshShizuku()
    }
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val cfg by PointerState.config.collectAsState()
    val touch by PointerState.touch.collectAsState()
    val on by PointerState.running.collectAsState()
    val enabled by PointerState.enabled.collectAsState()
    val shz by PointerState.shizuku.collectAsState()
    val status by PointerState.status.collectAsState()
    val locked by PointerState.locked.collectAsState()
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val open = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
    val change: (Config) -> Unit = { PointerState.update(ctx, it) }
    val reset = { PointerState.reset(ctx) }
    val toggle: (Boolean) -> Unit = { PointerState.setEnabled(ctx, it) }
    val lockToggle: (Boolean) -> Unit = { PointerState.setLocked(ctx, it) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ColBg1, ColBg0)))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Header(on, open)
            if (land) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Preview(cfg, touch, true)
                        if (!on) ActivateButton(open)
                        ShizukuCard(shz, status)
                        PowerCard(enabled, toggle)
                        LockCard(locked, lockToggle)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        HowTo()
                        Limits(cfg, change, reset)
                    }
                }
            } else {
                Preview(cfg, touch, false)
                if (!on) ActivateButton(open)
                ShizukuCard(shz, status)
                PowerCard(enabled, toggle)
                LockCard(locked, lockToggle)
                HowTo()
                Limits(cfg, change, reset)
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun Header(on: Boolean, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Límite de deslizamiento", color = ColHi, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Tu dedo no pasa de la línea", color = ColLo, fontSize = 14.sp)
        }
        StatusPill(on, onOpen)
    }
}

@Composable
fun StatusPill(on: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "pulse")
    val a by t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    val col = if (on) Color(0xFF3DDC84) else Color(0xFFFF6B6B)
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.clip(shape).background(ColPanel).border(1.dp, ColLine, shape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(col.copy(alpha = if (on) a else 1f)))
        Spacer(Modifier.width(8.dp))
        Text(if (on) "Activo" else "Activar", color = ColHi, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun GlassCard(content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(ColPanel.copy(alpha = 0.85f))
            .border(1.dp, ColLine, shape).padding(18.dp),
        content = content
    )
}

@Composable
fun ActivateButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ColAccent)
    ) { Text("Activar en Accesibilidad", fontWeight = FontWeight.SemiBold) }
}

@Composable
fun PowerCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Límite activo", color = ColHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (enabled) "Frenando tus deslizamientos" else "En pausa: tus toques pasan normal",
                    color = ColLo, fontSize = 13.sp
                )
            }
            Switch(
                checked = enabled, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = ColAccent)
            )
        }
    }
}

@Composable
fun Preview(cfg: Config, touch: Pair<Float, Float>?, land: Boolean) {
    val f = if (land) cfg.land else cfg.portrait
    val tx by animateFloatAsState(touch?.first ?: 0.5f, spring(stiffness = Spring.StiffnessMedium), label = "tx")
    val ty by animateFloatAsState(touch?.second ?: 0.5f, spring(stiffness = Spring.StiffnessMedium), label = "ty")
    val ta by animateFloatAsState(if (touch != null) 1f else 0f, tween(180), label = "ta")
    val ly by animateFloatAsState(if (f >= 0f) f else 0.5f, spring(stiffness = Spring.StiffnessMediumLow), label = "ly")
    val la by animateFloatAsState(if (f >= 0f) 1f else 0f, tween(250), label = "la")
    val pulse = rememberInfiniteTransition(label = "p")
    val glow by pulse.animateFloat(0.15f, 0.4f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "g")
    val shape = RoundedCornerShape(30.dp)
    val m = if (land) Modifier.fillMaxWidth().aspectRatio(2.1f) else Modifier.height(400.dp).aspectRatio(0.49f)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            m.clip(shape)
                .background(Brush.verticalGradient(listOf(Color(0xFF1F2033), Color(0xFF12131D))))
                .border(2.dp, ColLine, shape)
        ) {
            val w = size.width
            val h = size.height
            val grid = Color.White.copy(alpha = 0.04f)
            for (i in 1..7) drawLine(grid, Offset(w * i / 8f, 0f), Offset(w * i / 8f, h))
            for (i in 1..15) drawLine(grid, Offset(0f, h * i / 16f), Offset(w, h * i / 16f))

            if (la > 0.01f) { // línea de límite de extremo a extremo
                val y = ly * h
                drawLine(ColAccent2.copy(alpha = glow * la), Offset(0f, y), Offset(w, y), strokeWidth = 14f)
                drawLine(ColAccent2.copy(alpha = la), Offset(0f, y), Offset(w, y), strokeWidth = 3f)
            }
            if (ta > 0.01f) { // tu dedo en vivo
                val p = Offset(tx * w, ty * h)
                drawCircle(ColAccent.copy(alpha = 0.25f * ta), 30f, p)
                drawCircle(Color.White.copy(alpha = ta), 9f, p)
                drawCircle(ColAccent.copy(alpha = ta), 9f, p, style = Stroke(3f))
            }
        }
        if (f < 0f) {
            Text(
                "Sin límite\nDoble toque en cualquier app para fijar uno",
                color = ColLo, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp)
            )
        }
    }
}

@Composable
fun HowTo() {
    GlassCard {
        Text("Cómo se usa", color = ColHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Step("1", "Usa el teléfono normal: tus deslizamientos pasan directo a las apps, sin retraso. Cuando tu dedo llega a la línea, el toque se corta ahí.")
        Step("2", "Doble toque en cualquier lugar de cualquier app fija un nuevo límite a esa altura. Aparece un puntito donde tocaste.")
        Step("3", "Toca fuera del puntito y desaparece, dejando la pantalla libre. El límite sigue activo.")
        Step("4", "Los toques simples no tienen límite. Un doble toque también lo recibe la app que tengas abierta.")
    }
}

@Composable
fun Step(n: String, t: String) {
    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(24.dp).clip(CircleShape).background(ColAccent.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) { Text(n, color = ColAccent, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(12.dp))
        Text(t, color = ColLo, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
fun Limits(cfg: Config, onChange: (Config) -> Unit, onReset: () -> Unit) {
    GlassCard {
        Text("Límite", color = ColHi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Línea horizontal de lado a lado. Una por orientación del teléfono.", color = ColLo, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        LimitRow("Pantalla vertical", cfg.portrait) { onChange(cfg.copy(portrait = it)) }
        LimitRow("Pantalla horizontal", cfg.land) { onChange(cfg.copy(land = it)) }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, ColLine)
        ) { Text("Quitar límites", color = ColHi) }
    }
}

@Composable
fun LimitRow(label: String, v: Float, onChange: (Float) -> Unit) {
    val none = v < 0f
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = ColHi, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Text(
                if (none) "Sin límite" else "${(v * 100).roundToInt()}% de la altura",
                color = if (none) ColLo else ColAccent2, fontSize = 14.sp, fontWeight = FontWeight.Medium
            )
        }
        Slider(
            value = if (none) 0.5f else v, onValueChange = onChange, valueRange = 0.02f..0.98f,
            colors = SliderDefaults.colors(
                thumbColor = if (none) ColLo else ColAccent,
                activeTrackColor = if (none) ColLine else ColAccent,
                inactiveTrackColor = ColLine
            )
        )
    }
}

@Composable
fun ShizukuCard(state: Int, status: String) {
    GlassCard {
        Text("Motor (Shizuku)", color = ColHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(
            when (state) {
                0 -> "Shizuku no está activo. Ábrelo y toca Iniciar."
                1 -> "Shizuku está activo, falta darle permiso a esta app."
                else -> "Shizuku listo."
            },
            color = ColLo, fontSize = 13.sp
        )
        if (state == 1) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { Shizuku.requestPermission(100) },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ColAccent)
            ) { Text("Dar permiso a Shizuku", fontWeight = FontWeight.SemiBold) }
        }
        if (status.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Diagnóstico: $status", color = ColLo, fontSize = 12.sp)
        }
    }
}

@Composable
fun LockCard(locked: Boolean, onToggle: (Boolean) -> Unit) {
    GlassCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Bloquear doble toque", color = ColHi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (locked) "Bloqueado: el doble toque no cambia el límite"
                    else "Libre: el doble toque fija un límite nuevo",
                    color = ColLo, fontSize = 13.sp
                )
            }
            Switch(
                checked = locked, onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(checkedTrackColor = ColAccent)
            )
        }
    }
}
