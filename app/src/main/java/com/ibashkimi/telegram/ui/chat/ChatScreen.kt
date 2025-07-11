package com.ibashkimi.telegram.ui.chat

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Gif
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemsIndexed
import com.ibashkimi.telegram.R
import com.ibashkimi.telegram.data.TelegramClient
import com.ibashkimi.telegram.ui.util.TelegramImage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.drinkless.td.libcore.telegram.TdApi

@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun ChatScreen(
    chatId: Long,
    navController: NavController,
    modifier: Modifier = Modifier,
    viewModel: ChatScreenViewModel = viewModel()
) {
    LaunchedEffect(chatId) {
        viewModel.setChatId(chatId)
    }
    val chat = viewModel.chat.collectAsState(null)
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(chat.value?.title ?: "", maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                })
        },
        content = { paddingValues ->
            ChatContent(
                viewModel = viewModel,
                modifier = Modifier.padding(paddingValues),
                // Placeholder for the actual save function from ViewModel
                onSaveMessageClicked = { messageId, fileId ->
                    Log.d("ChatScreen", "Save clicked for messageId: $messageId, fileId: $fileId")
                    // This will be replaced by viewModel.initiateSaveMedia(messageId, fileId)
                    viewModel.initiateSaveMedia(messageId, fileId) // Assuming this function will be created
                }
            )
        }
    )
}

@Composable
fun ChatContent(
    viewModel: ChatScreenViewModel,
    onSaveMessageClicked: (messageId: Long, fileId: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val history = viewModel.messagesPaged.collectAsLazyPagingItems()

    Column(modifier = modifier.fillMaxWidth()) {
        ChatHistory(
            client = viewModel.client,
            messages = history,
            onSaveMessageClicked = onSaveMessageClicked,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.0f)
        )
        val input = remember { mutableStateOf(TextFieldValue("")) }
        val scope = rememberCoroutineScope()
        MessageInput(
            input = input,
            insertGif = {
                // TODO
            }, attachFile = {
                // todo
            }, sendMessage = {
                if (it.isNotBlank()) { // Ensure message is not blank
                    scope.launch {
                        viewModel.sendMessage(
                            inputMessageContent = TdApi.InputMessageText(
                                TdApi.FormattedText(
                                    it,
                                    emptyArray()
                                ), false, false
                            )
                        ).await()
                        input.value = TextFieldValue() // Clear input
                        history.refresh() // Refresh messages
                    }
                }
            })
    }
}

@Composable
fun ChatLoading(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.loading),
            style = MaterialTheme.typography.h5,
        )
    }
}

@Composable
fun ChatHistory(
    client: TelegramClient,
    messages: LazyPagingItems<TdApi.Message>,
    onSaveMessageClicked: (messageId: Long, fileId: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier, reverseLayout = true) {
        when (val refreshState = messages.loadState.refresh) {
            is LoadState.Loading -> {
                item { ChatLoading(Modifier.fillParentMaxSize()) }
            }
            is LoadState.Error -> {
                item {
                    Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Cannot load messages: ${refreshState.error.localizedMessage}",
                            style = MaterialTheme.typography.h5,
                            color = MaterialTheme.colors.error
                        )
                    }
                }
            }
            is LoadState.NotLoading -> {
                if (messages.itemCount == 0) {
                    item {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Empty chat")
                        }
                    }
                }
            }
        }
        itemsIndexed(messages, key = { _, message -> message.id }) { index, message ->
            message?.let {
                // Ensure sender is User before casting; handle other sender types if necessary
                val currentMessageSenderUser = message.sender as? TdApi.MessageSenderUser
                val previousMessageSenderUser = if (index < messages.itemCount - 1) { // Check bounds for previous
                    messages.peek(index + 1)?.sender as? TdApi.MessageSenderUser
                } else null

                MessageItem(
                    isSameUserFromPreviousMessage = currentMessageSenderUser?.userId == previousMessageSenderUser?.userId,
                    client = client,
                    message = it,
                    onSaveMessageClicked = onSaveMessageClicked
                )
            }
        }
    }
}

@Composable
private fun MessageItem(
    isSameUserFromPreviousMessage: Boolean,
    client: TelegramClient,
    message: TdApi.Message,
    onSaveMessageClicked: (messageId: Long, fileId: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val messageAlignment = if (message.isOutgoing) Alignment.CenterEnd else Alignment.CenterStart
    val contentPadding = if (message.isOutgoing) {
        PaddingValues(start = 50.dp, end = 8.dp, top = 4.dp, bottom = 4.dp)
    } else {
        PaddingValues(start = 8.dp, end = 50.dp, top = 4.dp, bottom = 4.dp)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding),
        contentAlignment = messageAlignment
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            if (!message.isOutgoing && !isSameUserFromPreviousMessage) {
                val senderUser = message.sender as? TdApi.MessageSenderUser
                if (senderUser != null) {
                    ChatUserIcon(
                        client = client,
                        userId = senderUser.userId,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(42.dp)
                            .clip(CircleShape)
                    )
                }
            } else if (!message.isOutgoing) {
                Spacer(Modifier.width(50.dp)) // Space for icon if not shown
            }

            MessageItemCard { // Removed modifier here, will be applied by Box
                MessageItemContent(
                    client = client,
                    message = message,
                    onSaveMessageClicked = onSaveMessageClicked,
                    modifier = Modifier
                        .background(
                            if (message.isOutgoing) Color.Green.copy(alpha = 0.2f)
                            else MaterialTheme.colors.surface // Or another color for incoming
                        )
                        .padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun MessageItemCard(
    // modifier: Modifier = Modifier, // Modifier removed, applied by MessageItem's Box
    content: @Composable () -> Unit
) = Card(
    elevation = 2.dp,
    shape = RoundedCornerShape(8.dp),
    // modifier = modifier, // Modifier removed
    content = content
)

@Composable
private fun MessageItemContent(
    client: TelegramClient,
    message: TdApi.Message,
    onSaveMessageClicked: (messageId: Long, fileId: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    when (message.content) {
        is TdApi.MessageText -> TextMessage(message, modifier)
        is TdApi.MessageVideo -> VideoMessage(client, message, onSaveClicked = onSaveMessageClicked, modifier)
        is TdApi.MessageCall -> CallMessage(message, modifier)
        is TdApi.MessageAudio -> AudioMessage(message, modifier)
        is TdApi.MessageSticker -> StickerMessage(client, message, modifier)
        is TdApi.MessageAnimation -> AnimationMessage(client, message, modifier)
        is TdApi.MessagePhoto -> PhotoMessage(client, message, onSaveClicked = onSaveMessageClicked, modifier)
        is TdApi.MessageVideoNote -> VideoNoteMessage(client, message, modifier)
        is TdApi.MessageVoiceNote -> VoiceNoteMessage(message, modifier)
        else -> UnsupportedMessage(modifier)
    }
}

@Composable
private fun ChatUserIcon(client: TelegramClient, userId: Int, modifier: Modifier) {
    val userFlow = remember(userId) { client.send<TdApi.User>(TdApi.GetUser(userId)) }
    val user = userFlow.collectAsState(initial = null).value
    TelegramImage(client, user?.profilePhoto?.small, modifier = modifier)
}

@Composable
fun MessageInput(
    modifier: Modifier = Modifier,
    input: MutableState<TextFieldValue>, // Removed default
    insertGif: () -> Unit, // Removed default
    attachFile: () -> Unit, // Removed default
    sendMessage: (String) -> Unit // Removed default
) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colors.surface, elevation = 6.dp) { // Added fillMaxWidth
        TextField(
            value = input.value,
            modifier = Modifier.fillMaxWidth(),
            onValueChange = { input.value = it },
            textStyle = MaterialTheme.typography.body1,
            placeholder = {
                Text("Message")
            },
            leadingIcon = {
                IconButton(onClick = insertGif) {
                    Icon(
                        imageVector = Icons.Default.Gif,
                        contentDescription = "Insert GIF"
                    )
                }
            },
            trailingIcon = {
                if (input.value.text.isEmpty()) {
                    Row {
                        IconButton(onClick = attachFile) {
                            Icon(
                                imageVector = Icons.Outlined.AttachFile,
                                contentDescription = "Attach File"
                            )
                        }
                        IconButton(onClick = { /* TODO: Implement Mic Action */ }) {
                            Icon(
                                imageVector = Icons.Outlined.Mic,
                                contentDescription = "Record Voice"
                            )
                        }
                    }
                } else {
                    IconButton(onClick = { sendMessage(input.value.text) }) {
                        Icon(
                            imageVector = Icons.Outlined.Send,
                            contentDescription = "Send Message"
                        )
                    }
                }
            },
            colors = TextFieldDefaults.textFieldColors(
                backgroundColor = MaterialTheme.colors.surface,
                focusedIndicatorColor = Color.Transparent, // Optional: remove indicator
                unfocusedIndicatorColor = Color.Transparent // Optional: remove indicator
            )
        )
    }
}