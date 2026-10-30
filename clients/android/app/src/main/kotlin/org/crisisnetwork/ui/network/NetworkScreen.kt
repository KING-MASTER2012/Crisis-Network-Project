package org.crisisnetwork.android.ui.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.crisisnetwork.android.AppVisibility
import org.crisisnetwork.android.MeshController
import org.crisisnetwork.android.R
import org.crisisnetwork.android.core.ble.BleStats
import org.crisisnetwork.android.core.ble.LinkInfo
import org.crisisnetwork.android.core.ble.LinkRole
import org.crisisnetwork.android.settings.StorageLimits
import org.crisisnetwork.android.ui.components.InfoCard
import org.crisisnetwork.android.ui.components.ScreenColumn
import org.crisisnetwork.android.ui.components.ScreenTopBar
import org.crisisnetwork.android.ui.components.StatusPill
import org.crisisnetwork.android.ui.components.Tone
import org.crisisnetwork.android.ui.screens.profileLabel
import uniffi.crisis_core.NodeStats

/** How often the core's counters are read while the screen is on. They only change when packets move. */
private const val STATS_POLL_MS = 2_000L

/**
 * The network at a glance (phase A6): is this phone part of the mesh, which neighbours are
 * connected, how full the message store is, how the Bluetooth radio behaves, and the core's
 * counters (`NodeStats`) for finding out why something does not arrive.
 *
 * Neighbours are anonymous: the Bluetooth link says nothing about who is on the other side, only
 * the messages do. A neighbour is therefore shown by a number and the end of its Bluetooth address
 * (random on most phones), with what the link carries.
 */
@Composable
fun NetworkScreen(onBack: () -> Unit) {
    val mesh by MeshController.state.collectAsState()
    val visible by AppVisibility.visible.collectAsState()
    val running = mesh.status == MeshController.Status.Running

    // The link and radio numbers come from the controller; the counters are read from the core here,
    // only while the node runs and the app is on screen.
    val stats by produceState<NodeStats?>(initialValue = null, running, visible) {
        if (!running) {
            value = null
            return@produceState
        }
        while (visible) {
            try {
                MeshController.runtime?.stats()?.let { value = it }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // The node stopped between two reads or failed: keep the last numbers, the status card tells the rest.
            }
            delay(STATS_POLL_MS)
        }
    }

    Column {
        ScreenTopBar(title = stringResource(R.string.net_title), onBack = onBack)
        ScreenColumn {
            StatusCard(mesh)
            if (!running) {
                InfoCard {
                    Text(stringResource(R.string.net_not_running), style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                NeighboursCard(mesh.links, mesh.stats)
                stats?.let { current ->
                    StorageCard(current, StorageLimits.bytesOf(mesh.openedWith?.storeLimitMb ?: mesh.storeLimitMb))
                }
                mesh.stats?.let { RadioCard(it, mesh.activeLinks) }
                stats?.let { CountersCard(it) }
            }
        }
    }
}

@Composable
private fun StatusCard(mesh: MeshController.State) {
    InfoCard(title = stringResource(R.string.home_mesh_title)) {
        val neighbours = mesh.activeLinks
        val (label, tone) =
            when {
                mesh.status == MeshController.Status.Starting -> stringResource(R.string.home_status_starting) to Tone.Neutral
                mesh.status == MeshController.Status.Stopping -> stringResource(R.string.home_status_stopping) to Tone.Neutral
                mesh.status == MeshController.Status.Running && neighbours > 0 -> stringResource(R.string.home_status_connected) to Tone.Success
                mesh.status == MeshController.Status.Running -> stringResource(R.string.home_status_searching) to Tone.Warning
                else -> stringResource(R.string.home_status_offline) to Tone.Neutral
            }
        StatusPill(text = label, tone = tone)
        Text(stringResource(R.string.home_profile_line, profileLabel(mesh.profile)), style = MaterialTheme.typography.bodyMedium)
        mesh.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun NeighboursCard(links: List<LinkInfo>, radio: BleStats?) {
    InfoCard(title = stringResource(R.string.net_neighbours_title)) {
        val active = links.count { it.active }
        Text(pluralStringResource(R.plurals.home_neighbours, active, active), style = MaterialTheme.typography.titleSmall)
        if (links.isEmpty()) {
            Text(stringResource(R.string.net_neighbours_none), style = MaterialTheme.typography.bodyMedium)
        }
        // Connected ones first; the order of the rest stays as the transport reports it.
        links.sortedByDescending { it.active }.forEachIndexed { index, link -> LinkItem(index + 1, link) }
        if (radio != null && radio.candidates > 0) {
            Text(stringResource(R.string.net_candidates_line, radio.candidates), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LinkItem(number: Int, link: LinkInfo) {
    Column(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.net_link_title, number, link.address.takeLast(5)), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(if (link.role == LinkRole.Client) R.string.net_link_outgoing else R.string.net_link_incoming),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(stringResource(if (link.active) R.string.net_link_active else R.string.net_link_connecting), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.net_link_traffic, ByteSizes.format(link.metrics.bytesSent), ByteSizes.format(link.metrics.bytesReceived)),
            style = MaterialTheme.typography.bodySmall,
        )
        if (link.payloadMtu > 0) {
            Text(stringResource(R.string.net_link_mtu, link.payloadMtu), style = MaterialTheme.typography.bodySmall)
        }
        if (link.queuedBytes > 0) {
            Text(stringResource(R.string.net_link_queued, ByteSizes.format(link.queuedBytes.toLong())), style = MaterialTheme.typography.bodySmall)
        }
        if (link.metrics.packetsDropped > 0) {
            Text(stringResource(R.string.net_link_dropped, ByteSizes.count(link.metrics.packetsDropped)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StorageCard(stats: NodeStats, limitBytes: Long) {
    InfoCard(title = stringResource(R.string.net_storage_title)) {
        val used = stats.storedBytes.toLongSaturated()
        val messages = stats.storedMessages.toLongSaturated().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        Text(
            stringResource(R.string.net_storage_used, ByteSizes.format(used), ByteSizes.format(limitBytes)),
            style = MaterialTheme.typography.titleSmall,
        )
        // The text above says it all; the bar is only a picture of it, so TalkBack skips it.
        LinearProgressIndicator(
            progress = { ByteSizes.fraction(used, limitBytes) },
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )
        Text(pluralStringResource(R.plurals.net_storage_messages, messages, messages), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.net_storage_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RadioCard(radio: BleStats, activeLinks: Int) {
    InfoCard(title = stringResource(R.string.net_radio_title)) {
        StatRow(stringResource(R.string.net_radio_scanning), yesNo(radio.scanning))
        StatRow(stringResource(R.string.net_radio_advertising), yesNo(radio.advertising))
        StatRow(stringResource(R.string.net_radio_links), "$activeLinks / ${radio.effectiveMaxLinks}")
        StatRow(stringResource(R.string.net_radio_candidates), ByteSizes.count(radio.candidates.toLong()))
        StatRow(stringResource(R.string.net_radio_held), ByteSizes.count(radio.heldByTieBreak.toLong()))
        StatRow(stringResource(R.string.net_radio_backoff), ByteSizes.count(radio.backedOff.toLong()))
        StatRow(stringResource(R.string.net_radio_attempts), ByteSizes.count(radio.connectAttempts))
        StatRow(stringResource(R.string.net_radio_failures), ByteSizes.count(radio.connectFailures))
        StatRow(stringResource(R.string.net_radio_rotations), ByteSizes.count(radio.linkRotations))
        StatRow(stringResource(R.string.net_radio_scan_starts), ByteSizes.count(radio.scanStarts))
    }
}

@Composable
private fun CountersCard(stats: NodeStats) {
    InfoCard(title = stringResource(R.string.net_counters_title)) {
        Text(stringResource(R.string.net_counters_hint), style = MaterialTheme.typography.bodySmall)

        CounterGroup(R.string.net_group_traffic)
        StatRow(stringResource(R.string.net_stat_packets_received), ByteSizes.count(stats.packetsReceived))
        StatRow(stringResource(R.string.net_stat_packets_sent), ByteSizes.count(stats.packetsSent))
        StatRow(stringResource(R.string.net_stat_frames_accepted), ByteSizes.count(stats.framesAccepted))
        StatRow(stringResource(R.string.net_stat_duplicates), ByteSizes.count(stats.duplicates))
        StatRow(stringResource(R.string.net_stat_acks_sent), ByteSizes.count(stats.acksSent))

        CounterGroup(R.string.net_group_refused)
        StatRow(stringResource(R.string.net_stat_rate_limited), ByteSizes.count(stats.rateLimited))
        StatRow(stringResource(R.string.net_stat_sender_rate_limited), ByteSizes.count(stats.senderRateLimited))
        StatRow(stringResource(R.string.net_stat_malformed), ByteSizes.count(stats.malformedPackets))
        StatRow(stringResource(R.string.net_stat_invalid_signatures), ByteSizes.count(stats.invalidSignatures))
        StatRow(stringResource(R.string.net_stat_blocked), ByteSizes.count(stats.blockedDropped))
        StatRow(stringResource(R.string.net_stat_unknown_peer), ByteSizes.count(stats.unknownPeerPackets))
        StatRow(stringResource(R.string.net_stat_fragments_rejected), ByteSizes.count(stats.fragmentsRejected))
        StatRow(stringResource(R.string.net_stat_undecryptable), ByteSizes.count(stats.undecryptable))
        StatRow(stringResource(R.string.net_stat_tombstoned), ByteSizes.count(stats.tombstoned))
        StatRow(stringResource(R.string.net_stat_quota_rejected), ByteSizes.count(stats.quotaRejected))

        CounterGroup(R.string.net_group_trouble)
        StatRow(stringResource(R.string.net_stat_expired_transfers), ByteSizes.count(stats.expiredTransfers))
        StatRow(stringResource(R.string.net_stat_want_gave_up), ByteSizes.count(stats.wantGaveUp))
        StatRow(stringResource(R.string.net_stat_inflight_full), ByteSizes.count(stats.inflightFull))
        StatRow(stringResource(R.string.net_stat_queue_full), ByteSizes.count(stats.queueFull))
        StatRow(stringResource(R.string.net_stat_peers_banned), ByteSizes.count(stats.peersBanned))
    }
}

/** A heading inside a card, announced as one by screen readers through the card's own structure of titles. */
@Composable
private fun CounterGroup(title: Int) {
    Spacer(Modifier.height(8.dp))
    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
}

/** One line "what: number". Read as one item by TalkBack, so the number is never separated from its label. */
@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.width(16.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(if (value) R.string.net_yes else R.string.net_no)

private fun ULong.toLongSaturated(): Long = if (this > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else toLong()
