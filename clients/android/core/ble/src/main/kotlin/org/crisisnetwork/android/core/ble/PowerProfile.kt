package org.crisisnetwork.android.core.ble

/** How aggressively the radio is used. The platform-neutral names of the Android scan and advertise modes. */
enum class ScanMode { LowPower, Balanced, LowLatency }

enum class AdvertiseMode { LowPower, Balanced, LowLatency }

/** Connection interval class requested on links we opened (the central decides it, a peripheral cannot ask). */
enum class LinkPriority { LowPower, Balanced, High }

/**
 * What a [PowerProfile] means for the transport.
 *
 * @property screenOffScanMode The scan mode while the screen is off. The scan is filtered on the
 *   service UUID, which is what allows it to run (and be offloaded to the Bluetooth chip) with the
 *   screen off.
 * @property maxLinks Links (both directions) the profile allows; one slot is kept free for incoming
 *   connections when the limit is 3 or more.
 * @property rotationIntervalMs How often a saturated node may swap its oldest outgoing link for a
 *   neighbour that is waiting.
 */
data class BleTuning(
    val scanMode: ScanMode,
    val screenOffScanMode: ScanMode,
    val advertiseMode: AdvertiseMode,
    val maxLinks: Int,
    val linkPriority: LinkPriority,
    val screenOffLinkPriority: LinkPriority,
    val rotationIntervalMs: Long,
)

/**
 * The three power profiles of the product (settings screen in A6). The numbers are a starting
 * point to be tuned with the battery measurements of the A3 exit test.
 *
 * * **Saver**: the platform's own duty cycle (about 10 % of the time scanning), slow advertising,
 *   few links. A neighbour is found in seconds to tens of seconds.
 * * **Balanced**: the default. Responsive scan while the screen is on, duty-cycled while it is off.
 * * **Aggressive**: continuous scan and fast advertising, more links; for the moments when the
 *   network is being used actively. Costs noticeably more battery.
 */
enum class PowerProfile(val tuning: BleTuning) {
    Saver(
        BleTuning(
            scanMode = ScanMode.LowPower,
            screenOffScanMode = ScanMode.LowPower,
            advertiseMode = AdvertiseMode.LowPower,
            maxLinks = 3,
            linkPriority = LinkPriority.LowPower,
            screenOffLinkPriority = LinkPriority.LowPower,
            rotationIntervalMs = 15 * 60_000L,
        ),
    ),
    Balanced(
        BleTuning(
            scanMode = ScanMode.Balanced,
            screenOffScanMode = ScanMode.LowPower,
            advertiseMode = AdvertiseMode.Balanced,
            maxLinks = 5,
            linkPriority = LinkPriority.Balanced,
            screenOffLinkPriority = LinkPriority.LowPower,
            rotationIntervalMs = 10 * 60_000L,
        ),
    ),
    Aggressive(
        BleTuning(
            scanMode = ScanMode.LowLatency,
            screenOffScanMode = ScanMode.Balanced,
            advertiseMode = AdvertiseMode.LowLatency,
            maxLinks = 7,
            linkPriority = LinkPriority.High,
            screenOffLinkPriority = LinkPriority.Balanced,
            rotationIntervalMs = 5 * 60_000L,
        ),
    ),
}
