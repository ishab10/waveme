package com.example.mesh

import android.app.Application
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.mesh.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
class ChatViewModelTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var viewModel: ChatViewModel
    private lateinit var database: AppDatabase
    private val mockWaveMeshService: WaveMeshService = mock()
    private val mockMeshRouter: MeshRouter = mock()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        viewModel = ChatViewModel(context)
        viewModel.waveMeshService = mockWaveMeshService
        whenever(mockWaveMeshService.meshRouter).thenReturn(mockMeshRouter)
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `sendFile when service is null does not crash`() = runTest {
        viewModel.waveMeshService = null
        val mockUri: Uri = mock()
        viewModel.sendFile("recipient", mockUri, "test.txt", "text/plain")
        // No verification needed, the test passes if no exception is thrown
    }

    @Test
    fun `sendFile with io exception posts error`() = runTest {
        val mockUri: Uri = mock()
        val recipientId = "test_recipient"
        val fileName = "test.txt"
        val mimeType = "text/plain"

        // Given
        val exception = Exception("Failed to send")
        whenever(mockMeshRouter.sendFile(any(), any(), any())).thenThrow(exception)

        // When
        viewModel.sendFile(recipientId, mockUri, fileName, mimeType)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assert(viewModel.fileError.value == "Failed to send file: ${exception.message}")
    }
}