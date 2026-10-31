package org.crisisnetwork.android

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import org.crisisnetwork.android.messaging.Messaging
import org.crisisnetwork.android.notifications.AppNotifications
import org.crisisnetwork.android.ui.ScreenProtection
import org.crisisnetwork.android.ui.UiPreferences
import org.crisisnetwork.android.ui.announcements.AnnouncementsScreen
import org.crisisnetwork.android.ui.announcements.ComposeAnnouncementScreen
import org.crisisnetwork.android.ui.chat.ChatScreen
import org.crisisnetwork.android.ui.chat.ChatsScreen
import org.crisisnetwork.android.ui.chat.NewChatScreen
import org.crisisnetwork.android.ui.contacts.ContactDetailScreen
import org.crisisnetwork.android.ui.contacts.ContactsScreen
import org.crisisnetwork.android.ui.contacts.MyCardScreen
import org.crisisnetwork.android.ui.contacts.PasteCardScreen
import org.crisisnetwork.android.ui.contacts.ScanCardScreen
import org.crisisnetwork.android.ui.network.NetworkScreen
import org.crisisnetwork.android.ui.onboarding.OnboardingScreen
import org.crisisnetwork.android.ui.screens.HomeScreen
import org.crisisnetwork.android.ui.screens.SettingsScreen
import org.crisisnetwork.android.ui.shell.AdaptiveShell
import org.crisisnetwork.android.ui.shell.Destination
import org.crisisnetwork.android.ui.shell.LaunchRequests
import org.crisisnetwork.android.ui.shell.LaunchTarget
import org.crisisnetwork.android.ui.shell.Overlay
import org.crisisnetwork.android.ui.shell.OverlayStack
import org.crisisnetwork.android.ui.theme.CrisisTheme

/**
 * The single activity of the app (phase A4): the theme, then either the introduction (first
 * launch) or the shell with its destinations. The A0 self-test that used to live here is now in
 * the settings, under the developer tools of debug builds.
 *
 * Phase A6: it tells [AppVisibility] when it is on screen, creates the notification channels and
 * turns a tap on a message notification into a [LaunchRequests] entry that the shell opens.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first frame: the theme must not flash from the default to the stored choice.
        UiPreferences.load(this)
        // Before the first frame as well: nothing of the first screen may reach a screenshot.
        ScreenProtection.apply(this, UiPreferences.state.value.screenProtection)
        MeshController.load(this)
        Messaging.start(this)
        AppNotifications.createChannels(this)
        // A rotation or a restart after process death delivers the same intent again: only a fresh start acts on it.
        if (savedInstanceState == null) handleLaunchIntent(intent)
        enableEdgeToEdge()
        setContent {
            val ui by UiPreferences.state.collectAsState()
            val dark = ui.theme.isDark(isSystemInDarkTheme())

            LaunchedEffect(ui.screenProtection) { ScreenProtection.apply(this@MainActivity, ui.screenProtection) }

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

    /** The activity was running already and a notification was tapped. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        AppVisibility.onStart()
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.onResume()
    }

    override fun onStop() {
        AppVisibility.onStop()
        super.onStop()
    }

    private fun handleLaunchIntent(intent: Intent?) {
        LaunchTarget.of(intent?.action, intent?.getStringExtra(LaunchTarget.EXTRA_PEER))?.let(LaunchRequests::post)
    }
}

/**
 * The destinations behind the navigation bar (phones) or rail (tablets), and the screens that open on top
 * of them (a chat, a contact, the QR screens, writing an announcement). Those form a stack that is saved
 * as text, so a rotation or a killed process brings the person back to the same screen.
 */
@Composable
private fun MainShell() {
    var destination by rememberSaveable { mutableStateOf(Destination.Home) }
    var savedStack by rememberSaveable { mutableStateOf("") }
    val stack = OverlayStack.decode(savedStack)
    val unread by Messaging.unread.collectAsState()
    val launch by LaunchRequests.pending.collectAsState()
    val top = stack.lastOrNull()

    fun navigate(next: List<Overlay>) {
        savedStack = OverlayStack.encode(next)
    }

    fun open(overlay: Overlay) = navigate(OverlayStack.push(stack, overlay))

    BackHandler(enabled = top != null) { navigate(OverlayStack.pop(stack)) }
    BackHandler(enabled = top == null && destination != Destination.Home) { destination = Destination.Home }

    // A tap on a notification: open that chat (or the announcements) from a clean start of the shell.
    LaunchedEffect(launch) {
        val target = launch ?: return@LaunchedEffect
        when (target) {
            is LaunchTarget.Chat -> {
                destination = Destination.Chats
                savedStack = OverlayStack.encode(OverlayStack.toChat(emptyList(), target.peerHex))
            }
            LaunchTarget.Announcements -> {
                destination = Destination.Announcements
                savedStack = ""
            }
        }
        LaunchRequests.consume(target)
    }

    if (top != null) {
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            OverlayScreen(top = top, stack = stack, navigate = ::navigate)
        }
        return
    }

    AdaptiveShell(
        current = destination,
        onNavigate = { destination = it },
        badges = mapOf(Destination.Chats to unread.chats, Destination.Announcements to unread.announcements),
    ) {
        when (destination) {
            Destination.Home -> HomeScreen(onOpenNetwork = { open(Overlay.Network) })
            Destination.Chats -> ChatsScreen(onOpenChat = { open(Overlay.Chat(it)) }, onNewChat = { open(Overlay.NewChat) })
            Destination.Announcements -> AnnouncementsScreen(onWrite = { open(Overlay.Compose) })
            Destination.Contacts ->
                ContactsScreen(
                    onOpenContact = { open(Overlay.Contact(it)) },
                    onShowMyCard = { open(Overlay.MyCard) },
                    onScan = { open(Overlay.Scan) },
                    onEnterText = { open(Overlay.Paste) },
                )
            Destination.Settings -> SettingsScreen(onOpenNetwork = { open(Overlay.Network) })
        }
    }
}

@Composable
private fun OverlayScreen(
    top: Overlay,
    stack: List<Overlay>,
    navigate: (List<Overlay>) -> Unit,
) {
    val back: () -> Unit = { navigate(OverlayStack.pop(stack)) }
    when (top) {
        is Overlay.Chat ->
            ChatScreen(
                peerHex = top.peerHex,
                onBack = back,
                onOpenContact = { navigate(OverlayStack.push(stack, Overlay.Contact(it))) },
            )
        is Overlay.Contact ->
            ContactDetailScreen(
                peerHex = top.peerHex,
                onBack = back,
                onMessage = { navigate(OverlayStack.toChat(stack, it)) },
            )
        Overlay.MyCard -> MyCardScreen(onBack = back)
        Overlay.Scan ->
            ScanCardScreen(
                onBack = back,
                // Entering the card as text replaces the camera screen, so Back returns to the contacts.
                onEnterText = { navigate(OverlayStack.push(OverlayStack.pop(stack), Overlay.Paste)) },
                onDone = back,
            )
        Overlay.Paste -> PasteCardScreen(onBack = back, onDone = back)
        Overlay.NewChat -> NewChatScreen(onBack = back, onPick = { navigate(OverlayStack.toChat(OverlayStack.pop(stack), it)) })
        Overlay.Compose -> ComposeAnnouncementScreen(onBack = back, onDone = back)
        Overlay.Network -> NetworkScreen(onBack = back)
    }
}
