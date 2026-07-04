package dev.warsha.remoteble.androidclient.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The app's semantic palette. Components reference these names — not raw hex — so the look
 * lives in one place. Built on the Tailwind "slate" neutrals with indigo/violet accents.
 */
object AppColors {
    val background = Color(0xFF0F172A)      // slate-900
    val surface = Color(0xFF1E293B)         // slate-800
    val surfaceAlt = Color(0xFF334155)      // slate-700 — neutral buttons, borders
    val onSurface = Color(0xFFF8FAFC)       // slate-50
    val onSurfaceMuted = Color(0xFF94A3B8)  // slate-400 — labels, secondary text
    val onSurfaceFaint = Color(0xFF64748B)  // slate-500 — hints, monospace ids

    val accent = Color(0xFF6366F1)          // indigo-500
    val accentSecondary = Color(0xFF8B5CF6) // violet-500
    val positive = Color(0xFF10B981)        // emerald-500 — battery, values
    val warning = Color(0xFFF59E0B)         // amber-500
    val danger = Color(0xFFF43F5E)          // rose-500 — disconnect
    val heart = Color(0xFFEF4444)           // red-500 — heart rate

    val readChip = Color(0xFF2563EB)
    val writeChip = Color(0xFF7C3AED)
    val notifyChip = Color(0xFFD97706)
}

private val DarkColors = darkColorScheme(
    primary = AppColors.accent,
    secondary = AppColors.accentSecondary,
    background = AppColors.background,
    surface = AppColors.surface,
    onBackground = AppColors.onSurface,
    onSurface = AppColors.onSurface,
    onSurfaceVariant = AppColors.onSurfaceMuted,
    outline = AppColors.surfaceAlt,
    error = AppColors.danger,
)

@Composable
fun RemoteBleTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkColors, content = content)
}
