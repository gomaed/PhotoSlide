package com.example.digitalcatalogue.data

import android.content.Context
import android.content.SharedPreferences

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
        const val KEY_SHOW_SPACING = "show_spacing"
        const val KEY_DOUBLE_TAP_ADVANCE = "double_tap_advance"
        const val KEY_STAGGER_RATIO = "stagger_ratio"
    }

    var selectedFolderUris: Set<String>
        get() = prefs.getStringSet(KEY_FOLDER_URIS, emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_FOLDER_URIS, value).apply() }

    var sortOrder: String
        get() = prefs.getString(KEY_SORT_ORDER, SORT_NAME_ASC) ?: SORT_NAME_ASC
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

    var showSpacing: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SPACING, true)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_SPACING, value).apply() }

    var doubleTapAdvance: Boolean
        get() = prefs.getBoolean(KEY_DOUBLE_TAP_ADVANCE, false)
        set(value) { prefs.edit().putBoolean(KEY_DOUBLE_TAP_ADVANCE, value).apply() }

    // 50 = no stagger (50/50), 70 = max stagger (70/30)
    var staggerRatio: Int
        get() = prefs.getInt(KEY_STAGGER_RATIO, 50)
        set(value) { prefs.edit().putInt(KEY_STAGGER_RATIO, value).apply() }

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
