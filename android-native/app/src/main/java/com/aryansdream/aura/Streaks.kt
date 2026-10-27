package com.aryansdream.aura

import java.time.LocalDate
import java.time.ZonedDateTime

/** Aura days roll over at 2 AM in the device's time zone. */
fun auraDay(now: ZonedDateTime = ZonedDateTime.now()): LocalDate =
    if (now.hour < 2) now.toLocalDate().minusDays(1) else now.toLocalDate()

/** Keep yesterday's active streak visible until the user earns points today. */
fun calculateStreak(earnedDays: Set<String>, currentDay: LocalDate = auraDay()): Int {
    var expected = currentDay
    if (expected.toString() !in earnedDays) expected = expected.minusDays(1)
    var streak = 0
    while (expected.toString() in earnedDays) {
        streak++
        expected = expected.minusDays(1)
    }
    return streak
}
