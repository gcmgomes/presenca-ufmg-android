package com.example.presensor.tools

import android.content.Context
import com.example.presensor.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object TimeUtils {

    fun tryParseDate(text: String, formatter: DateTimeFormatter): LocalDate? {
        return try {
            LocalDate.parse(text, formatter)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    fun isDateInCurrentWeek(targetDate: LocalDate): Boolean {
        val today = LocalDate.now()
        val startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        return !targetDate.isBefore(startOfWeek) && !targetDate.isAfter(endOfWeek)
    }

    fun fromMillisToLocalDate(utcMillis: Long): LocalDate {
        return Instant.ofEpochMilli(utcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    }

    fun fromMillisToLocalDateTime(millis: Long): LocalDateTime {
        return LocalDateTime.ofInstant(
            Instant.ofEpochMilli(millis),
            ZoneId.systemDefault()
        )
    }

    fun makeSessionTimeFormatter(context: Context): DateTimeFormatter {
        val pattern = context.getString(R.string.session_date_display_format)
        return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    }

    fun formatMinutesToTime(minutes: Long?): String {
        if (minutes == null) return "---"
        val hours = minutes / 60
        val mins = minutes % 60
        return String.format(Locale.getDefault(), "%02d:%02d", hours, mins)
    }

    fun parseTimeToMinutes(timeStr: String): Long? {
        val parts = timeStr.split(":")
        if (parts.size != 2) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutesStr = parts[1]
        if (minutesStr.length != 2) return null
        val mins = minutesStr.toLongOrNull() ?: return null
        if (hours !in 0..23 || mins !in 0..59) return null
        return hours * 60 + mins
    }
}
