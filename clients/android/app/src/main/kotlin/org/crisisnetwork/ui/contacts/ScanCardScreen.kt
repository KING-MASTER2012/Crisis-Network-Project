package org.crisisnetwork.android.ui.contacts

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.CardCodec
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.QuietButton
import org.crisisnetwork.android.ui.components.ScreenColumn
import org.crisisnetwork.android.ui.components.ScreenTopBar
import org.crisisnetwork.android.ui.components.SecondaryButton

/**
 * Reading the QR code of another phone. The camera is asked for only here, with the reason said first;
 * without it (or without a camera) the card can still be entered as text.
 */
@Composable
fun ScanCardScreen(
    onBack: () -> Unit,
    onEnterText: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val hasCamera = remember { context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var asked by remember { mutableStateOf(false) }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
            granted = result
            asked = true
        }
    var scanned by remember { mutableStateOf<ByteArray?>(null) }
    var notACard by remember { mutableStateOf(false) }

    Column {
        ScreenTopBar(title = stringResource(R.string.scan_title), onBack = onBack)
        val card = scanned
        when {
            card != null ->
                ScreenColumn {
                    AddContactForm(card = card, onDone = onDone, onCancel = { scanned = null })
                }

            hasCamera && granted ->
                Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                    QrCameraPreview(
                        onText = { text ->
                            val bytes = CardCodec.decode(text)
                            if (bytes != null) scanned = bytes else notACard = true
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)),
                    )
                    Text(
                        stringResource(if (notACard) R.string.scan_invalid else R.string.scan_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (notACard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    SecondaryButton(text = stringResource(R.string.scan_use_text), onClick = onEnterText)
                }

            else ->
                ScreenColumn {
                    InfoCard(title = stringResource(R.string.scan_permission_title)) {
                        Text(
                            stringResource(if (!hasCamera) R.string.scan_no_camera else if (asked) R.string.scan_denied_body else R.string.scan_permission_body),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (hasCamera && !asked) {
                            PrimaryButton(
                                text = stringResource(R.string.scan_allow),
                                onClick = { launcher.launch(Manifest.permission.CAMERA) },
                            )
                        }
                    }
                    SecondaryButton(text = stringResource(R.string.scan_use_text), onClick = onEnterText)
                    QuietButton(text = stringResource(R.string.add_cancel), onClick = onBack)
                }
        }
    }
}
