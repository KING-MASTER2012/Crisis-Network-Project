package org.crisisnetwork.android.ui.shell

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.crisisnetwork.android.R

/** The top-level places of the app. Phones show them in a bar at the bottom, larger screens in a rail at the side. */
enum class Destination(val titleRes: Int, val iconRes: Int) {
    Home(R.string.nav_home, R.drawable.ic_nav_home),
    Chats(R.string.nav_chats, R.drawable.ic_nav_chat),
    Announcements(R.string.nav_announcements, R.drawable.ic_nav_announcements),
    Contacts(R.string.nav_contacts, R.drawable.ic_nav_people),
    Settings(R.string.nav_settings, R.drawable.ic_nav_settings),
}

/**
 * Window width classes in the sense of the Material guidelines: compact below 600 dp (phones),
 * medium up to 840 dp (large phones, small tablets, foldables), expanded beyond. Computed from the
 * width the shell is given, not from the screen: it follows split-screen and unfolding.
 */
enum class WidthClass {
    Compact,
    Medium,
    Expanded,
    ;

    companion object {
        const val MEDIUM_MIN_DP = 600f
        const val EXPANDED_MIN_DP = 840f

        fun of(widthDp: Float): WidthClass =
            when {
                widthDp < MEDIUM_MIN_DP -> Compact
                widthDp < EXPANDED_MIN_DP -> Medium
                else -> Expanded
            }
    }
}

/**
 * The frame around the screens: a navigation bar at the bottom on compact windows, a navigation
 * rail at the start on wider ones (with labels on expanded ones). Insets are handled here, so the
 * screens inside only worry about their content.
 */
@Composable
fun AdaptiveShell(
    current: Destination,
    onNavigate: (Destination) -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        when (WidthClass.of(maxWidth.value)) {
            WidthClass.Compact ->
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            for (destination in Destination.entries) {
                                NavigationBarItem(
                                    selected = destination == current,
                                    onClick = { onNavigate(destination) },
                                    icon = { Icon(painter = painterResource(destination.iconRes), contentDescription = null) },
                                    label = { Text(stringResource(destination.titleRes), maxLines = 1) },
                                    alwaysShowLabel = true,
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) { content() }
                }

            WidthClass.Medium, WidthClass.Expanded ->
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail {
                        for (destination in Destination.entries) {
                            NavigationRailItem(
                                selected = destination == current,
                                onClick = { onNavigate(destination) },
                                icon = { Icon(painter = painterResource(destination.iconRes), contentDescription = null) },
                                label = { Text(stringResource(destination.titleRes), maxLines = 1) },
                                alwaysShowLabel = true,
                            )
                        }
                    }
                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Bottom + WindowInsetsSides.End),
                                ),
                    ) { content() }
                }
        }
    }
}
