package com.gomaed.photoslide.ui

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.gomaed.photoslide.R
import com.gomaed.photoslide.data.AppPreferences
import com.gomaed.photoslide.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences

    private val scanningListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppPreferences.KEY_IS_SCANNING ||
            key == AppPreferences.KEY_IS_FACE_SCANNING ||
            key == AppPreferences.KEY_FACE_SCAN_PROGRESS)
            updateScanSpinner()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPreferences(this)

        val isLightMode = (resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_NO
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = isLightMode
            isAppearanceLightNavigationBars = isLightMode
        }

        setSupportActionBar(binding.toolbar)

        // Colour the progress spinner to match the toolbar's colour scheme
        val tv = TypedValue()
        theme.resolveAttribute(com.google.android.material.R.attr.colorOnPrimaryContainer, tv, true)
        val onPrimary = tv.data
        binding.scanSpinner.setIndicatorColor(onPrimary)
        binding.scanSpinner.trackColor = ColorUtils.setAlphaComponent(onPrimary, 50)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController

        binding.bottomNavigation.setupWithNavController(navController)

        goToFoldersIfRequested(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        goToFoldersIfRequested(intent)
    }

    private fun goToFoldersIfRequested(intent: android.content.Intent) {
        if (intent.getBooleanExtra("go_to_folders", false)) {
            binding.bottomNavigation.selectedItemId = R.id.folderSelectFragment
        }
    }

    override fun onResume() {
        super.onResume()
        prefs.registerChangeListener(scanningListener)
        updateScanSpinner()
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterChangeListener(scanningListener)
    }

    private fun updateScanSpinner() {
        when {
            prefs.isFaceScanning -> {
                val progress = prefs.faceScanProgress
                if (binding.scanSpinner.isIndeterminate) {
                    binding.scanSpinner.isIndeterminate = false
                }
                binding.scanSpinner.max = 100
                binding.scanSpinner.setProgressCompat(progress, true)
                binding.scanLabel.setText(R.string.scanning_faces)
                binding.scanIndicator.visibility = View.VISIBLE
            }
            prefs.isScanning -> {
                if (!binding.scanSpinner.isIndeterminate) {
                    binding.scanSpinner.isIndeterminate = true
                }
                binding.scanLabel.setText(R.string.scanning_folders)
                binding.scanIndicator.visibility = View.VISIBLE
            }
            else -> binding.scanIndicator.visibility = View.GONE
        }
    }
}
