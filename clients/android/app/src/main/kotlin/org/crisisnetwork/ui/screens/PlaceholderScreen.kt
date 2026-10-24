package org.crisisnetwork.android.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import org.crisisnetwork.android.ui.components.EmptyState

/** A destination whose content arrives with a later phase (chats, announcements, contacts: A5). */
@Composable
fun PlaceholderScreen(titleRes: Int, bodyRes: Int, modifier: Modifier = Modifier) {
    EmptyState(title = stringResource(titleRes), body = stringResource(bodyRes), modifier = modifier)
}
