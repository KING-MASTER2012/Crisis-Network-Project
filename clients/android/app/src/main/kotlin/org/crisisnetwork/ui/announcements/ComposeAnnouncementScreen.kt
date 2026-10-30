package org.crisisnetwork.android.ui.announcements

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.launch
import org.crisisnetwork.android.MeshController
import org.crisisnetwork.android.R
import org.crisisnetwork.android.messaging.AnnouncementCodec
import org.crisisnetwork.android.messaging.ByteBudget
import org.crisisnetwork.android.messaging.LifetimeUnit
import org.crisisnetwork.android.messaging.Lifetimes
import org.crisisnetwork.android.messaging.Messaging
import org.crisisnetwork.android.messaging.SendResult
import org.crisisnetwork.android.messaging.Severity
import org.crisisnetwork.android.ui.chat.errorFor
import org.crisisnetwork.android.ui.components.ChoiceGroup
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.MeshOffNotice
import org.crisisnetwork.android.ui.components.PrimaryButton
import org.crisisnetwork.android.ui.components.ScreenColumn
import org.crisisnetwork.android.ui.components.ScreenTopBar
import org.crisisnetwork.android.ui.components.SectionTitle
import org.crisisnetwork.android.ui.components.Tone
import org.crisisnetwork.android.ui.components.severityLabel

/** Marks the "custom" row of the lifetime choice. */
private const val CUSTOM = -1L

/**
 * Writing a public announcement: the text (with a byte counter), how important it is, and how long
 * phones keep it, from one second up to the longest the core allows. Important and emergency
 * announcements ask for a confirmation first, because they cannot be taken back.
 */
@Composable
fun ComposeAnnouncementScreen(
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val limits by Messaging.limits.collectAsState()
    val ready by Messaging.ready.collectAsState()
    val scope = rememberCoroutineScope()

    var text by rememberSaveable { mutableStateOf("") }
    var severity by rememberSaveable { mutableStateOf(Severity.Normal) }
    // The lifetime the person chose in the settings is the one that is preselected.
    var choice by rememberSaveable { mutableStateOf(MeshController.state.value.defaultLifetimeSecs) }
    var customValue by rememberSaveable { mutableStateOf("1") }
    var customUnit by rememberSaveable { mutableStateOf(LifetimeUnit.Hours) }
    var error by remember { mutableStateOf<Int?>(null) }
    var confirm by remember { mutableStateOf(false) }

    val lifetimeSecs = if (choice == CUSTOM) Lifetimes.toSeconds(customValue.toLongOrNull() ?: 0L, customUnit) else choice
    val lifetimeValid = Lifetimes.isValid(lifetimeSecs, limits.maxLifetimeSecs)
    val payloadBytes = AnnouncementCodec.encode(text.trim(), severity).size
    val count = ByteBudget.Count(payloadBytes, limits.maxPayloadBytes)
    val canPublish = ready && text.isNotBlank() && !count.over && lifetimeValid

    fun publish() {
        scope.launch {
            when (val result = Messaging.publishAnnouncement(text, severity, lifetimeSecs)) {
                SendResult.Sent -> onDone()
                else -> error = errorFor(result)
            }
        }
    }

    Column {
        ScreenTopBar(title = stringResource(R.string.ann_compose_title), onBack = onBack)
        ScreenColumn {
            if (!ready) MeshOffNotice()
            InfoCard(tone = Tone.Warning) {
                Text(stringResource(R.string.ann_public_note), style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                isError = count.over,
                label = { Text(stringResource(R.string.ann_field)) },
                supportingText = {
                    val counter = stringResource(R.string.bytes_counter, count.used, count.limit)
                    Text(if (count.over) counter + " · " + stringResource(R.string.bytes_over) else counter)
                },
            )

            SectionTitle(stringResource(R.string.ann_severity_title))
            ChoiceGroup(
                options = Severity.entries,
                selected = severity,
                label = { severityLabel(it) },
                onSelect = { severity = it },
            )

            SectionTitle(stringResource(R.string.ann_lifetime_title))
            Text(stringResource(R.string.ann_lifetime_hint), style = MaterialTheme.typography.bodySmall)
            ChoiceGroup(
                options = Lifetimes.presets(limits.maxLifetimeSecs) + CUSTOM,
                selected = choice,
                label = { if (it == CUSTOM) stringResource(R.string.lifetime_custom) else durationLabel(it) },
                onSelect = { choice = it },
            )
            if (choice == CUSTOM) {
                OutlinedTextField(
                    value = customValue,
                    onValueChange = { customValue = it.filter(Char::isDigit).take(9) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.lifetime_custom_value)) },
                    isError = !lifetimeValid,
                )
                ChoiceGroup(
                    options = LifetimeUnit.entries,
                    selected = customUnit,
                    label = { unitLabel(it) },
                    onSelect = { customUnit = it },
                )
                if (!lifetimeValid) {
                    Text(
                        stringResource(R.string.ann_lifetime_invalid, durationLabel(limits.maxLifetimeSecs)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            PrimaryButton(
                text = stringResource(R.string.ann_publish),
                enabled = canPublish,
                onClick = { if (severity == Severity.Normal) publish() else confirm = true },
            )
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.ann_confirm_title, severityLabel(severity))) },
            text = { Text(stringResource(R.string.ann_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    publish()
                }) { Text(stringResource(R.string.ann_publish)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.add_cancel)) } },
        )
    }
}

/** "15 minutes", "1 day": a lifetime in the largest unit that divides it evenly. */
@Composable
fun durationLabel(secs: Long): String {
    val (amount, unit) = Lifetimes.split(secs)
    val n = amount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val plural =
        when (unit) {
            LifetimeUnit.Seconds -> R.plurals.duration_seconds
            LifetimeUnit.Minutes -> R.plurals.duration_minutes
            LifetimeUnit.Hours -> R.plurals.duration_hours
            LifetimeUnit.Days -> R.plurals.duration_days
        }
    return pluralStringResource(plural, n, n)
}

@Composable
private fun unitLabel(unit: LifetimeUnit): String =
    stringResource(
        when (unit) {
            LifetimeUnit.Seconds -> R.string.unit_seconds
            LifetimeUnit.Minutes -> R.string.unit_minutes
            LifetimeUnit.Hours -> R.string.unit_hours
            LifetimeUnit.Days -> R.string.unit_days
        },
    )
