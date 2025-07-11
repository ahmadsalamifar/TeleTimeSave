package com.ibashkimi.telegram.ui.chat

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.ibashkimi.telegram.data.DownloadedFile
import com.ibashkimi.telegram.data.TelegramClient
import com.ibashkimi.telegram.data.chats.ChatsRepository
import com.ibashkimi.telegram.data.messages.MessagesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.drinkless.td.libcore.telegram.TdApi
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ChatScreenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val client: TelegramClient,
    val chatsRepository: ChatsRepository,
    val messagesRepository: MessagesRepository
) : ViewModel() {

    private var chatId: Long = -1

    lateinit var chat: Flow<TdApi.Chat?>
        private set

    lateinit var messagesPaged: Flow<PagingData<TdApi.Message>>
        private set

    private val TAG_SAVE = "SaveMedia"

    init {
        viewModelScope.launch {
            client.fileDownloadedFlow.collectLatest { downloadedFile ->
                Log.d(
                    TAG_SAVE,
                    "File downloaded event received: FileId=${downloadedFile.fileId}, Path=${downloadedFile.path}"
                )
                val timestamp =
                    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val originalFile = File(downloadedFile.path)
                val extension = originalFile.extension.ifEmpty { "dat" }
                val fileName = "TeleSave_${timestamp}_${downloadedFile.fileId}.$extension"

                val mimeType = when (extension.lowercase(Locale.ROOT)) {
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "mp4" -> "video/mp4"
                    // TODO: Add more mime types as needed
                    else -> "application/octet-stream"
                }

                saveFileToPublicStorage(
                    context = context,
                    sourcePath = downloadedFile.path,
                    fileName = fileName,
                    mimeType = mimeType,
                    isVideo = mimeType.startsWith("video")
                )
            }
        }
    }

    fun setChatId(chatId: Long) {
        this.chatId = chatId
        this.chat = chatsRepository.getChat(chatId)
        this.messagesPaged = Pager(PagingConfig(pageSize = 30)) {
            messagesRepository.getMessagesPaged(chatId)
        }.flow.cachedIn(viewModelScope)
    }

    fun sendMessage(
        messageThreadId: Long = 0,
        replyToMessageId: Long = 0,
        options: TdApi.MessageSendOptions = TdApi.MessageSendOptions(),
        inputMessageContent: TdApi.InputMessageContent
    ): Deferred<TdApi.Message> {
        return messagesRepository.sendMessage(
            chatId, messageThreadId, replyToMessageId, options, inputMessageContent
        )
    }

    fun initiateSaveMedia(messageId: Long, fileId: Int) {
        Log.d(TAG_SAVE, "Initiating save for messageId: $messageId, fileId: $fileId")
        viewModelScope.launch {
            client.requestDownloadFile(fileId).collectLatest { success ->
                if (success) {
                    Log.d(
                        TAG_SAVE,
                        "Download request successful for fileId: $fileId. Waiting for UpdateFile..."
                    )
                    // Actual saving is handled by the fileDownloadedFlow collector
                } else {
                    Log.e(TAG_SAVE, "Download request failed for fileId: $fileId")
                    // TODO: Show error to user (e.g., via a StateFlow<UiEvent> in ViewModel)
                }
            }
        }
    }

    private fun saveFileToPublicStorage(
        context: Context,
        sourcePath: String,
        fileName: String,
        mimeType: String,
        isVideo: Boolean
    ) {
        val contentResolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val subFolder = "TeleSave"
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isVideo) Environment.DIRECTORY_MOVIES + File.separator + subFolder
                    else Environment.DIRECTORY_PICTURES + File.separator + subFolder
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val directoryType =
                    if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                val directory = Environment.getExternalStoragePublicDirectory(directoryType)
                val teleSaveDir = File(directory, "TeleSave")
                if (!teleSaveDir.exists() && !teleSaveDir.mkdirs()) {
                    Log.e(TAG_SAVE, "Failed to create directory: $teleSaveDir")
                    // TODO: Show error to user
                    return
                }
                val file = File(teleSaveDir, fileName)
                put(MediaStore.MediaColumns.DATA, file.absolutePath) // For API < 29
            }
        }

        val collectionUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (isVideo) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        var uri: Uri? = null
        try {
            uri = contentResolver.insert(collectionUri, contentValues)
                ?: throw IOException("Failed to create new MediaStore record for $fileName at $collectionUri")

            contentResolver.openOutputStream(uri)?.use { outputStream ->
                File(sourcePath).inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: throw IOException("Failed to get output stream for $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, contentValues, null, null)
            }
            Log.d(TAG_SAVE, "File saved successfully to: $uri")
            // TODO: Show success message to user (e.g., Toast or Snackbar)
        } catch (e: Exception) {
            Log.e(TAG_SAVE, "Failed to save file '$fileName': ${e.message}", e)
            if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Clean up pending entry if it was created
                contentResolver.delete(uri, null, null)
            }
            // TODO: Show error message to user
        }
    }
}