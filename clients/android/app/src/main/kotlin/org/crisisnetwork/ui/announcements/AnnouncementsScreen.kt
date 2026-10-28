package org.crisisnetwork.android.ui.announcements

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.MessageRow
import org.crisisnetwork.android.messaging.Messaging
import org.crisisnetwork.android.messaging.Severity
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.ListScreen
import org.crisisnetwork.android.ui.components.MeshOffNotice
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.StatusPill
import org.crisisnetwork.android.ui.components.UnreadDivider
import org.crisisnetwork.android.ui.components.displayName
import org.crisisnetwork.android.ui.components.formatTime
import org.crisisnetwork.android.ui.components.severityLabel
import org.crisisnetwork.android.ui.components.severityTone
import org.crisisnetwork.android.ui.components.statusLabel

/**
 * The public announcements this phone holds, the newest first, and the button to write one. Announcements
 * marked important or emergency get their own colour, and always a word as well. While the screen is
 * open, the announcements count as read.
 */
@Composable
fun AnnouncementsScreen(onWrite: () -> Unit) {
    val feed by Messaging.feed.collectAsState()
    val ready by Messaging.ready.collectAsState()
    val names by Messaging.names.collectAsState()

    DisposableEffect(Unit) {
        Messaging.openFeed()
        onDispose { Messaging.closeFeed() }
    }

    ListScreen {
        item(key = "title") {
            Text(
                stringResource(R.string.nav_announcements),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (!ready) item(key = "off") { MeshOffNotice() }
        item(key = "write") { PrimaryButton(text = stringResource(R.string.ann_write), onClick = onWrite) }
        if (feed.loaded && feed.messages.isEmpty()) {
            item(key = "empty") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.announcements_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.announcements_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(feed.messages, key = { it.seq }) { message ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AnnouncementCard(message = message, sender = message.senderHex?.let { displayName(it, names) })
                // The newest feed is read from the top: the line sits under the oldest message that was unread.
                if (message.seq == feed.firstUnreadSeq) UnreadDivider()
            }
        }
        if (feed.hasOlder) {
            item(key = "older") {
                LaunchedEffect(feed.messages.size) { Messaging.loadOlderFeed() }
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

@Composable
private fun AnnouncementCard(message: MessageRow, sender: String?) {
    InfoCard(tone = severityTone(message.severity)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (message.severity != Severity.Normal) {
                StatusPill(text = severityLabel(message.severity), tone = severityTone(message.severity))
            }
            Text(
                text = sender ?: stringResource(R.string.you),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(formatTime(message.timeMs), style = MaterialTheme.typography.labelMedium)
        }
        val text = message.text
        if (text == null) {
            Text(stringResource(R.string.msg_unreadable), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
        } else {
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
        }
        message.status?.let { Text(statusLabel(it), style = MaterialTheme.typography.labelSmall) }
    }
}
