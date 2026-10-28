package org.crisisnetwork.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.ByteBudget
import org.crisisnetwork.android.messaging.Messaging
import org.crisisnetwork.android.messaging.SendResult
import org.crisisnetwork.android.ui.components.MeshOffNotice
import org.crisisnetwork.android.ui.components.MessageBubble
import org.crisisnetwork.android.ui.components.QuietButton
import org.crisisnetwork.android.ui.components.ScreenTopBar
import org.crisisnetwork.android.ui.components.UnreadDivider
import org.crisisnetwork.android.ui.components.displayName
import uniffi.crisis_core.TrustLevel

/**
 * One private conversation. The newest messages are at the bottom; scrolling up loads older pages.
 * While the screen is open, incoming messages are marked read at once. The box at the bottom counts
 * the bytes of the text (UTF-8, not characters) against what a private message can carry.
 */
@Composable
fun ChatScreen(
    peerHex: String,
    onBack: () -> Unit,
    onOpenContact: (String) -> Unit,
) {
    val chat by Messaging.chat.collectAsState()
    val ready by Messaging.ready.collectAsState()
    val names by Messaging.names.collectAsState()
    val contacts by Messaging.contacts.collectAsState()
    val limits by Messaging.limits.collectAsState()

    DisposableEffect(peerHex) {
        Messaging.openChat(peerHex)
        onDispose { Messaging.closeChat() }
    }

    val contact = contacts.firstOrNull { it.hex == peerHex }
    val blocked = contact?.trust == TrustLevel.BLOCKED
    val hasCard = contact?.hasCard == true
    val canWrite = ready && hasCard && !blocked

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = displayName(peerHex, names),
            onBack = onBack,
            trailing = { QuietButton(text = stringResource(R.string.chat_details), onClick = { onOpenContact(peerHex) }) },
        )
        if (!ready) MeshOffNotice(modifier = Modifier.padding(horizontal = 16.dp))

        val listState = rememberLazyListState()
        val newest = chat.messages.lastOrNull()?.seq
        // Newest at the bottom: follow new messages while the reader is at the bottom.
        LaunchedEffect(newest) {
            if (listState.firstVisibleItemIndex <= 1) listState.animateScrollToItem(0)
        }
        val shown = remember(chat.messages) { chat.messages.asReversed() }

        Column(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (chat.loaded && shown.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.chat_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
                items(shown, key = { it.seq }) { message ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (message.seq == chat.firstUnreadSeq) UnreadDivider()
                        MessageBubble(message = message)
                    }
                }
                if (chat.hasOlder) {
                    item(key = "older") {
                        // Reaching the top of the list asks for the next older page; a longer list asks again.
                        LaunchedEffect(chat.messages.size) { Messaging.loadOlderChat() }
                        Text(
                            stringResource(R.string.loading_older),
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                        )
                    }
                }
            }
        }

        Composer(
            peerHex = peerHex,
            enabled = canWrite,
            limit = ByteBudget.directLimit(limits.maxPayloadBytes),
            note =
                when {
                    blocked -> R.string.chat_blocked
                    !hasCard -> R.string.chat_no_card
                    else -> null
                },
        )
    }
}

@Composable
private fun Composer(
    peerHex: String,
    enabled: Boolean,
    limit: Int,
    note: Int?,
) {
    var text by rememberSaveable(peerHex) { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val count = ByteBudget.count(text, limit)
    val canSend = enabled && text.isNotBlank() && !count.over

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (note != null) {
            Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                label = { Text(stringResource(R.string.chat_input_hint)) },
                maxLines = 5,
                isError = count.over,
                supportingText = {
                    val counter = stringResource(R.string.bytes_counter, count.used, count.limit)
                    Text(if (count.over) counter + " · " + stringResource(R.string.bytes_over) else counter)
                },
            )
            Button(
                onClick = {
                    val sending = text
                    scope.launch {
                        when (val result = Messaging.sendDirect(peerHex, sending)) {
                            SendResult.Sent -> {
                                // Keep what the person typed meanwhile.
                                if (text == sending) text = ""
                                error = null
                            }
                            else -> error = errorFor(result)
                        }
                    }
                },
                enabled = canSend,
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                Text(stringResource(R.string.chat_send))
            }
        }
    }
}

/** The words for a message that was not sent. */
internal fun errorFor(result: SendResult): Int =
    when (result) {
        SendResult.Sent -> R.string.send_err_failed
        SendResult.Empty -> R.string.send_err_empty
        SendResult.TooLong -> R.string.bytes_over
        SendResult.MeshOff -> R.string.send_err_mesh_off
        SendResult.UnknownContact -> R.string.send_err_unknown_contact
        is SendResult.Rejected -> R.string.send_err_rejected
        is SendResult.Failed -> R.string.send_err_failed
    }
