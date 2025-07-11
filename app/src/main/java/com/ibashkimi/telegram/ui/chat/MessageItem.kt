package com.ibashkimi.telegram.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.outlined.SyncProblem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.ibashkimi.telegram.data.TelegramClient
import com.ibashkimi.telegram.ui.util.TelegramImage // Assuming this handles CoilImage or similar
import org.drinkless.td.libcore.telegram.TdApi
import java.io.File // Keep for CoilImage if used directly in TelegramImage or here
import java.util.*
// import com.google.accompanist.coil.CoilImage // Replaced by io.coil-kt:coil-compose

@Composable
fun TextMessage(message: TdApi.Message, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        val content = message.content as? TdApi.MessageText ?: return TextUnsupportFormat(modifier)
        TextMessageContent(content)
        MessageStatus(message)
    }
}

@Composable
private fun TextUnsupportFormat(modifier: Modifier = Modifier) {
    Text(text = "<Unsupported Text Format>", modifier = modifier)
}


@Composable
private fun TextMessageContent(content: TdApi.MessageText, modifier: Modifier = Modifier) {
    Text(text = content.text.text, modifier = modifier)
}

@Composable
fun AudioMessage(message: TdApi.Message, modifier: Modifier = Modifier) {
    val content = message.content as? TdApi.MessageAudio ?: return UnsupportedMessage(title = "<Audio Error>")
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Text(text = "Audio ${content.audio.duration}", modifier = modifier)
        content.caption.text.takeIf { it.isNotBlank() }?.let {
            Text(it)
        }
        MessageStatus(message)
    }
}

@Composable
fun VideoMessage(
    client: TelegramClient, // Added client for consistency, might be used for thumbnail later
    message: TdApi.Message,
    onSaveClicked: (messageId: Long, fileId: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val content = message.content as? TdApi.MessageVideo ?: return UnsupportedMessage(title = "<Video Error>")
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        // TODO: Implement actual video thumbnail display using client if needed
        // For now, showing text placeholder similar to original
        Text(text = "Video ${content.video.duration}s (TTL: ${message.ttl}s)")
        content.caption.text.takeIf { it.isNotBlank() }?.let {
            Text(it)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (message.ttl > 0) {
                val videoFileId = content.video.video.id
                if (videoFileId != 0) {
                    IconButton(onClick = { onSaveClicked(message.id, videoFileId) }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save Video")
                    }
                }
            }
            Spacer(Modifier.weight(1f)) // Pushes MessageStatus to the end if Row is fillMaxWidth
            MessageStatus(message/*, Modifier.padding(start = 4.dp)*/) // Padding might not be needed if Spacer is used
        }
    }
}

@Composable
fun StickerMessage(
    client: TelegramClient,
    message: TdApi.Message,
    modifier: Modifier = Modifier
) {
    val content = message.content as? TdApi.MessageSticker ?: return UnsupportedMessage(title = "<Sticker Error>")
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        StickerMessageContent(client, content)
        MessageStatus(message)
    }
}

@Composable
private fun StickerMessageContent(
    client: TelegramClient,
    content: TdApi.MessageSticker,
    modifier: Modifier = Modifier
) {
    if (content.sticker.isAnimated) {
        Text(text = "<Animated Sticker> ${content.sticker.emoji}", modifier = modifier)
    } else {
        Box(contentAlignment = Alignment.BottomEnd) {
            TelegramImage(client = client, file = content.sticker.sticker)
            content.sticker.emoji.takeIf { it.isNotBlank() }?.let {
                Text(text = it, modifier = modifier)
            }
        }
    }
}

@Composable
fun AnimationMessage(
    client: TelegramClient,
    message: TdApi.Message,
    modifier: Modifier = Modifier
) {
    val content = message.content as? TdApi.MessageAnimation ?: return UnsupportedMessage(title = "<Animation Error>")
    // Assuming TelegramImage can handle animation or uses Coil/Glide which can.
    val animationFile = content.animation.animation
    val pathFlow = client.downloadableFile(animationFile).collectAsState(initial = null)

    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        // Display the animation using TelegramImage or a specific Composable for animations
        TelegramImage(client = client, file = animationFile, modifier = Modifier.size(150.dp))
        // Optionally, show path or download status for debugging
        // pathFlow.value?.let { Text("Path: $it") } ?: Text("Path: N/A")
        MessageStatus(message)
    }
}

@Composable
fun CallMessage(message: TdApi.Message, modifier: Modifier = Modifier) {
    val content = message.content as? TdApi.MessageCall ?: return UnsupportedMessage(title = "<Call Error>")
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        CallMessageContent(content)
        MessageStatus(message)
    }
}

@Composable
private fun CallMessageContent(content: TdApi.MessageCall, modifier: Modifier = Modifier) {
    val msg = when (content.discardReason) {
        is TdApi.CallDiscardReasonHungUp -> "Incoming call"
        is TdApi.CallDiscardReasonDeclined -> "Declined call"
        is TdApi.CallDiscardReasonDisconnected -> "Call disconnected"
        is TdApi.CallDiscardReasonMissed -> "Missed call"
        is TdApi.CallDiscardReasonEmpty -> "Call: Unknown state"
        else -> "Call: Unknown state"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = msg) // Removed modifier = modifier from here as Row has it
        Icon(
            imageVector = Icons.Outlined.Call,
            contentDescription = null,
            modifier = Modifier
                .padding(start = 8.dp) // Only start padding if text is before it
                .size(18.dp)
        )
    }
}

@Composable
fun PhotoMessage(
    client: TelegramClient,
    message: TdApi.Message,
    onSaveClicked: (messageId: Long, fileId: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val content = message.content as? TdApi.MessagePhoto ?: return UnsupportedMessage(title = "<Photo Error>")
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        PhotoMessageContent(client, content)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (message.ttl > 0) {
                val photoFileId = content.photo.sizes.lastOrNull()?.photo?.id
                if (photoFileId != null && photoFileId != 0) {
                    IconButton(onClick = { onSaveClicked(message.id, photoFileId) }) {
                        Icon(Icons.Filled.Save, contentDescription = "Save Photo")
                    }
                }
            }
            Spacer(Modifier.weight(1f)) // Pushes MessageStatus to the end
            MessageStatus(message/*, Modifier.padding(start = 4.dp)*/)
        }
    }
}

@Composable
private fun PhotoMessageContent(
    client: TelegramClient,
    content: TdApi.MessagePhoto, // Changed from TdApi.MessagePhoto to content
    modifier: Modifier = Modifier
) {
    val photoSize = content.photo.sizes.lastOrNull() // Get the largest available size
    if (photoSize == null) {
        UnsupportedMessage(title = "<Photo unavailable>")
        return
    }
    val width: Dp = with(LocalDensity.current) {
        photoSize.width.toDp()
    }
    Column(modifier.width(min(200.dp, width))) { // Use the passed modifier
        TelegramImage(
            client,
            photoSize.photo, // Pass the TdApi.File object
            modifier = Modifier.fillMaxWidth()
        )
        content.caption.text.takeIf { it.isNotEmpty() }
            ?.let { Text(text = it, Modifier.padding(top = 4.dp)) } // Simpler padding
    }
}


@Composable
fun VideoNoteMessage(
    client: TelegramClient,
    message: TdApi.Message,
    modifier: Modifier = Modifier
) {
    val content = message.content as? TdApi.MessageVideoNote ?: return UnsupportedMessage(title = "<Video Note Error>")
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Text("<Video note>") // Placeholder
        TelegramImage(
            client,
            content.videoNote.thumbnail?.file,
            Modifier.size(150.dp)
        )
        MessageStatus(message)
    }
}

@Composable
fun VoiceNoteMessage(
    message: TdApi.Message,
    modifier: Modifier = Modifier
) {
    val content = message.content as? TdApi.MessageVoiceNote ?: return UnsupportedMessage(title = "<Voice Note Error>")
    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Text("<Voice note>") // Placeholder
        content.caption.text.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(top = 4.dp))
        }
        MessageStatus(message)
    }
}

@Composable
fun UnsupportedMessage(modifier: Modifier = Modifier, title: String? = null) {
    Text(title ?: "<Unsupported message>", modifier = modifier.padding(8.dp))
}

@Composable
private fun MessageStatus(message: TdApi.Message, modifier: Modifier = Modifier) {
    val alignmentAndPadding = if (message.isOutgoing) Modifier.padding(start = 8.dp) else Modifier
    Row(
        modifier = modifier.then(alignmentAndPadding), // Combine modifiers
        verticalAlignment = Alignment.CenterVertically
    ) {
        MessageTime(message = message)
        if (message.isOutgoing) {
            MessageSendingState(message.sendingState, Modifier.size(16.dp).padding(start = 4.dp))
        }
    }
}

@Composable
private fun MessageTime(message: TdApi.Message, modifier: Modifier = Modifier) {
    // Original date formatting logic
    val date = Date(message.date * 1000L) // Assuming message.date is in seconds
    val calendar = Calendar.getInstance().apply { time = date }
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    val minute = calendar.get(Calendar.MINUTE)
    val timeString = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
    MessageTimeText(text = timeString, modifier = modifier.alpha(0.6f))
}

@Composable
private fun MessageTimeText(text: String, modifier: Modifier = Modifier) { // Renamed to avoid conflict
    Text(
        text,
        style = MaterialTheme.typography.caption,
        maxLines = 1,
        modifier = modifier
    )
}

@Composable
private fun MessageSendingState(
    sendingState: TdApi.MessageSendingState?,
    modifier: Modifier = Modifier
) {
    when (sendingState?.constructor) { // Safe call for constructor
        TdApi.MessageSendingStatePending.CONSTRUCTOR -> {
            Icon(
                imageVector = Icons.Outlined.Pending,
                contentDescription = "Pending", // Added content description
                modifier = modifier
            )
        }
        TdApi.MessageSendingStateFailed.CONSTRUCTOR -> {
            Icon(
                imageVector = Icons.Outlined.SyncProblem,
                contentDescription = "Failed", // Added content description
                modifier = modifier
            )
        }
        null -> { // Handle null sendingState (e.g. for incoming messages if this composable was misused)
             Icon(
                imageVector = Icons.Outlined.Done, // Default for received messages
                contentDescription = "Sent/Read",
                modifier = modifier
            )
        }
        else -> { // Default for successfully sent/read messages
            Icon(
                imageVector = Icons.Outlined.Done,
                contentDescription = "Sent/Read", // Added content description
                modifier = modifier
            )
        }
    }
}
