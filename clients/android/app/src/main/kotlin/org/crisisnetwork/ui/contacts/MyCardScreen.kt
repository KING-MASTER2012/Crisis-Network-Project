package org.crisisnetwork.android.ui.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.crisisnetwork.android.R
import org.crisisnetwork.android.ui.UiPreferences
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.ScreenColumn
import org.crisisnetwork.android.ui.components.ScreenTopBar
import org.crisisnetwork.android.ui.components.SecondaryButton
import org.crisisnetwork.android.ui.components.Tone
import org.crisisnetwork.android.ui.onboarding.OnboardingFlow

/**
 * The user's own contact card as a QR code, for the other person to scan, plus the same card as text
 * to copy or share when a camera is not an option. A card holds only public data: the public key
 * and its signature. Anyone who has it can write to you, which is why people meet to exchange it.
 */
@Composable
fun MyCardScreen(onBack: () -> Unit) {
    val ui by UiPreferences.state.collectAsState()
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }
    val card = ui.cardText
    val cardTitle = stringResource(R.string.mycard_title)
    val shareTitle = stringResource(R.string.mycard_share)

    Column {
        ScreenTopBar(title = stringResource(R.string.mycard_title), onBack = onBack)
        ScreenColumn {
            if (card == null) {
                InfoCard(title = stringResource(R.string.mycard_title), tone = Tone.Warning) {
                    Text(stringResource(R.string.mycard_unavailable), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                InfoCard(title = stringResource(R.string.mycard_title)) {
                    Text(stringResource(R.string.mycard_body), style = MaterialTheme.typography.bodyMedium)
                    QrCodeView(
                        text = card,
                        description = stringResource(R.string.mycard_qr_desc),
                        modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().align(Alignment.CenterHorizontally),
                    )
                    ui.safetyNumber?.let { number ->
                        Text(stringResource(R.string.add_safety_label), style = MaterialTheme.typography.labelLarge)
                        Text(
                            OnboardingFlow.formatSafetyNumber(number),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                SecondaryButton(
                    text = stringResource(R.string.mycard_copy),
                    onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText(cardTitle, card))
                        copied = true
                    },
                )
                if (copied) Text(stringResource(R.string.mycard_copied), style = MaterialTheme.typography.bodySmall)
                SecondaryButton(
                    text = stringResource(R.string.mycard_share),
                    onClick = {
                        val send =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, card)
                            }
                        context.startActivity(Intent.createChooser(send, shareTitle))
                    },
                )
            }
        }
    }
}
