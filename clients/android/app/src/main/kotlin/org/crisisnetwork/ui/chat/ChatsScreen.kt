package org.crisisnetwork.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.ChatRow
import org.crisisnetwork.android.messaging.Messaging
import org.crisisnetwork.android.ui.components.ListScreen
import org.crisisnetwork.android.ui.components.MeshOffNotice
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.StatusPill
import org.crisisnetwork.android.ui.components.Tone
import org.crisisnetwork.android.ui.components.displayName
import org.crisisnetwork.android.ui.components.formatTime

/** The private conversations, the one with the newest message first, with a preview and the unread count. */
@Composable
fun ChatsScreen(
    onOpenChat: (String) -> Unit,
    onNewChat: () -> Unit,
) {
    val chats by Messaging.chats.collectAsState()
    val ready by Messaging.ready.collectAsState()
    val names by Messaging.names.collectAsState()

    ListScreen {
        item(key = "title") {
            Text(
                stringResource(R.string.nav_chats),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (!ready) item(key = "off") { MeshOffNotice() }
        item(key = "new") { PrimaryButton(text = stringResource(R.string.chats_new), onClick = onNewChat) }
        if (chats.isEmpty()) {
            item(key = "empty") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                    Text(stringResource(R.string.chats_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.chats_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(chats, key = { it.peerHex }) { chat ->
                ChatListItem(chat = chat, name = displayName(chat.peerHex, names), onClick = { onOpenChat(chat.peerHex) })
            }
        }
    }
}

@Composable
private fun ChatListItem(chat: ChatRow, name: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (chat.unread > 0) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(formatTime(chat.lastTimeMs), style = MaterialTheme.typography.labelMedium)
            }
            val preview = chat.preview
            Text(
                text = when {
                    preview == null -> stringResource(R.string.msg_unreadable)
                    chat.lastOutgoing -> stringResource(R.string.chat_preview_mine, preview)
                    else -> preview
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (chat.unread > 0) {
                StatusPill(text = pluralStringResource(R.plurals.chat_unread, chat.unread, chat.unread), tone = Tone.Warning)
            }
        }
    }
}
