package dev.warsha.ble.remoteble.androidclient.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.warsha.ble.remoteble.androidclient.ui.AppColors

/** A rounded dark surface used for every grouped block on both screens. */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    bordered: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface),
        border = if (bordered) BorderStroke(1.dp, AppColors.surfaceAlt) else null,
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** The small uppercase section heading repeated throughout the UI. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppColors.onSurfaceMuted,
) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = modifier,
    )
}

/** Monospace UUID / handle text. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = AppColors.onSurface,
    fontSize: Int = 12,
) {
    Text(text = text, color = color, fontFamily = FontFamily.Monospace, fontSize = fontSize.sp, modifier = modifier)
}

/** Filled accent (primary) button used for the main action in a group. */
@Composable
fun AccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    container: Color = AppColors.accent,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(42.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            disabledContainerColor = AppColors.surfaceAlt,
        ),
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(text = text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

/** Subdued neutral button for secondary actions (stop, read, subscribe). */
@Composable
fun NeutralButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(38.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.surfaceAlt,
            disabledContainerColor = AppColors.surface,
        ),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(text = text, color = AppColors.onSurface, fontSize = 13.sp)
    }
}

/** A capability tag (READ / WRITE / NOTIFY) for a characteristic. */
@Composable
fun PropertyChip(label: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 9.sp)
    }
}

/** Recessed monospace block showing a raw characteristic value. */
@Composable
fun ValueBlock(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AppColors.background)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        MonoText(text = text, color = AppColors.positive, fontSize = 11)
    }
}

/** A pulsing dot used as a live-activity indicator (scanning / connected). */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Box(modifier = modifier.size(10.dp).alpha(alpha).background(color, CircleShape))
}
