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
        const val KEY_DOUBLE_TAP_ACTION  = "double_tap_action"
        const val DOUBLE_TAP_SWAP   = "swap"
        const val DOUBLE_TAP_OPEN   = "open"
        const val DOUBLE_TAP_NONE   = "none"
        const val KEY_STAGGER_RATIO = "stagger_ratio"
        const val KEY_GRID_COLOR = "grid_color"
        const val KEY_GRID_CORNER_RADIUS = "grid_corner_radius"
        const val KEY_IS_SCANNING = "is_scanning"
        const val KEY_IS_FACE_SCANNING = "is_face_scanning"
        const val KEY_FACE_SCAN_PROGRESS = "face_scan_progress"
        const val KEY_FADE_DURATION = "fade_duration"
        const val KEY_CENTER_FACES  = "center_faces"
        const val KEY_FACES_ONLY    = "faces_only"
        const val KEY_RESCAN             = "rescan"
        // Incremented by ImageScanner.startScan() when a fragment-triggered URI scan
        // finishes; the wallpaper engine listens to retry ensureImages() without racing.
        const val KEY_URI_CACHE_UPDATED  = "uri_cache_updated"
        // Incremented by FaceScanner when an external scan finishes; the wallpaper
        // engine listens for changes to reload facesOnlyImages from disk.
        const val KEY_FACE_CACHE_UPDATED = "face_cache_updated"
        // Newline-delimited URI lists — the exact photos shown in each cell at last save.
        // Restored on startup so the wallpaper resumes the same selection after a reboot.
        const val KEY_PORTRAIT_CELL_URIS  = "portrait_cell_uris"
        const val KEY_LANDSCAPE_CELL_URIS = "landscape_cell_uris"
        // Wall-clock ms of the last auto-advance; used to compute remaining delay on resume.
        const val KEY_LAST_ADVANCE_TIME = "last_advance_time"
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

    var doubleTapAction: String
        get() = prefs.getString(KEY_DOUBLE_TAP_ACTION, DOUBLE_TAP_SWAP) ?: DOUBLE_TAP_SWAP
        set(value) { prefs.edit().putString(KEY_DOUBLE_TAP_ACTION, value).apply() }

    var isScanning: Boolean
        get() = prefs.getBoolean(KEY_IS_SCANNING, false)
        set(value) { prefs.edit().putBoolean(KEY_IS_SCANNING, value).apply() }

    var isFaceScanning: Boolean
        get() = prefs.getBoolean(KEY_IS_FACE_SCANNING, false)
        set(value) { prefs.edit().putBoolean(KEY_IS_FACE_SCANNING, value).apply() }

    var faceScanProgress: Int
        get() = prefs.getInt(KEY_FACE_SCAN_PROGRESS, 0)
        set(value) { prefs.edit().putInt(KEY_FACE_SCAN_PROGRESS, value).apply() }

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

    var centerFacesEnabled: Boolean
        get() = prefs.getBoolean(KEY_CENTER_FACES, false)
        set(value) { prefs.edit().putBoolean(KEY_CENTER_FACES, value).apply() }

    var facesOnlyEnabled: Boolean
        get() = prefs.getBoolean(KEY_FACES_ONLY, false)
        set(value) { prefs.edit().putBoolean(KEY_FACES_ONLY, value).apply() }

    // Pulsed true by the Rescan FAB; service resets to false after handling
    var rescan: Boolean
        get() = prefs.getBoolean(KEY_RESCAN, false)
        set(value) { prefs.edit().putBoolean(KEY_RESCAN, value).apply() }

    var uriCacheUpdated: Int
        get() = prefs.getInt(KEY_URI_CACHE_UPDATED, 0)
        set(value) { prefs.edit().putInt(KEY_URI_CACHE_UPDATED, value).apply() }

    var faceCacheUpdated: Int
        get() = prefs.getInt(KEY_FACE_CACHE_UPDATED, 0)
        set(value) { prefs.edit().putInt(KEY_FACE_CACHE_UPDATED, value).apply() }

    var portraitCellUris: String
        get() = prefs.getString(KEY_PORTRAIT_CELL_URIS, "") ?: ""
        set(value) { prefs.edit().putString(KEY_PORTRAIT_CELL_URIS, value).apply() }

    var landscapeCellUris: String
        get() = prefs.getString(KEY_LANDSCAPE_CELL_URIS, "") ?: ""
        set(value) { prefs.edit().putString(KEY_LANDSCAPE_CELL_URIS, value).apply() }

    var lastAdvanceTime: Long
        get() = prefs.getLong(KEY_LAST_ADVANCE_TIME, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_ADVANCE_TIME, value).apply() }

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
