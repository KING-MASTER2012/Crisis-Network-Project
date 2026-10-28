package org.crisisnetwork.android.ui.contacts

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.CardCodec
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.ScreenColumn
import org.crisisnetwork.android.ui.components.ScreenTopBar
import org.crisisnetwork.android.ui.components.SecondaryButton

/** Entering a card as text: for phones without a camera, and for cards that were sent as a message. */
@Composable
fun PasteCardScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var card by remember { mutableStateOf<ByteArray?>(null) }
    var invalid by remember { mutableStateOf(false) }

    Column {
        ScreenTopBar(title = stringResource(R.string.paste_title), onBack = onBack)
        ScreenColumn {
            val ready = card
            if (ready != null) {
                AddContactForm(card = ready, onDone = onDone, onCancel = { card = null })
            } else {
                Text(stringResource(R.string.paste_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        invalid = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    isError = invalid,
                    label = { Text(stringResource(R.string.paste_field)) },
                )
                if (invalid) Text(stringResource(R.string.paste_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                SecondaryButton(
                    text = stringResource(R.string.paste_clipboard),
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        val clip = clipboard?.primaryClip
                        val pasted = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(context)?.toString() else null
                        if (pasted != null) {
                            text = pasted
                            invalid = false
                        }
                    },
                )
                PrimaryButton(
                    text = stringResource(R.string.paste_continue),
                    enabled = text.isNotBlank(),
                    onClick = {
                        val bytes = CardCodec.decode(text)
                        if (bytes == null) invalid = true else card = bytes
                    },
                )
            }
        }
    }
}
