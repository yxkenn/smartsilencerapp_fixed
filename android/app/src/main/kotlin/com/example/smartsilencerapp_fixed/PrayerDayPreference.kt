package com.example.smartsilencerapp_fixed
data class PrayerDayPreference(
    val dayOfWeek: Int, // Calendar.SUNDAY, Calendar.MONDAY, etc.
    val prayerPreferences: Map<String, PrayerPreference> // Key is prayer name (fajr, dhuhr, etc.)
)

enum class PrayerPreference {
    DEFAULT,    // Use the app's selected mode (gps/notification/auto)
    CERTAIN,    // Always treat as if going to mosque (auto mode logic)
    EXCLUDED    // Skip this prayer entirely
}