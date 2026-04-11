package com.gomaed.photoslide.ui.settings

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import androidx.fragment.app.Fragment
import com.gomaed.photoslide.R
import com.gomaed.photoslide.data.AppPreferences
import com.gomaed.photoslide.databinding.FragmentGeneralSettingsBinding
import com.gomaed.photoslide.wallpaper.LiveWallpaperService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class GeneralSettingsFragment : Fragment() {

    private var _binding: FragmentGeneralSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: AppPreferences

    private val intervalValues = listOf(5, 10, 30, 60, 300, 900, 1800, 3600, 21600, 43200, 86400, AppPreferences.INTERVAL_NEVER)

    // 0–16 in 2px steps, 20–32 in 4px steps, 40–128 in 8px steps
    private val cornerRadiusValues =
        (0..8).map { it * 2 } +       // 0, 2, 4 … 16
        (1..4).map { 16 + it * 4 } +  // 20, 24, 28, 32
        (1..12).map { 32 + it * 8 }   // 40, 48, 56 … 128

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGeneralSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        setupSlideInterval()
        setupFadeDuration()
        setupSortOrder()
        setupGridAppearance()
        setupDoubleTapAction()
        setupCenterFacesSwitch()
        setupFacesOnlySwitch()
        setupSetWallpaperFab()
    }

    private fun setupSlideInterval() {
        val currentIndex = intervalValues.indexOf(prefs.slideInterval).coerceAtLeast(0)
        binding.slideIntervalSlider.apply {
            valueFrom = 0f
            valueTo = (intervalValues.size - 1).toFloat()
            stepSize = 1f
            value = currentIndex.toFloat()
        }
        binding.slideIntervalLabel.text = intervalLabel(intervalValues[currentIndex])
        binding.slideIntervalSlider.setLabelFormatter { value ->
            intervalLabel(intervalValues[value.toInt()])
        }

        binding.slideIntervalSlider.addOnChangeListener { _, value, _ ->
            val interval = intervalValues[value.toInt()]
            prefs.slideInterval = interval
            binding.slideIntervalLabel.text = intervalLabel(interval)
        }
    }

    private fun intervalLabel(seconds: Int): String = when (seconds) {
        5 -> getString(R.string.interval_5s)
        10 -> getString(R.string.interval_10s)
        30 -> getString(R.string.interval_30s)
        60 -> getString(R.string.interval_1min)
        300 -> getString(R.string.interval_5min)
        900 -> getString(R.string.interval_15min)
        1800 -> getString(R.string.interval_30min)
        3600 -> getString(R.string.interval_1h)
        21600 -> getString(R.string.interval_6h)
        43200 -> getString(R.string.interval_12h)
        86400 -> getString(R.string.interval_24h)
        else -> getString(R.string.interval_never)
    }

    private fun setupFadeDuration() {
        val currentStep = (prefs.fadeDuration / 100).coerceIn(0, 10)
        binding.fadeDurationSlider.apply {
            valueFrom = 0f
            valueTo = 10f
            stepSize = 1f
            value = currentStep.toFloat()
        }
        binding.fadeDurationLabel.text = fadeDurationLabel(currentStep * 100)
        binding.fadeDurationSlider.setLabelFormatter { value ->
            fadeDurationLabel(value.toInt() * 100)
        }
        binding.fadeDurationSlider.addOnChangeListener { _, value, _ ->
            val ms = value.toInt() * 100
            prefs.fadeDuration = ms
            binding.fadeDurationLabel.text = fadeDurationLabel(ms)
        }
    }

    private fun fadeDurationLabel(ms: Int): String =
        if (ms == 0) getString(R.string.fade_off) else "${ms}ms"

    private fun setupSortOrder() {
        val sortLabels = arrayOf(
            getString(R.string.sort_name_asc),
            getString(R.string.sort_date_desc),
            getString(R.string.sort_random)
        )
        val sortValues = listOf(
            AppPreferences.SORT_NAME_ASC,
            AppPreferences.SORT_DATE_DESC,
            AppPreferences.SORT_RANDOM
        )

        fun updateButton() {
            val idx = sortValues.indexOf(prefs.sortOrder).coerceAtLeast(0)
            binding.sortOrderButton.text = sortLabels[idx]
        }
        updateButton()

        binding.sortOrderButton.setOnClickListener {
            val current = sortValues.indexOf(prefs.sortOrder).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.sort_order)
                .setSingleChoiceItems(sortLabels, current) { dialog, which ->
                    prefs.sortOrder = sortValues[which]
                    updateButton()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun setupGridAppearance() {
        binding.gridSpacingSlider.apply {
            valueFrom = 0f
            valueTo = 16f
            stepSize = 1f
            value = prefs.gridSpacing.toFloat().coerceIn(0f, 16f)
        }
        updateSpacingLabel(prefs.gridSpacing)
        binding.gridSpacingSlider.setLabelFormatter { value ->
            if (value == 0f) getString(R.string.grid_width_off) else "${value.toInt()}px"
        }
        binding.gridSpacingSlider.addOnChangeListener { _, value, _ ->
            val spacing = value.toInt()
            prefs.gridSpacing = spacing
            updateSpacingLabel(spacing)
            updateColorRowEnabled(spacing > 0)
        }

        updateColorSwatch()
        updateColorRowEnabled(prefs.gridSpacing > 0)
        binding.gridColorRow.setOnClickListener { showColorPicker() }

        val currentRadiusIndex = cornerRadiusValues.indexOfFirst { it >= prefs.gridCornerRadius }
            .coerceAtLeast(0)
        binding.gridCornerRadiusSlider.apply {
            valueFrom = 0f
            valueTo = (cornerRadiusValues.size - 1).toFloat()
            stepSize = 1f
            value = currentRadiusIndex.toFloat()
        }
        updateCornerRadiusLabel(cornerRadiusValues[currentRadiusIndex])
        binding.gridCornerRadiusSlider.setLabelFormatter { value ->
            cornerRadiusLabel(cornerRadiusValues[value.toInt()])
        }
        binding.gridCornerRadiusSlider.addOnChangeListener { _, value, _ ->
            val radius = cornerRadiusValues[value.toInt()]
            prefs.gridCornerRadius = radius
            updateCornerRadiusLabel(radius)
        }
    }

    private fun cornerRadiusLabel(radius: Int) =
        if (radius == 0) getString(R.string.grid_width_off) else "${radius}px"

    private fun updateCornerRadiusLabel(radius: Int) {
        binding.gridCornerRadiusLabel.text = cornerRadiusLabel(radius)
    }

    private fun updateSpacingLabel(spacing: Int) {
        binding.gridSpacingLabel.text =
            if (spacing == 0) getString(R.string.grid_width_off) else "${spacing}px"
    }

    private fun updateColorRowEnabled(enabled: Boolean) {
        binding.gridColorRow.isClickable = enabled
        binding.gridColorRow.alpha = if (enabled) 1f else 0.38f
    }

    private fun setupSetWallpaperFab() {
        binding.fabSetWallpaper.setOnClickListener {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(requireContext(), LiveWallpaperService::class.java)
                )
            }
            startActivity(intent)
        }
    }

    private fun setupDoubleTapAction() {
        val labels = arrayOf(
            getString(R.string.double_tap_swap),
            getString(R.string.double_tap_open),
            getString(R.string.double_tap_none)
        )
        val values = listOf(
            AppPreferences.DOUBLE_TAP_SWAP,
            AppPreferences.DOUBLE_TAP_OPEN,
            AppPreferences.DOUBLE_TAP_NONE
        )

        fun updateButton() {
            val idx = values.indexOf(prefs.doubleTapAction).coerceAtLeast(0)
            binding.doubleTapButton.text = labels[idx]
        }
        updateButton()

        binding.doubleTapButton.setOnClickListener {
            val current = values.indexOf(prefs.doubleTapAction).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.double_tap_action)
                .setSingleChoiceItems(labels, current) { dialog, which ->
                    prefs.doubleTapAction = values[which]
                    updateButton()
                    dialog.dismiss()
                }
                .show()
        }
    }

    private fun setupCenterFacesSwitch() {
        binding.centerFacesSwitch.isChecked = prefs.centerFacesEnabled
        binding.centerFacesSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.centerFacesEnabled = checked
        }
    }

    private fun setupFacesOnlySwitch() {
        binding.facesOnlySwitch.isChecked = prefs.facesOnlyEnabled
        binding.facesOnlySwitch.setOnCheckedChangeListener { _, checked ->
            if (checked && !prefs.facesOnlyEnabled) {
                // Revert the switch visually until user confirms
                binding.facesOnlySwitch.isChecked = false
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.faces_only_warning_title)
                    .setMessage(R.string.faces_only_warning_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        prefs.facesOnlyEnabled = true
                        binding.facesOnlySwitch.isChecked = true
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                prefs.facesOnlyEnabled = checked
            }
        }
    }

    private fun updateColorSwatch() {
        val color = prefs.gridColor
        val outline = resolveAttrColor(com.google.android.material.R.attr.colorOutline)
        binding.gridColorSwatch.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke(2.dpToPx(), outline)
        }
    }

    private fun showColorPicker() {
        val colors = listOf(
            // Neutrals
            Color.parseColor("#000000"), // Black
            Color.parseColor("#212121"), // Grey 900
            Color.parseColor("#616161"), // Grey 700
            Color.parseColor("#FFFFFF"), // White
            // Warm
            Color.parseColor("#B71C1C"), // Red 900
            Color.parseColor("#BF360C"), // Deep Orange 900
            Color.parseColor("#E65100"), // Orange 900
            Color.parseColor("#3E2723"), // Brown 900
            // Cool
            Color.parseColor("#0D47A1"), // Blue 900
            Color.parseColor("#1A237E"), // Indigo 900
            Color.parseColor("#4A148C"), // Purple 900
            Color.parseColor("#880E4F"), // Pink 900
            // Nature
            Color.parseColor("#1B5E20"), // Green 900
            Color.parseColor("#004D40"), // Teal 900
            Color.parseColor("#006064"), // Cyan 900
            Color.parseColor("#263238"), // Blue Grey 900
        )

        val swatchSize = 48.dpToPx()
        val margin = 8.dpToPx()
        val outline = resolveAttrColor(com.google.android.material.R.attr.colorOutline)
        val primary = resolveAttrColor(androidx.appcompat.R.attr.colorPrimary)

        val grid = GridLayout(requireContext()).apply {
            columnCount = 4
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(margin, margin, margin, margin)
        }

        var dialog: androidx.appcompat.app.AlertDialog? = null

        colors.forEach { color ->
            val isSelected = color == prefs.gridColor
            val swatch = object : View(requireContext()) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    val size = MeasureSpec.getSize(widthMeasureSpec)
                    setMeasuredDimension(size, size)
                }
            }.apply {
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED),
                    GridLayout.spec(GridLayout.UNDEFINED, 1f)
                ).apply {
                    width = 0
                    height = 0
                    setMargins(margin, margin, margin, margin)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(if (isSelected) 4.dpToPx() else 2.dpToPx(),
                              if (isSelected) primary else outline)
                }
                setOnClickListener {
                    prefs.gridColor = color
                    updateColorSwatch()
                    dialog?.dismiss()
                }
            }
            grid.addView(swatch)
        }

        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.grid_color_picker_title)
            .setView(grid)
            .create()
        dialog.show()
    }

    private fun resolveAttrColor(attrRes: Int): Int {
        val tv = TypedValue()
        requireContext().theme.resolveAttribute(attrRes, tv, true)
        return tv.data
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
