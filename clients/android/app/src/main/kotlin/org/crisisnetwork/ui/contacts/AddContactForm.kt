package org.crisisnetwork.android.ui.contacts

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.launch
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.AddResult
import org.crisisnetwork.android.messaging.Messaging
import org.crisisnetwork.android.messaging.NicknameStore
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.QuietButton
import org.crisisnetwork.android.ui.components.Tone
import org.crisisnetwork.android.ui.onboarding.OnboardingFlow
import uniffi.crisis_core.inspectContactCard

/**
 * What happens after a card was read (from a QR code or typed): show whose it is, with the safety
 * number to compare, ask for an optional nickname and add the contact. When the mesh is off the
 * card is kept and added as soon as the node runs.
 */
@Composable
fun AddContactForm(
    card: ByteArray,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val details = remember(card) { runCatching { inspectContactCard(card) }.getOrNull() }
    var nickname by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<AddResult?>(null) }
    val scope = rememberCoroutineScope()

    Column {
        if (details == null) {
            InfoCard(title = stringResource(R.string.add_title), tone = Tone.Error) {
                Text(stringResource(R.string.add_invalid), style = MaterialTheme.typography.bodyMedium)
            }
            QuietButton(text = stringResource(R.string.add_cancel), onClick = onCancel)
            return@Column
        }

        val outcome = result
        if (outcome is AddResult.Added || outcome is AddResult.Queued) {
            InfoCard(title = stringResource(R.string.add_title), tone = Tone.Success) {
                Text(
                    stringResource(if (outcome is AddResult.Queued) R.string.add_queued else R.string.add_done),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            PrimaryButton(text = stringResource(R.string.add_finish), onClick = onDone)
            return@Column
        }

        InfoCard(title = stringResource(R.string.add_title)) {
            Text(stringResource(R.string.add_safety_label), style = MaterialTheme.typography.labelLarge)
            Text(
                OnboardingFlow.formatSafetyNumber(details.safetyNumber),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(stringResource(R.string.add_safety_hint), style = MaterialTheme.typography.bodySmall)
        }
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it.take(NicknameStore.MAX_LENGTH) },
            modifier = Modifier,
            singleLine = true,
            label = { Text(stringResource(R.string.add_nickname)) },
        )
        if (outcome != null) {
            Text(
                stringResource(
                    when (outcome) {
                        AddResult.OwnCard -> R.string.add_own
                        AddResult.Invalid -> R.string.add_invalid
                        else -> R.string.add_failed
                    },
                ),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        PrimaryButton(
            text = stringResource(R.string.add_confirm),
            onClick = { scope.launch { result = Messaging.addContact(card, nickname.takeIf { it.isNotBlank() }) } },
        )
        QuietButton(text = stringResource(R.string.add_cancel), onClick = onCancel)
    }
}
