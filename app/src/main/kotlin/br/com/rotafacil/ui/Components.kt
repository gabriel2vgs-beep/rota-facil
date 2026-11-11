package br.com.rotafacil.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.rotafacil.core.PackageStatus
import br.com.rotafacil.core.StopStatus

val Green = Color(0xFF16A34A)
val Amber = Color(0xFFF59E0B)
val Red = Color(0xFFDC2626)
val Gray = Color(0xFF8E8E93)

@Composable
fun AppCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
    if (onClick != null) Card(onClick, modifier, shape = RoundedCornerShape(20.dp), colors = colors, border = border) { Column(Modifier.padding(16.dp), content = content) }
    else Card(modifier, shape = RoundedCornerShape(20.dp), colors = colors, border = border) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun Chip(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

fun stopStatusLabel(s: StopStatus) = when (s) { StopStatus.PENDING -> "Pendente"; StopStatus.IN_PROGRESS -> "Em andamento"; StopStatus.COMPLETED -> "Concluída" }
fun stopStatusColor(s: StopStatus) = when (s) { StopStatus.PENDING -> Gray; StopStatus.IN_PROGRESS -> Amber; StopStatus.COMPLETED -> Green }
fun pkgStatusLabel(s: PackageStatus) = when (s) { PackageStatus.PENDING -> "Pendente"; PackageStatus.DELIVERED -> "Entregue"; PackageStatus.NOT_DELIVERED -> "Não entregue" }
fun pkgStatusColor(s: PackageStatus) = when (s) { PackageStatus.PENDING -> Gray; PackageStatus.DELIVERED -> Green; PackageStatus.NOT_DELIVERED -> Red }

@Composable
fun StatTile(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    AppCard(modifier) {
        Text(value.toString(), fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f))
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, icon: @Composable (() -> Unit)? = null) {
    val shape = RoundedCornerShape(16.dp)
    val m = modifier.heightIn(min = 56.dp)   // alvo grande: uso com o carro parado / uma mão
    if (primary) Button(onClick, m, enabled = enabled, shape = shape) { icon?.invoke(); if (icon != null) Spacer(Modifier.width(8.dp)); Text(text, fontWeight = FontWeight.Bold) }
    else OutlinedButton(onClick, m, enabled = enabled, shape = shape) { icon?.invoke(); if (icon != null) Spacer(Modifier.width(8.dp)); Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun ProgressBar(percent: Int, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(percent / 100f, label = "progress")
    LinearProgressIndicator(progress = { p }, modifier = modifier.height(10.dp), color = Green, trackColor = MaterialTheme.colorScheme.surfaceVariant, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
}

@Composable
fun Title(text: String, sub: String? = null) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}
