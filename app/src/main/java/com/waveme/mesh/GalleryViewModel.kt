package com.waveme.mesh

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.waveme.mesh.data.AppDatabase
import com.waveme.mesh.data.MessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val database: AppDatabase
) : ViewModel() {

    private val _selectedTab = MutableLiveData<Int>(0)
    val selectedTab: LiveData<Int> = _selectedTab

    val mediaItems: LiveData<List<MessageEntity>> =
        database.messageDao().getGalleryPhotosAndVideos().asLiveData()

    val docItems: LiveData<List<MessageEntity>> =
        database.messageDao().getGalleryDocuments().asLiveData()

    val linkItems: LiveData<List<MessageEntity>> =
        database.messageDao().getGalleryLinks().asLiveData()

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }
}