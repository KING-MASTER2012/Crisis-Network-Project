package org.crisisnetwork.android.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.Messaging
import org.crisisnetwork.android.messaging.Names
import org.crisisnetwork.android.messaging.NicknameStore
import org.crisisnetwork.android.ui.UiPreferences
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.ScreenColumn
import org.crisisnetwork.android.ui.components.ScreenTopBar
import org.crisisnetwork.android.ui.components.SecondaryButton
import org.crisisnetwork.android.ui.components.StatusPill
import org.crisisnetwork.android.ui.components.Tone
import org.crisisnetwork.android.ui.components.displayName
import org.crisisnetwork.android.ui.components.trustLabel
import org.crisisnetwork.android.ui.components.trustTone
import org.crisisnetwork.android.ui.onboarding.OnboardingFlow
import uniffi.crisis_core.TrustLevel

/**
 * One contact: the nickname, the trust level and the comparison of safety numbers. Two people who
 * read their numbers to each other and find them identical know that nobody replaced a card on the
 * way; only then does the contact count as verified. Changing the trust needs the node, so those
 * buttons wait for the mesh.
 */
@Composable
fun ContactDetailScreen(
    peerHex: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
) {
    val contacts by Messaging.contacts.collectAsState()
    val names by Messaging.names.collectAsState()
    val ready by Messaging.ready.collectAsState()
    val ui by UiPreferences.state.collectAsState()
    val scope = rememberCoroutineScope()

    val contact = contacts.firstOrNull { it.hex == peerHex }
    var nickname by rememberSaveable(peerHex) { mutableStateOf(names[peerHex].orEmpty()) }
    var confirmBlock by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    fun change(trust: TrustLevel) {
        scope.launch { failed = !Messaging.setTrust(peerHex, trust) }
    }

    Column {
        ScreenTopBar(title = displayName(peerHex, names), onBack = onBack)
        ScreenColumn {
            InfoCard(title = stringResource(R.string.contact_nickname)) {
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it.take(NicknameStore.MAX_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.contact_nickname)) },
                )
                SecondaryButton(text = stringResource(R.string.contact_save), onClick = { Messaging.setNickname(peerHex, nickname) })
                Text(
                    stringResource(R.string.contact_id_line, Names.shortId(peerHex)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }

            if (contact == null) {
                InfoCard(tone = Tone.Warning) {
                    Text(stringResource(R.string.contact_unknown_hint), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                InfoCard(title = stringResource(R.string.contact_trust_title)) {
                    StatusPill(text = trustLabel(contact.trust), tone = trustTone(contact.trust))
                    if (contact.trust == TrustLevel.BLOCKED) {
                        Text(stringResource(R.string.contact_block_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }

                InfoCard(title = stringResource(R.string.contact_safety_title)) {
                    val theirs = contact.safetyNumber
                    if (theirs == null) {
                        Text(stringResource(R.string.contact_no_card_hint), style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(stringResource(R.string.contact_safety_theirs), style = MaterialTheme.typography.labelLarge)
                        Text(OnboardingFlow.formatSafetyNumber(theirs), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                        ui.safetyNumber?.let { own ->
                            Text(stringResource(R.string.contact_safety_yours), style = MaterialTheme.typography.labelLarge)
                            Text(OnboardingFlow.formatSafetyNumber(own), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
                        }
                        Text(stringResource(R.string.contact_safety_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (!ready) Text(stringResource(R.string.contact_needs_mesh), style = MaterialTheme.typography.bodyMedium)
                if (failed) Text(stringResource(R.string.contact_change_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

                if (contact.hasCard && contact.trust != TrustLevel.BLOCKED) {
                    PrimaryButton(text = stringResource(R.string.contact_message), onClick = { onMessage(peerHex) })
                }
                when (contact.trust) {
                    TrustLevel.UNKNOWN ->
                        if (contact.hasCard) {
                            SecondaryButton(text = stringResource(R.string.contact_verify), enabled = ready, onClick = { change(TrustLevel.VERIFIED) })
                        }
                    TrustLevel.VERIFIED ->
                        SecondaryButton(text = stringResource(R.string.contact_unverify), enabled = ready, onClick = { change(TrustLevel.UNKNOWN) })
                    TrustLevel.BLOCKED ->
                        SecondaryButton(text = stringResource(R.string.contact_unblock), enabled = ready, onClick = { change(TrustLevel.UNKNOWN) })
                }
                if (contact.trust != TrustLevel.BLOCKED) {
                    SecondaryButton(text = stringResource(R.string.contact_block), enabled = ready, onClick = { confirmBlock = true })
                }
            }
        }
    }

    if (confirmBlock) {
        AlertDialog(
            onDismissRequest = { confirmBlock = false },
            title = { Text(stringResource(R.string.contact_block_title)) },
            text = { Text(stringResource(R.string.contact_block_hint)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmBlock = false
                    change(TrustLevel.BLOCKED)
                }) { Text(stringResource(R.string.contact_block)) }
            },
            dismissButton = { TextButton(onClick = { confirmBlock = false }) { Text(stringResource(R.string.add_cancel)) } },
        )
    }
}
