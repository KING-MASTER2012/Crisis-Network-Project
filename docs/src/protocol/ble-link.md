# BLE link profile

How two Crisis Network nodes talk over Bluetooth Low Energy. This is the radio side of the
[protocol](v1.md): the core produces and consumes opaque packets, this profile only says how a
packet crosses one BLE connection. Another implementation (for example iOS) must follow it to
interoperate. Status: **draft, implemented by the Android client in phase A2**; discovery policy
and topology (phase A3) are not part of it yet.

## Roles

Every device is both at the same time:

* **Peripheral (GATT server):** advertises the service and serves it.
* **Central (GATT client):** scans for the service and connects to what it finds.

Once a connection is up, the roles are symmetric for the mesh: both sides send and receive packets.

## Advertisement

* Connectable, undirected.
* Payload: the 128-bit service UUID only (flags + UUID take 21 of the 31 bytes).
* No device name and no transmit power: a name would identify the owner. Rotating tokens that let
  two devices avoid connecting to each other twice are specified in phase A3.

## GATT service

| Item | UUID | Properties | Direction |
| --- | --- | --- | --- |
| Service | `6f1d5a30-7c2e-4b8a-9d41-c3b5e0a7f2d6` | primary | |
| RX characteristic | `6f1d5a31-7c2e-4b8a-9d41-c3b5e0a7f2d6` | write without response | central → peripheral |
| TX characteristic | `6f1d5a32-7c2e-4b8a-9d41-c3b5e0a7f2d6` | notify | peripheral → central |
| TX CCCD | `00002902-0000-1000-8000-00805f9b34fb` | read, write | |

The UUIDs are random; changing one splits the network.

## Connection setup (central side)

In this order, one GATT operation at a time:

1. Connect (no auto-connect, LE transport).
2. Discover services; the service, RX, TX and the TX CCCD must exist, otherwise disconnect.
3. Negotiate the ATT MTU (ask for 517; skip the request if the stack already exchanged it).
4. Enable notifications on TX (write `0x0001` to the CCCD).

The central only sends after step 4. The peripheral considers the link **ready when the central
subscribed**; it cannot request an MTU itself, it only learns the one the central chose.

A link that is not ready 15 s after the connection started is dropped.

## Packets

* **One core packet is exactly one write (RX) or one notification (TX).** There is no framing,
  header or checksum on top of the core's own packet format; the core already fragments to fit.
* The size of the packets a link carries is `min(ATT MTU - 3, 512)`. This is the value reported
  to the core as the link MTU.
* The core requires at least `min_link_mtu` (64 in protocol v1). A link whose MTU is smaller is
  not reported to the core and is closed once the handshake time is over.
* If the MTU grows after the link became ready, the larger value is reported again for the same
  neighbour.
* Empty writes and notifications are ignored.

## Sending

* A transport keeps one bounded queue per link (32 KiB by default) and has **one send in flight**
  per link: the next write or notification starts when the platform reports the previous one done.
* A packet that does not fit the queue, or is larger than the link MTU, is dropped; the mesh
  repairs losses by itself.
* A send that the platform refuses (busy) is retried every 25 ms, at most 40 times; a send that
  does not complete within 5 s, or three failed sends in a row, close the link.

## Handles

The transport identifies a connection to the core with a 64-bit handle that is assigned when the
link becomes usable and **never reused** during the life of the transport. A reconnect, even to
the same device, gets a new handle. The core keys all per-neighbour state by handle and knows
nothing about radio addresses.

## Known limits of this version

* Two devices that both scan and advertise connect to each other twice (once per direction).
  The tie-break arrives with phase A3.
* Radio addresses are random and rotate; links are keyed by address only while they exist.
* No back-off per error code (GATT status 133 and similar), no connection rotation, no power
  profiles, no filtered scanning with the screen off: all phase A3.
