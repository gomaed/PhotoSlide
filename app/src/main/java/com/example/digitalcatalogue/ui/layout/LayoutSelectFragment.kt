package com.example.digitalcatalogue.ui.layout

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import com.example.digitalcatalogue.R
import com.example.digitalcatalogue.data.AppPreferences
import com.example.digitalcatalogue.databinding.FragmentLayoutSelectBinding
import com.google.android.material.tabs.TabLayout

class LayoutSelectFragment : Fragment() {

    private var _binding: FragmentLayoutSelectBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: AppPreferences
    private lateinit var portraitAdapter: LayoutOptionAdapter
    private lateinit var landscapeAdapter: LayoutOptionAdapter
    private var portraitCols = 2
    private var landscapeCols = 3
    private var activeTab = 0

    private val gridOptions = listOf(
        GridOption(1, 1),
        GridOption(2, 1),
        GridOption(1, 2),
        GridOption(2, 2),
        GridOption(3, 1),
        GridOption(1, 3),
        GridOption(3, 2),
        GridOption(2, 3),
        GridOption(3, 3),
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLayoutSelectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        portraitCols = prefs.portraitCols
        landscapeCols = prefs.landscapeCols

        portraitAdapter = LayoutOptionAdapter(
            gridOptions,
            GridOption(prefs.portraitCols, prefs.portraitRows)
        ) { option ->
            prefs.portraitCols = option.cols
            prefs.portraitRows = option.rows
            portraitCols = option.cols
            updateStaggerEnabled()
        }

        landscapeAdapter = LayoutOptionAdapter(
            gridOptions,
            GridOption(prefs.landscapeCols, prefs.landscapeRows)
        ) { option ->
            prefs.landscapeCols = option.cols
            prefs.landscapeRows = option.rows
            landscapeCols = option.cols
            updateStaggerEnabled()
        }

        binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 3)
        binding.recyclerView.adapter = portraitAdapter

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                activeTab = tab.position
                binding.recyclerView.adapter = when (tab.position) {
                    0 -> portraitAdapter
                    else -> landscapeAdapter
                }
                updateStaggerEnabled()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        setupStaggerSlider()
        updateStaggerEnabled()
    }

    private fun setupStaggerSlider() {
        val current = prefs.staggerRatio
        binding.staggerSlider.apply {
            valueFrom = 50f
            valueTo = 70f
            stepSize = 1f
            value = current.toFloat()
        }
        binding.staggerLabel.text = staggerLabel(current)
        binding.staggerSlider.setLabelFormatter { value -> staggerLabel(value.toInt()) }
        binding.staggerSlider.addOnChangeListener { _, value, _ ->
            val ratio = value.toInt()
            prefs.staggerRatio = ratio
            binding.staggerLabel.text = staggerLabel(ratio)
        }
    }

    private fun updateStaggerEnabled() {
        val cols = if (activeTab == 0) portraitCols else landscapeCols
        val enabled = cols > 1
        binding.staggerSlider.isEnabled = enabled
        binding.staggerTitle.isEnabled = enabled
        binding.staggerLabel.isEnabled = enabled
        binding.staggerTitle.alpha = if (enabled) 1f else 0.38f
        binding.staggerLabel.alpha = if (enabled) 1f else 0.38f
    }

    private fun staggerLabel(ratio: Int): String {
        return if (ratio <= 50) getString(R.string.stagger_none)
        else "$ratio/${100 - ratio}"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
