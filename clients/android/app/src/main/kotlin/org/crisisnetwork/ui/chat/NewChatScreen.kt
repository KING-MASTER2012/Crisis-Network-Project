package org.crisisnetwork.android.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.Messaging
import org.crisisnetwork.android.ui.components.ListScreen
import org.crisisnetwork.android.ui.components.MeshOffNotice
import org.crisisnetwork.android.ui.components.ScreenTopBar
import org.crisisnetwork.android.ui.components.StatusPill
import org.crisisnetwork.android.ui.components.displayName
import org.crisisnetwork.android.ui.components.trustLabel
import org.crisisnetwork.android.ui.components.trustTone
import uniffi.crisis_core.TrustLevel

/** Choosing the contact to write to: only people whose card is stored (and who are not blocked) can be written to. */
@Composable
fun NewChatScreen(
    onBack: () -> Unit,
    onPick: (String) -> Unit,
) {
    val contacts by Messaging.contacts.collectAsState()
    val names by Messaging.names.collectAsState()
    val ready by Messaging.ready.collectAsState()
    val candidates = contacts.filter { it.hasCard && it.trust != TrustLevel.BLOCKED }

    Column {
        ScreenTopBar(title = stringResource(R.string.newchat_title), onBack = onBack)
        ListScreen {
            if (!ready) item(key = "off") { MeshOffNotice() }
            if (candidates.isEmpty()) {
                item(key = "none") {
                    Text(
                        stringResource(R.string.newchat_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(candidates, key = { it.hex }) { contact ->
                Card(
                    onClick = { onPick(contact.hex) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(displayName(contact.hex, names), style = MaterialTheme.typography.titleMedium)
                        StatusPill(text = trustLabel(contact.trust), tone = trustTone(contact.trust))
                    }
                }
            }
        }
    }
}
