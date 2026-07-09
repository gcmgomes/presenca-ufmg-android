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
}
