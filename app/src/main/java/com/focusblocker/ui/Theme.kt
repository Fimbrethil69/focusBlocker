package com.focusblocker.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun FocusTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

fun formatTime(time: LocalDateTime, now: LocalDateTime): String {
    val hm = "%02d:%02d".format(time.hour, time.minute)
    val days = time.toLocalDate().toEpochDay() - now.toLocalDate().toEpochDay()
    return when (days) {
        0L -> hm
        1L -> "demain $hm"
        else -> "${dayName(time.dayOfWeek)} $hm"
    }
}

fun dayName(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, Locale.FRENCH)

fun dayShort(day: DayOfWeek): String =
    day.getDisplayName(TextStyle.SHORT, Locale.FRENCH).trimEnd('.').replaceFirstChar { it.uppercase() }
