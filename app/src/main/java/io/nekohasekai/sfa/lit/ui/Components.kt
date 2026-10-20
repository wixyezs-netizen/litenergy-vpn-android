package io.nekohasekai.sfa.lit.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.constant.Status
import kotlin.math.floor
import kotlin.math.sin

// Палитра: чёрный фон, тёмные карточки, белые акценты
internal val Bg = Color(0xFF000000)
internal val Surface = Color(0xFF161616)
internal val Raised = Color(0xFF0E0E0E)
internal val Pill = Color(0xFF222222)
internal val Border = Color(0x14FFFFFF)
internal val BorderStrong = Color(0x2EFFFFFF)
internal val TextMain = Color(0xFFFFFFFF)
internal val Muted = Color(0xFF8E8E93)
internal val Dim = Color(0xFF636366)
internal val Red = Color(0xFFEF4444)

internal fun open(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Падающие белые точки в верхней четверти экрана. */
@Composable
internal fun FallingDots(count: Int = 46, zone: Float = 0.25f) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> time = (now - start) / 1_000_000_000f }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val h = size.height * zone
        val d = density
        for (i in 0 until count) {
            val seed = i.toDouble()
            val x = fract(sin(seed * 12.9898) * 43758.5453).toFloat() * size.width
            val speed = (14 + fract(sin(seed * 78.233) * 12345.678) * 30).toFloat() * d
            val phase = fract(sin(seed * 3.17) * 9876.54).toFloat()
            val r = (0.7f + fract(sin(seed * 5.71) * 4567.89).toFloat() * 1.3f) * d
            val progress = fract((time * speed / h + phase).toDouble()).toFloat()
            val alpha = (1f - progress) * (0.35f + 0.55f * fract(sin(seed * 9.1) * 777.7).toFloat())
            drawCircle(Color.White.copy(alpha = alpha), r, Offset(x, progress * h))
        }
    }
}

private fun fract(v: Double): Double = v - floor(v)

/** Плитка с логотипом и название. */
@Composable
internal fun BrandHeader(compact: Boolean = false) {
    val tile = if (compact) 64.dp else 76.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(
            Modifier.size(tile).clip(RoundedCornerShape(22.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.lit_logo), contentDescription = null, modifier = Modifier.size(tile * 0.58f))
        }
        Spacer(Modifier.height(if (compact) 12.dp else 18.dp))
        Text("LitEnergy VPN", color = TextMain, fontSize = if (compact) 26.sp else 30.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
internal fun RoundIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).background(Surface).border(1.dp, Border, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = TextMain, modifier = Modifier.size(22.dp)) }
}

/** Тёмная кнопка внутри карточки. */
@Composable
internal fun PillButton(icon: ImageVector?, text: String, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(16.dp)).background(Pill).border(1.dp, Border, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        horizontalArrangement = if (trailing == null) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = TextMain, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = if (trailing != null) Modifier.weight(1f) else Modifier)
        if (trailing != null) {
            Text(trailing, color = Muted, fontSize = 14.sp)
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Dim, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
internal fun PrimaryButton(text: String, busy: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.White, contentColor = Color.Black,
            disabledContainerColor = Pill, disabledContentColor = Dim,
        ),
        modifier = Modifier.fillMaxWidth().height(56.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(color = Color.Black, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        } else {
            Text(text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun ChoiceCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(24.dp))
            .clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(Pill).border(1.dp, Border, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = TextMain, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = Muted, fontSize = 14.sp)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Dim)
    }
}

@Composable
internal fun LinkCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(22.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(Pill).border(1.dp, Border, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = TextMain, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 14.sp)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Dim)
    }
}

@Composable
internal fun SheetItem(title: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(Surface)
            .border(1.dp, Border, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) Red else TextMain, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = Muted, fontSize = 14.sp)
        }
        if (!danger) Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = Dim, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun SheetHeader(title: String) {
    Text(title, color = TextMain, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(14.dp))
}

@Composable
internal fun SectionTitle(text: String) {
    Text(text.uppercase(), color = Dim, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, modifier = Modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp))
}

/** Карточка со строками и разделителями. */
@Composable
internal fun RowGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Surface).border(1.dp, Border, RoundedCornerShape(22.dp)),
        content = content,
    )
}

@Composable
internal fun RowDivider() {
    HorizontalDivider(color = Border, thickness = 1.dp, modifier = Modifier.padding(start = 16.dp))
}

@Composable
internal fun SelectRow(title: String, subtitle: String? = null, trailing: String? = null, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (selected) Color.White else Color.Transparent)
                .border(1.5.dp, if (selected) Color.White else Dim, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        if (!trailing.isNullOrEmpty()) Text(trailing, color = Muted, fontSize = 14.sp)
    }
}

@Composable
internal fun ToggleRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = TextMain, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black, checkedTrackColor = Color.White, checkedBorderColor = Color.White,
                uncheckedThumbColor = Muted, uncheckedTrackColor = Pill, uncheckedBorderColor = BorderStrong,
            ),
        )
    }
}

@Composable
internal fun StepHeader(title: String, subtitle: String) {
    Spacer(Modifier.height(8.dp))
    Text(title, color = TextMain, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, color = Muted, fontSize = 15.sp)
}

@Composable
internal fun ErrorText(error: String?) {
    if (error != null) {
        Spacer(Modifier.height(12.dp))
        Text(error, color = Red, fontSize = 14.sp)
    }
}

@Composable
internal fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color.White,
    unfocusedBorderColor = BorderStrong,
    focusedContainerColor = Surface,
    unfocusedContainerColor = Surface,
    disabledContainerColor = Surface,
    cursorColor = TextMain,
    focusedTextColor = TextMain,
    unfocusedTextColor = TextMain,
)

/** Большая монохромная кнопка подключения: белая со свечением, когда VPN включён. */
@Composable
internal fun ConnectButton(status: Status, onClick: () -> Unit) {
    val on = status == Status.Started
    val busy = status == Status.Starting || status == Status.Stopping
    val transition = rememberInfiniteTransition(label = "pulse")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "p")
    val glow = when {
        on -> 0.08f + 0.08f * pulse
        busy -> 0.03f + 0.07f * pulse
        else -> 0f
    }
    Box(
        Modifier.size(240.dp).drawBehind {
            val r = size.minDimension / 2
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = glow * 2), Color.White.copy(alpha = glow), Color.Transparent), center, r), r)
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(196.dp).clip(CircleShape).background(if (on) Color.White else Surface)
                .border(1.dp, if (on) Color.White else BorderStrong, CircleShape)
                .clickable(enabled = status != Status.Stopping, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            val fg = if (on) Color.Black else TextMain
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = fg, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(10.dp))
                Text(
                    when (status) {
                        Status.Stopped -> "Подключить"
                        Status.Starting -> "Подключение…"
                        Status.Started -> "Отключить"
                        Status.Stopping -> "Отключение…"
                    },
                    color = fg, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
