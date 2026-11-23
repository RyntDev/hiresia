package com.hiresia.audioplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.hiresia.audioplayer.R
import com.hiresia.audioplayer.databinding.ItemLibraryFolderBinding
import com.hiresia.audioplayer.model.LibraryFolder

class LibraryFolderAdapter(
    private var folders: List<LibraryFolder>,
    private val onFolderClick: (LibraryFolder) -> Unit,
    private val onRescanClick: (LibraryFolder) -> Unit,
    private val onDeleteClick: (LibraryFolder) -> Unit
) : RecyclerView.Adapter<LibraryFolderAdapter.FolderViewHolder>() {

    class FolderViewHolder(val binding: ItemLibraryFolderBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemLibraryFolderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        val folder = folders[position]
        val context = holder.itemView.context

        holder.binding.tvFolderTitle.text = folder.friendlyName
        holder.binding.tvFolderPath.text = folder.pathOrUri
        holder.binding.tvTrackBadge.text = com.hiresia.audioplayer.repository.AppLanguageManager.formatSongsCount(folder.trackCount)

        if (folder.isSaf) {
            holder.binding.ivFolderIcon.setImageResource(android.R.drawable.ic_menu_save)
            holder.binding.ivFolderIcon.setColorFilter(ContextCompat.getColor(context, R.color.cyan_accent))
        } else {
            holder.binding.ivFolderIcon.setImageResource(android.R.drawable.ic_menu_agenda)
            holder.binding.ivFolderIcon.setColorFilter(ContextCompat.getColor(context, R.color.gold_accent))
        }

        holder.binding.btnRescanFolder.setOnClickListener {
            onRescanClick(folder)
        }

        holder.binding.btnDeleteFolder.setOnClickListener {
            onDeleteClick(folder)
        }

        holder.itemView.setOnClickListener {
            onFolderClick(folder)
        }
    }

    override fun getItemCount(): Int = folders.size

    fun updateFolders(newFolders: List<LibraryFolder>) {
        folders = newFolders
        notifyDataSetChanged()
    }
}
