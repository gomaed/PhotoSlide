package com.android.photoslide.ui.folders

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.android.photoslide.data.AppPreferences
import com.android.photoslide.databinding.FragmentFolderSelectBinding

class FolderSelectFragment : Fragment() {

    private var _binding: FragmentFolderSelectBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: AppPreferences
    private lateinit var adapter: FolderAdapter

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { addFolder(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFolderSelectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = AppPreferences(requireContext())

        adapter = FolderAdapter { uri -> removeFolder(uri) }
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.fabAddFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        loadFolders()
    }

    override fun onResume() {
        super.onResume()
        loadFolders()
    }

    private fun loadFolders() {
        val folders = prefs.selectedFolderUris.mapNotNull { uriStr ->
            val uri = Uri.parse(uriStr)
            val docFile = DocumentFile.fromTreeUri(requireContext(), uri)
            if (docFile != null) FolderItem(uri, docFile.name ?: uri.lastPathSegment ?: "Unknown")
            else null
        }.sortedBy { it.name }

        adapter.submitList(folders)
        binding.emptyView.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun addFolder(uri: Uri) {
        requireContext().contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        val uris = prefs.selectedFolderUris.toMutableSet()
        uris.add(uri.toString())
        prefs.selectedFolderUris = uris
        loadFolders()
    }

    private fun removeFolder(uri: Uri) {
        val uris = prefs.selectedFolderUris.toMutableSet()
        uris.remove(uri.toString())
        prefs.selectedFolderUris = uris
        loadFolders()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
