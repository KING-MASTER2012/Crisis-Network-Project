package org.crisisnetwork.android.ui.contacts

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.Messaging
import org.crisisnetwork.android.ui.components.ListScreen
import org.crisisnetwork.android.ui.components.MeshOffNotice
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.QuietButton
import org.crisisnetwork.android.ui.components.SecondaryButton
import org.crisisnetwork.android.ui.components.StatusPill
import org.crisisnetwork.android.ui.components.Tone
import org.crisisnetwork.android.ui.components.displayName
import org.crisisnetwork.android.ui.components.trustLabel
import org.crisisnetwork.android.ui.components.trustTone

/** The people this phone knows, and the ways to add one: show your own card, scan theirs, or enter it as text. */
@Composable
fun ContactsScreen(
    onOpenContact: (String) -> Unit,
    onShowMyCard: () -> Unit,
    onScan: () -> Unit,
    onEnterText: () -> Unit,
) {
    val contacts by Messaging.contacts.collectAsState()
    val names by Messaging.names.collectAsState()
    val ready by Messaging.ready.collectAsState()

    ListScreen {
        item(key = "title") {
            Text(
                stringResource(R.string.nav_contacts),
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { heading() },
            )
        }
        item(key = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton(text = stringResource(R.string.contacts_my_card), onClick = onShowMyCard)
                SecondaryButton(text = stringResource(R.string.contacts_scan), onClick = onScan)
                QuietButton(text = stringResource(R.string.contacts_paste), onClick = onEnterText)
            }
        }
        if (!ready) item(key = "off") { MeshOffNotice() }
        if (contacts.isEmpty()) {
            item(key = "empty") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.contacts_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.contacts_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(contacts, key = { it.hex }) { contact ->
            Card(
                onClick = { onOpenContact(contact.hex) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        displayName(contact.hex, names),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(text = trustLabel(contact.trust), tone = trustTone(contact.trust))
                        if (!contact.hasCard) StatusPill(text = stringResource(R.string.contact_no_card_pill), tone = Tone.Warning)
                    }
                }
            }
        }
    }
}
