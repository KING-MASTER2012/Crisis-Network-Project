package org.crisisnetwork.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.crisisnetwork.android.ui.theme.statusColors

// The building blocks of every screen (phase A4). Rules they follow: touch targets of at least
// 48 dp, text that wraps instead of being cut off at large font sizes, a role and a state for
// screen readers (TalkBack), and meaning that never depends on colour alone.

/** Widest a column of content grows: lines stay readable on tablets and in landscape. */
private val ContentMaxWidth = 640.dp

/** The standard screen: scrolls, is centred and limited in width, with the usual padding. */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = ContentMaxWidth)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

/** The title of a screen; announced as a heading. */
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.semantics { heading() },
    )
}

/** A heading inside a screen. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 8.dp).semantics { heading() },
    )
}

/** What a card or a label is about. Each tone also has a word or icon where it is used. */
enum class Tone { Neutral, Success, Warning, Emergency, Error }

@Composable
private fun toneContainer(tone: Tone): androidx.compose.ui.graphics.Color =
    when (tone) {
        Tone.Neutral -> MaterialTheme.colorScheme.surfaceContainer
        Tone.Success -> MaterialTheme.statusColors.successContainer
        Tone.Warning -> MaterialTheme.statusColors.warningContainer
        Tone.Emergency -> MaterialTheme.statusColors.emergencyContainer
        Tone.Error -> MaterialTheme.colorScheme.errorContainer
    }

@Composable
private fun toneContent(tone: Tone): androidx.compose.ui.graphics.Color =
    when (tone) {
        Tone.Neutral -> MaterialTheme.colorScheme.onSurface
        Tone.Success -> MaterialTheme.statusColors.onSuccessContainer
        Tone.Warning -> MaterialTheme.statusColors.onWarningContainer
        Tone.Emergency -> MaterialTheme.statusColors.onEmergencyContainer
        Tone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }

/** A card with an optional title; the body is whatever [content] places in it. */
@Composable
fun InfoCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    tone: Tone = Tone.Neutral,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = toneContainer(tone), contentColor = toneContent(tone)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            }
            content()
        }
    }
}

/** The main action of a screen or step: full width, at least 52 dp high. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(text = text, textAlign = TextAlign.Center)
    }
}

/** A secondary action: outlined, same size as [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(text = text, textAlign = TextAlign.Center)
    }
}

/** A quiet action, for "skip" and "not now". */
@Composable
fun QuietButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(text = text, textAlign = TextAlign.Center)
    }
}

/**
 * A small label with a state in words ("Connected"), tinted by [tone]. A change of the text is
 * announced by screen readers (a polite live region), so that "Searching" becoming "Connected" is heard.
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    tone: Tone = Tone.Neutral,
) {
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(50),
        color = toneContainer(tone),
        contentColor = toneContent(tone),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

/** What a list shows while it is empty, or a screen that is not built yet. */
@Composable
fun EmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp),
        )
    }
}

/** A setting that is on or off. The whole row is the switch, so the target is large and TalkBack reads one item. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
                .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * One choice out of a few, as a list of radio rows. Rows instead of chips: they stay readable and
 * easy to hit at the largest text size.
 */
@Composable
fun <T> ChoiceGroup(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // selectableGroup(): a screen reader says that the rows belong together ("radio group") and how many there are.
    Column(modifier = modifier.fillMaxWidth().selectableGroup()) {
        for (option in options) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = option == selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RadioButton(selected = option == selected, onClick = null, enabled = enabled)
                Text(text = label(option), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
