package com.android.photoslide.ui.layout

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.android.photoslide.R
import com.android.photoslide.data.AppPreferences
import com.android.photoslide.databinding.FragmentLayoutSelectBinding
import com.google.android.material.tabs.TabLayoutMediator

class LayoutSelectFragment : Fragment() {

    private var _binding: FragmentLayoutSelectBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: AppPreferences
    private lateinit var portraitAdapter: LayoutOptionAdapter
    private lateinit var landscapeAdapter: LayoutOptionAdapter
    private var portraitCols = 2
    private var portraitRows = 2
    private var landscapeCols = 3
    private var landscapeRows = 1

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
        portraitRows = prefs.portraitRows
        landscapeCols = prefs.landscapeCols
        landscapeRows = prefs.landscapeRows

        portraitAdapter = LayoutOptionAdapter(
            gridOptions,
            GridOption(prefs.portraitCols, prefs.portraitRows)
        ) { option ->
            prefs.portraitCols = option.cols
            prefs.portraitRows = option.rows
            portraitCols = option.cols
            portraitRows = option.rows
            updateStaggerEnabled()
        }

        landscapeAdapter = LayoutOptionAdapter(
            gridOptions,
            GridOption(prefs.landscapeCols, prefs.landscapeRows),
            isLandscape = true
        ) { option ->
            prefs.landscapeCols = option.cols
            prefs.landscapeRows = option.rows
            landscapeRows = option.rows
            landscapeCols = option.cols
            updateStaggerEnabled()
        }

        binding.viewPager.adapter = LayoutPagerAdapter()

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.portrait)
                else -> getString(R.string.landscape)
            }
        }.attach()

        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateStaggerEnabled()
            }
        })

        setupStaggerSlider()
        updateStaggerEnabled()
    }

    private inner class LayoutPagerAdapter : RecyclerView.Adapter<LayoutPagerAdapter.PageHolder>() {

        inner class PageHolder(val recyclerView: RecyclerView) : RecyclerView.ViewHolder(recyclerView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
            val rv = RecyclerView(parent.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 8.dpToPx())
                clipToPadding = false
            }
            return PageHolder(rv)
        }

        override fun onBindViewHolder(holder: PageHolder, position: Int) {
            val gridCols = if (position == 0) 3 else 2
            holder.recyclerView.layoutManager = GridLayoutManager(holder.recyclerView.context, gridCols)
            holder.recyclerView.adapter = when (position) {
                0 -> portraitAdapter
                else -> landscapeAdapter
            }
        }

        override fun getItemCount() = 2
    }

    private fun Int.dpToPx(): Int =
        (this * resources.displayMetrics.density).toInt()

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
        val cols = if (binding.viewPager.currentItem == 0) portraitCols else landscapeCols
        val rows = if (binding.viewPager.currentItem == 0) portraitRows else landscapeRows
        val enabled = cols > 1 && rows > 1
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
