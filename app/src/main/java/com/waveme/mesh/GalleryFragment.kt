package com.waveme.mesh

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.GridLayoutManager
import com.waveme.mesh.databinding.FragmentGalleryBinding
import com.google.android.material.tabs.TabLayout
import dagger.hilt.android.AndroidEntryPoint
import java.io.File

@AndroidEntryPoint
class GalleryFragment : Fragment() {

    private var _binding: FragmentGalleryBinding? = null
    private val binding get() = _binding!!

    private val viewModel: GalleryViewModel by viewModels()
    private lateinit var galleryAdapter: GalleryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGalleryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        galleryAdapter = GalleryAdapter { item ->
            val path = item.attachmentPath ?: return@GalleryAdapter
            
            when (item.type) {
                "IMAGE" -> {
                    val intent = Intent(requireContext(), FullscreenImageActivity::class.java).apply {
                        putExtra("IMAGE_PATH", path)
                    }
                    startActivity(intent)
                }
                "VIDEO", "PDF", "FILE" -> {
                    openFile(path)
                }
            }
        }

        binding.galleryRecyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 3)
            adapter = galleryAdapter
        }

        binding.galleryTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                tab?.let { viewModel.selectTab(it.position) }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        viewModel.selectedTab.observe(viewLifecycleOwner) { position ->
            updateGalleryContent(position)
        }
    }

    private fun openFile(path: String) {
        try {
            val context = requireContext()
            val file = File(path)
            if (!file.exists()) {
                Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
                return
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            
            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            
            val chooser = Intent.createChooser(intent, "Open file with")
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Cannot open file", Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun updateGalleryContent(position: Int) {
        viewModel.mediaItems.removeObservers(viewLifecycleOwner)
        viewModel.docItems.removeObservers(viewLifecycleOwner)
        viewModel.linkItems.removeObservers(viewLifecycleOwner)

        when (position) {
            0 -> { 
                viewModel.mediaItems.observe(viewLifecycleOwner) { items ->
                    galleryAdapter.submitList(items)
                    updateEmptyView(items.isNullOrEmpty(), "No photos or videos found")
                }
            }
            1 -> { 
                viewModel.docItems.observe(viewLifecycleOwner) { items ->
                    galleryAdapter.submitList(items)
                    updateEmptyView(items.isNullOrEmpty(), "No documents found")
                }
            }
            2 -> {
                viewModel.linkItems.observe(viewLifecycleOwner) { items ->
                    galleryAdapter.submitList(items)
                    updateEmptyView(items.isNullOrEmpty(), "No shared links found")
                }
            }
        }
    }

    private fun updateEmptyView(isEmpty: Boolean, message: String) {
        if (isEmpty) {
            binding.emptyGalleryView.text = message
            binding.emptyGalleryView.visibility = View.VISIBLE
            binding.galleryRecyclerView.visibility = View.GONE
        } else {
            binding.emptyGalleryView.visibility = View.GONE
            binding.galleryRecyclerView.visibility = View.VISIBLE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
