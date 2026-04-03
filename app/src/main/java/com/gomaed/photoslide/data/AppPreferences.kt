package com.gomaed.photoslide.data

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    companion object {
        const val PREF_NAME = "wallpaper_prefs"

        const val SORT_NAME_ASC = "name_asc"
        const val SORT_DATE_DESC = "date_desc"
        const val SORT_RANDOM = "random"

        const val INTERVAL_NEVER = -1

        const val KEY_FOLDER_URIS = "folder_uris"
        const val KEY_SORT_ORDER = "sort_order"
        const val KEY_SLIDE_INTERVAL = "slide_interval"
        const val KEY_PORTRAIT_COLS = "portrait_cols"
        const val KEY_PORTRAIT_ROWS = "portrait_rows"
        const val KEY_LANDSCAPE_COLS = "landscape_cols"
        const val KEY_LANDSCAPE_ROWS = "landscape_rows"
        const val KEY_GRID_SPACING = "grid_spacing"
        const val KEY_DOUBLE_TAP_ADVANCE = "double_tap_advance"
        const val KEY_STAGGER_RATIO = "stagger_ratio"
        const val KEY_GRID_COLOR = "grid_color"
        const val KEY_GRID_CORNER_RADIUS = "grid_corner_radius"
        const val KEY_IS_SCANNING = "is_scanning"
        const val KEY_FADE_DURATION = "fade_duration"
    }

    var selectedFolderUris: Set<String>
        get() = prefs.getStringSet(KEY_FOLDER_URIS, emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_FOLDER_URIS, value).apply() }

    var sortOrder: String
        get() = prefs.getString(KEY_SORT_ORDER, SORT_RANDOM) ?: SORT_RANDOM
        set(value) { prefs.edit().putString(KEY_SORT_ORDER, value).apply() }

    var slideInterval: Int
        get() = prefs.getInt(KEY_SLIDE_INTERVAL, 30)
        set(value) { prefs.edit().putInt(KEY_SLIDE_INTERVAL, value).apply() }

    var portraitCols: Int
        get() = prefs.getInt(KEY_PORTRAIT_COLS, 2)
        set(value) { prefs.edit().putInt(KEY_PORTRAIT_COLS, value).apply() }

    var portraitRows: Int
        get() = prefs.getInt(KEY_PORTRAIT_ROWS, 2)
        set(value) { prefs.edit().putInt(KEY_PORTRAIT_ROWS, value).apply() }

    var landscapeCols: Int
        get() = prefs.getInt(KEY_LANDSCAPE_COLS, 3)
        set(value) { prefs.edit().putInt(KEY_LANDSCAPE_COLS, value).apply() }

    var landscapeRows: Int
        get() = prefs.getInt(KEY_LANDSCAPE_ROWS, 1)
        set(value) { prefs.edit().putInt(KEY_LANDSCAPE_ROWS, value).apply() }

    var gridSpacing: Int
        get() = prefs.getInt(KEY_GRID_SPACING, 3)
        set(value) { prefs.edit().putInt(KEY_GRID_SPACING, value).apply() }

    var doubleTapAdvance: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_ADVANCE, true)
        set(value) { prefs.edit().putBoolean(KEY_DOUBLE_TAP_ADVANCE, value).apply() }

    var isScanning: Boolean
        get() = prefs.getBoolean(KEY_IS_SCANNING, false)
        set(value) { prefs.edit().putBoolean(KEY_IS_SCANNING, value).apply() }

    var gridCornerRadius: Int
        get() = prefs.getInt(KEY_GRID_CORNER_RADIUS, 8)
        set(value) { prefs.edit().putInt(KEY_GRID_CORNER_RADIUS, value).apply() }

    var gridColor: Int
        get() = prefs.getInt(KEY_GRID_COLOR, Color.BLACK)
        set(value) { prefs.edit().putInt(KEY_GRID_COLOR, value).apply() }

    // 0 = no fade, 100–1000 = fade duration in ms
    var fadeDuration: Int
        get() = prefs.getInt(KEY_FADE_DURATION, 800)
        set(value) { prefs.edit().putInt(KEY_FADE_DURATION, value).apply() }

    // 50 = no stagger (50/50), 70 = max stagger (70/30)
    var staggerRatio: Int
        get() = prefs.getInt(KEY_STAGGER_RATIO, 55)
        set(value) { prefs.edit().putInt(KEY_STAGGER_RATIO, value).apply() }

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
