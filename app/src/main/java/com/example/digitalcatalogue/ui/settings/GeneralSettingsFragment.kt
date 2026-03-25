package com.example.digitalcatalogue.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.fragment.app.Fragment
import com.example.digitalcatalogue.R
import com.example.digitalcatalogue.data.AppPreferences
import com.example.digitalcatalogue.databinding.FragmentGeneralSettingsBinding

class GeneralSettingsFragment : Fragment() {

    private var _binding: FragmentGeneralSettingsBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: AppPreferences

    private val intervalValues = listOf(5, 10, 30, 60, 300, 900, 1800, 3600, 21600, 43200, 86400, AppPreferences.INTERVAL_NEVER)

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
        setupSortOrder()
        setupSpacingSwitch()
        setupDoubleTapSwitch()
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

    private fun setupSortOrder() {
        val sortLabels = listOf(
            getString(R.string.sort_name_asc),
            getString(R.string.sort_date_desc),
            getString(R.string.sort_random)
        )
        val sortValues = listOf(
            AppPreferences.SORT_NAME_ASC,
            AppPreferences.SORT_DATE_DESC,
            AppPreferences.SORT_RANDOM
        )

        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            sortLabels
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.sortOrderSpinner.adapter = adapter
        binding.sortOrderSpinner.setSelection(
            sortValues.indexOf(prefs.sortOrder).coerceAtLeast(0)
        )
        binding.sortOrderSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    prefs.sortOrder = sortValues[position]
                }
                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
    }

    private fun setupSpacingSwitch() {
        binding.spacingSwitch.isChecked = prefs.showSpacing
        binding.spacingSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.showSpacing = checked
        }
    }

    private fun setupDoubleTapSwitch() {
        binding.doubleTapSwitch.isChecked = prefs.doubleTapAdvance
        binding.doubleTapSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.doubleTapAdvance = checked
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
