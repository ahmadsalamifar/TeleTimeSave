package com.ibashkimi.telegram.data

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import org.drinkless.td.libcore.telegram.Client
import org.drinkless.td.libcore.telegram.TdApi
import javax.inject.Inject

/*
 * Go to https://my.telegram.org to obtain api id (integer) and api hash (string).
 * Put those in values (for example in values/api_keys.xml):
 * <resources>
 *   <integer name="telegram_api_id">your integer api id</integer>
 *   <string name="telegram_api_hash">your string api hash</string>
 * </resources>
 */

data class DownloadedFile(val fileId: Int, val path: String)

@OptIn(ExperimentalCoroutinesApi::class)
class TelegramClient @Inject constructor(
    private val tdLibParameters: TdApi.TdlibParameters
) : Client.ResultHandler {

    private val TAG = TelegramClient::class.java.simpleName

    val client = Client.create(this, null, null)

    private val _authState = MutableStateFlow(Authentication.UNKNOWN)
    val authState: StateFlow<Authentication> get() = _authState

    private val _fileDownloadedFlow = MutableSharedFlow<DownloadedFile>()
    val fileDownloadedFlow = _fileDownloadedFlow.asSharedFlow()

    init {
        client.send(TdApi.SetLogVerbosityLevel(1), this)
        client.send(TdApi.GetAuthorizationState(), this)
    }

    fun close() {
        client.close()
    }

    private val requestScope = CoroutineScope(Dispatchers.IO + SupervisorJob()) // Use SupervisorJob

    private fun setAuth(auth: Authentication) {
        _authState.value = auth
    }

    override fun onResult(data: TdApi.Object) {
        Log.d(TAG, "onResult: ${data::class.java.simpleName}")
        when (data.constructor) {
            TdApi.UpdateAuthorizationState.CONSTRUCTOR -> {
                Log.d(TAG, "UpdateAuthorizationState")
                onAuthorizationStateUpdated((data as TdApi.UpdateAuthorizationState).authorizationState)
            }
            TdApi.UpdateOption.CONSTRUCTOR -> {
                // Handle updateOption if necessary
            }
            TdApi.UpdateFile.CONSTRUCTOR -> {
                val updateFile = data as TdApi.UpdateFile
                Log.d(
                    TAG,
                    "UpdateFile: fileId=${updateFile.file.id}, remoteSize=${updateFile.file.remote.size}, localSize=${updateFile.file.local.size}, downloaded=${updateFile.file.local.isDownloadingCompleted}, path=${updateFile.file.local.path}"
                )
                if (updateFile.file.local.isDownloadingCompleted && updateFile.file.local.path.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "File download completed: id=${updateFile.file.id}, path=${updateFile.file.local.path}"
                    )
                    requestScope.launch {
                        _fileDownloadedFlow.emit(
                            DownloadedFile(
                                updateFile.file.id,
                                updateFile.file.local.path
                            )
                        )
                    }
                }
            }
            else -> Log.d(TAG, "Unhandled onResult call with data: $data.")
        }
    }

    private fun doAsync(job: () -> Unit) {
        requestScope.launch { job() }
    }

    fun startAuthentication() {
        Log.d(TAG, "startAuthentication called")
        if (_authState.value != Authentication.UNAUTHENTICATED) {
            throw IllegalStateException("Start authentication called but client already authenticated. State: ${_authState.value}.")
        }

        doAsync {
            client.send(TdApi.SetTdlibParameters(tdLibParameters)) {
                Log.d(TAG, "SetTdlibParameters result: $it")
                when (it.constructor) {
                    TdApi.Ok.CONSTRUCTOR -> {
                        //result.postValue(true)
                    }
                    TdApi.Error.CONSTRUCTOR -> {
                        //result.postValue(false)
                    }
                }
            }
        }
    }

    fun insertPhoneNumber(phoneNumber: String) {
        Log.d("TelegramClient", "phoneNumber: $phoneNumber")
        val settings = TdApi.PhoneNumberAuthenticationSettings(
            false,
            false,
            false
        )
        client.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, settings)) {
            Log.d("TelegramClient", "phoneNumber. result: $it")
            when (it.constructor) {
                TdApi.Ok.CONSTRUCTOR -> {

                }
                TdApi.Error.CONSTRUCTOR -> {

                }
            }
        }
    }

    fun insertCode(code: String) {
        Log.d("TelegramClient", "code: $code")
        doAsync {
            client.send(TdApi.CheckAuthenticationCode(code)) {
                when (it.constructor) {
                    TdApi.Ok.CONSTRUCTOR -> {

                    }
                    TdApi.Error.CONSTRUCTOR -> {

                    }
                }
            }
        }
    }

    fun insertPassword(password: String) {
        Log.d("TelegramClient", "inserting password")
        doAsync {
            client.send(TdApi.CheckAuthenticationPassword(password)) {
                when (it.constructor) {
                    TdApi.Ok.CONSTRUCTOR -> {

                    }
                    TdApi.Error.CONSTRUCTOR -> {

                    }
                }
            }
        }
    }

    private fun onAuthorizationStateUpdated(authorizationState: TdApi.AuthorizationState) {
        when (authorizationState.constructor) {
            TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR -> {
                Log.d(
                    TAG,
                    "onResult: AuthorizationStateWaitTdlibParameters -> state = UNAUTHENTICATED"
                )
                setAuth(Authentication.UNAUTHENTICATED)
            }
            TdApi.AuthorizationStateWaitEncryptionKey.CONSTRUCTOR -> {
                Log.d(TAG, "onResult: AuthorizationStateWaitEncryptionKey")
                client.send(TdApi.CheckDatabaseEncryptionKey()) {
                    when (it.constructor) {
                        TdApi.Ok.CONSTRUCTOR -> {
                            Log.d(TAG, "CheckDatabaseEncryptionKey: OK")
                        }
                        TdApi.Error.CONSTRUCTOR -> {
                            Log.d(TAG, "CheckDatabaseEncryptionKey: Error")
                        }
                    }
                }
            }
            TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR -> {
                Log.d(TAG, "onResult: AuthorizationStateWaitPhoneNumber -> state = WAIT_FOR_NUMBER")
                setAuth(Authentication.WAIT_FOR_NUMBER)
            }
            TdApi.AuthorizationStateWaitCode.CONSTRUCTOR -> {
                Log.d(TAG, "onResult: AuthorizationStateWaitCode -> state = WAIT_FOR_CODE")
                setAuth(Authentication.WAIT_FOR_CODE)
            }
            TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR -> {
                Log.d(TAG, "onResult: AuthorizationStateWaitPassword")
                setAuth(Authentication.WAIT_FOR_PASSWORD)
            }
            TdApi.AuthorizationStateReady.CONSTRUCTOR -> {
                Log.d(TAG, "onResult: AuthorizationStateReady -> state = AUTHENTICATED")
                setAuth(Authentication.AUTHENTICATED)
            }
            TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR -> {
                Log.d(TAG, "onResult: AuthorizationStateLoggingOut")
                setAuth(Authentication.UNAUTHENTICATED)
            }
            TdApi.AuthorizationStateClosing.CONSTRUCTOR -> {
                Log.d(TAG, "onResult: AuthorizationStateClosing")
            }
            TdApi.AuthorizationStateClosed.CONSTRUCTOR -> {
                Log.d(TAG, "onResult: AuthorizationStateClosed")
            }
            else -> Log.d(TAG, "Unhandled authorizationState with data: $authorizationState.")
        }
    }

    // This function is likely used by UI to display images that are currently downloading or already downloaded.
    // It can remain as is, or be adapted if its sole purpose was to trigger downloads for immediate display.
    // For saving, we will rely on `requestDownloadFile` and the `fileDownloadedFlow`.
    fun downloadableFile(file: TdApi.File): Flow<String?> =
        file.takeIf {
            // Check if file is null or local is null before accessing properties
            it.local?.isDownloadingCompleted == false && it.local?.canBeDownloaded == true
        }?.id?.let { fileId ->
            // It seems this was intended to *start* download and then map to path.
            // This might trigger downloads repeatedly if not handled carefully.
            // For our save feature, we'll use a dedicated request function.
            // Let's assume this function is for UI display purposes and might need its own download trigger.
            // Or, it could be simplified to just return the local path if available.
            // For now, let's keep its existing logic but be mindful of its usage.
            requestDownloadFile(fileId).map { success -> if (success) file.local?.path else null }
        } ?: flowOf(file.local?.path.takeIf { !it.isNullOrEmpty() })


    // Renamed from downloadFile to requestDownloadFile to better reflect its action.
    // Returns true if the request was sent successfully, false otherwise.
    fun requestDownloadFile(fileId: Int): Flow<Boolean> = callbackFlow {
        // It's good practice to check if the file is already downloaded or being downloaded
        // before sending a new DownloadFile request, though TDLib might handle this internally.
        // For simplicity, we send the request directly.
        client.send(TdApi.DownloadFile(fileId, 1, 0, 0, true)) { result ->
            when (result.constructor) {
                TdApi.File.CONSTRUCTOR, TdApi.Ok.CONSTRUCTOR -> { // Ok might return the File object directly
                    Log.d(TAG, "DownloadFile request successful for fileId $fileId")
                    trySend(true)
                }
                TdApi.Error.CONSTRUCTOR -> {
                    val error = result as TdApi.Error
                    Log.e(TAG, "Failed to request download for fileId $fileId: ${error.message} (code: ${error.code})")
                    // Specific error code for "already downloaded" or "already being downloaded" could be checked here.
                    // For example, if error.message.contains("FILE_ALREADY_BEING_DOWNLOADED") or similar.
                    trySend(false) // Indicate failure or already in progress if distinguishable
                }
                else -> {
                    Log.w(TAG, "DownloadFile for $fileId returned unexpected type: ${result.javaClass.simpleName}")
                    trySend(false)
                }
            }
            close() // Important to close the callbackFlow after emitting.
        }
        awaitClose { /* Cleanup, if any, when flow is cancelled */ }
    }

    fun sendAsFlow(query: TdApi.Function): Flow<TdApi.Object> = callbackFlow {
        client.send(query) {
            when (it.constructor) {
                TdApi.Error.CONSTRUCTOR -> {
                    // It's better to send the actual error to the flow
                    // instead of just completing with error("").
                    // However, the original code just used error(""), so we'll keep it for now.
                    // Consider changing to: channel.close(TdException(it as TdApi.Error))
                    error("TDLib error on sendAsFlow for query: ${query.javaClass.simpleName}")
                }
                else -> {
                    offer(it)
                }
            }
            // close() // Original code had this commented. Closing here would mean only one response per query.
            // For flows that expect multiple updates (like authorization state), this should not be closed here.
            // For single-response queries, it's fine. Given it's a generic send, caution is needed.
            // Let's assume it's for single responses or the caller handles completion.
        }
        awaitClose { }
    }

    inline fun <reified T : TdApi.Object> send(query: TdApi.Function): Flow<T> =
        sendAsFlow(query).map { it as T }
}
