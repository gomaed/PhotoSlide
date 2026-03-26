package com.android.photoslide.ui.folders

import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.android.photoslide.databinding.ItemFolderBinding

class FolderAdapter(private val onDelete: (Uri) -> Unit) :
    ListAdapter<FolderItem, FolderAdapter.ViewHolder>(DiffCallback()) {

    inner class ViewHolder(val binding: ItemFolderBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFolderBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.binding.folderName.text = item.name
        holder.binding.folderUri.text = item.uri.path ?: item.uri.toString()
        holder.binding.btnDelete.setOnClickListener { onDelete(item.uri) }
    }

    private class DiffCallback : DiffUtil.ItemCallback<FolderItem>() {
        override fun areItemsTheSame(oldItem: FolderItem, newItem: FolderItem) =
            oldItem.uri == newItem.uri

        override fun areContentsTheSame(oldItem: FolderItem, newItem: FolderItem) =
            oldItem == newItem
    }
}
