package com.gomaed.photoslide.ui.layout

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.gomaed.photoslide.databinding.ItemLayoutOptionBinding

class LayoutOptionAdapter(
    private val options: List<GridOption>,
    private var selectedOption: GridOption,
    private val isLandscape: Boolean = false,
    private val onSelect: (GridOption) -> Unit
) : RecyclerView.Adapter<LayoutOptionAdapter.ViewHolder>() {

    var staggerRatio: Int = 50
        set(value) { field = value; notifyItemRangeChanged(0, itemCount, STAGGER_PAYLOAD) }

    inner class ViewHolder(val binding: ItemLayoutOptionBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLayoutOptionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: List<Any>) {
        if (payloads.any { it === STAGGER_PAYLOAD }) {
            holder.binding.gridPreview.staggerRatio = staggerRatio
            return
        }
        onBindViewHolder(holder, position)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val option = options[position]
        holder.binding.gridPreview.isLandscape = isLandscape
        holder.binding.gridPreview.cols = option.cols
        holder.binding.gridPreview.rows = option.rows
        holder.binding.gridPreview.staggerRatio = staggerRatio
        holder.binding.gridLabel.text = "${option.cols} \u00d7 ${option.rows}"
        holder.binding.card.isChecked = option == selectedOption

        holder.binding.card.setOnClickListener {
            val oldPos = options.indexOf(selectedOption)
            selectedOption = option
            notifyItemChanged(oldPos)
            notifyItemChanged(position)
            onSelect(option)
        }
    }

    override fun getItemCount() = options.size

    companion object {
        private val STAGGER_PAYLOAD = Any()
    }
}
