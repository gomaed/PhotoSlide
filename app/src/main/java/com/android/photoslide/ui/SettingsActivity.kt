package com.android.photoslide.ui

import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.android.photoslide.R
import com.android.photoslide.data.AppPreferences
import com.android.photoslide.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefs: AppPreferences

    private val scanningListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppPreferences.KEY_IS_SCANNING) updateScanSpinner()
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
        binding.scanIndicator.visibility = if (prefs.isScanning) View.VISIBLE else View.GONE
    }
}
