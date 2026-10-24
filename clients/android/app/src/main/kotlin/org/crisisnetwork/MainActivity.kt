package org.crisisnetwork.android

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import org.crisisnetwork.android.ui.UiPreferences
import org.crisisnetwork.android.ui.onboarding.OnboardingScreen
import org.crisisnetwork.android.ui.screens.HomeScreen
import org.crisisnetwork.android.ui.screens.PlaceholderScreen
import org.crisisnetwork.android.ui.screens.SettingsScreen
import org.crisisnetwork.android.ui.shell.AdaptiveShell
import org.crisisnetwork.android.ui.shell.Destination
import org.crisisnetwork.android.ui.theme.CrisisTheme

/**
 * The single activity of the app (phase A4): the theme, then either the introduction (first
 * launch) or the shell with its destinations. The A0 self-test that used to live here is now in
 * the settings, under the developer tools of debug builds.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first frame: the theme must not flash from the default to the stored choice.
        UiPreferences.load(this)
        MeshController.load(this)
        enableEdgeToEdge()
        setContent {
            val ui by UiPreferences.state.collectAsState()
            val dark = ui.theme.isDark(isSystemInDarkTheme())

            // The status and navigation bar icons follow the app's own day/night choice, not only the system's.
            LaunchedEffect(dark) {
                val style =
                    if (dark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                this@MainActivity.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            CrisisTheme(ui.theme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (ui.onboardingDone) {
                        MainShell()
                    } else {
                        val context = LocalContext.current
                        OnboardingScreen(onFinished = { UiPreferences.completeOnboarding(context) })
                    }
                }
            }
        }
    }
}

/** The destinations behind the navigation bar (phones) or rail (tablets). */
@Composable
private fun MainShell() {
    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    BackHandler(enabled = destination != Destination.Home) { destination = Destination.Home }

    AdaptiveShell(current = destination, onNavigate = { destination = it }) {
        when (destination) {
            Destination.Home -> HomeScreen()
            Destination.Chats -> PlaceholderScreen(R.string.chats_empty_title, R.string.chats_empty_body)
            Destination.Announcements -> PlaceholderScreen(R.string.announcements_empty_title, R.string.announcements_empty_body)
            Destination.Contacts -> PlaceholderScreen(R.string.contacts_empty_title, R.string.contacts_empty_body)
            Destination.Settings -> SettingsScreen()
        }
    }
}
