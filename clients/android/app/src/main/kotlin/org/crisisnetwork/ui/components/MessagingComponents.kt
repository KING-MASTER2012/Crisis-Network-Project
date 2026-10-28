package org.crisisnetwork.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.DeliveryStatus
import org.crisisnetwork.android.messaging.MessageRow
import org.crisisnetwork.android.messaging.Names
import org.crisisnetwork.android.messaging.Severity
import uniffi.crisis_core.TrustLevel

// Pieces shared by the messaging screens of phase A5 (chats, contacts, announcements).

/** The top of a screen that opens over the shell: a way back, the title and optional actions at the end. */
@Composable
fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QuietButton(text = stringResource(R.string.back), onClick = onBack)
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp).semantics { heading() },
        )
        trailing()
    }
}

/** A scrolling list screen, centred and limited in width like [ScreenColumn]. */
@Composable
fun ListScreen(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    reverse: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            state = state,
            reverseLayout = reverse,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Shown while the node is closed: messages can only be read and sent while the phone is part of the mesh. */
@Composable
fun MeshOffNotice(modifier: Modifier = Modifier) {
    InfoCard(modifier = modifier, title = stringResource(R.string.mesh_off_title), tone = Tone.Warning) {
        Text(stringResource(R.string.mesh_off_body), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun trustLabel(trust: TrustLevel): String =
    stringResource(
        when (trust) {
            TrustLevel.UNKNOWN -> R.string.trust_unknown
            TrustLevel.VERIFIED -> R.string.trust_verified
            TrustLevel.BLOCKED -> R.string.trust_blocked
        },
    )

fun trustTone(trust: TrustLevel): Tone =
    when (trust) {
        TrustLevel.UNKNOWN -> Tone.Neutral
        TrustLevel.VERIFIED -> Tone.Success
        TrustLevel.BLOCKED -> Tone.Error
    }

@Composable
fun severityLabel(severity: Severity): String =
    stringResource(
        when (severity) {
            Severity.Normal -> R.string.sev_normal
            Severity.Important -> R.string.sev_important
            Severity.Emergency -> R.string.sev_emergency
        },
    )

fun severityTone(severity: Severity): Tone =
    when (severity) {
        Severity.Normal -> Tone.Neutral
        Severity.Important -> Tone.Warning
        Severity.Emergency -> Tone.Emergency
    }

/** The nickname, or "Node ab12cd34" for someone who has none. */
@Composable
fun displayName(hex: String, names: Map<String, String>): String = names[hex] ?: stringResource(R.string.name_unknown_node, Names.shortId(hex))

@Composable
fun statusLabel(status: DeliveryStatus): String =
    stringResource(
        when (status) {
            DeliveryStatus.Published -> R.string.status_published
            DeliveryStatus.Delivered -> R.string.status_delivered
        },
    )

/** The time of a message: only the time for today, the date and the time for earlier days. */
fun formatTime(ms: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (ms <= 0L) return ""
    val moment = Instant.ofEpochMilli(ms).atZone(zone)
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    return if (moment.toLocalDate() == today) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(moment)
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(moment)
    }
}

/** The line above the first message that was unread when a conversation was opened. */
@Composable
fun UnreadDivider(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(stringResource(R.string.unread_divider), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * One message of a chat: mine at the end in the accent colour, theirs at the start. The text can be
 * selected and copied; the state of a sent message is written out ("Delivered"), never only coloured.
 */
@Composable
fun MessageBubble(
    message: MessageRow,
    modifier: Modifier = Modifier,
) {
    val outgoing = message.outgoing
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (outgoing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = if (outgoing) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val text = message.text
                if (text == null) {
                    Text(stringResource(R.string.msg_unreadable), style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                } else {
                    SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatTime(message.timeMs), style = MaterialTheme.typography.labelSmall)
                    message.status?.let { Text("· " + statusLabel(it), style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}
