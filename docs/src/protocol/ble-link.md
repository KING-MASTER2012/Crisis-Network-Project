# BLE link profile

How two Crisis Network nodes talk over Bluetooth Low Energy. This is the radio side of the
[protocol](v1.md): the core produces and consumes opaque packets, this profile only says how a
packet crosses one BLE connection and how two devices decide who connects to whom. Another
implementation must follow it to interoperate.

Status: **profile 1.1**. Implemented by the Android client (phases A2, A3 and A8) and by the Apple
client (phase I2).

| Version   | What it added                                                                                                                             |
|-----------|-------------------------------------------------------------------------------------------------------------------------------------------|
| 1.0       | Roles, advertisement with the service UUID, the GATT service (RX, TX), setup, packets, sending.                                           |
| 1.1       | The rotating **token** in the advertisement and the tie-break (A3), the **HELLO** characteristic and the removal of duplicate links (A8). |

A 1.0 device and a 1.1 device interoperate: a 1.0 device has no token and no HELLO characteristic,
which a 1.1 device treats as "no information" (see [Compatibility](#compatibility)).

## Roles

Every device is both at the same time:

* **Peripheral (GATT server):** advertises the service and serves it.
* **Central (GATT client):** scans for the service and connects to what it finds.

Once a connection is up, the roles are symmetric for the mesh: both sides send and receive packets.
An implementation may run only one side (a test device, a chain of three phones where the ends only
connect or only advertise); the profile does not require both.

## Advertisement

* Connectable, undirected.
* Payload: the 128-bit service UUID, and (1.1) one manufacturer specific data structure with the
  token described below. Flags (3 bytes) + service UUID (18) + manufacturer data (9) take 30 of the
  31 bytes.
* No device name and no transmit power: a name would identify the owner.

A platform that cannot put manufacturer data into an advertisement (**Apple platforms** advertise
the service UUID only) advertises just the service UUID. That is valid: it means "no token".

### Token (1.1)

Manufacturer specific data with company identifier `0xFFFF` (the Bluetooth SIG value reserved for
testing and private use; on the air the two identifier bytes are little endian, as always). The
payload has 5 bytes, **big endian**:

```
byte 0      bits 0..3  format version (1)
            bit  4     "can initiate": this device also scans and connects to others
            bits 5..7  reserved, ignored
bytes 1..4  token, an unsigned 32-bit random number
```

A payload shorter than 5 bytes, or of another format version, is treated as "no token".

The token is random, unrelated to the node's keys, and replaced every 10 minutes by default. It
carries no identity. (Radio addresses also rotate on current phones; neither can be used to follow a
person for long, but a device that stays in range sees how often it changes.)

### Tie-break (1.1)

Two devices that both scan and advertise would otherwise connect to each other twice, once per
direction. With the tokens both sides decide the same way without talking: **a device initiates a
connection to a neighbour when**

* it advertises no token itself (the neighbour cannot see it, so it will never connect to it), or
* the neighbour advertised no usable token, or its "can initiate" flag is not set, or
* its own token is **smaller** than the neighbour's.

Equal tokens (1 in 4 billion) make both wait until the next rotation. A device that is held back by
the tie-break connects anyway when it has had no link at all for 30 s since it first saw the
neighbour: this protects against a neighbour that cannot see it.

Discovery policy beyond this (scan modes, duty cycles, back-off, how many links) is not part of the
profile; it is the business of each implementation.

## GATT service

| Item                       | UUID                                   | Properties                  | Direction            |
|----------------------------|----------------------------------------|-----------------------------|----------------------|
| Service                    | `6f1d5a30-7c2e-4b8a-9d41-c3b5e0a7f2d6` | primary                     |                      |
| RX characteristic          | `6f1d5a31-7c2e-4b8a-9d41-c3b5e0a7f2d6` | write without response      | central → peripheral |
| TX characteristic          | `6f1d5a32-7c2e-4b8a-9d41-c3b5e0a7f2d6` | notify                      | peripheral → central |
| TX CCCD                    | `00002902-0000-1000-8000-00805f9b34fb` | read, write                 |                      |
| HELLO characteristic (1.1) | `6f1d5a33-7c2e-4b8a-9d41-c3b5e0a7f2d6` | read, write (with response) | both                 |

The UUIDs are random; changing one splits the network. The HELLO characteristic is optional for a
device to *have* (a 1.0 device has none) and for a central to *use*.

### HELLO value (1.1)

Nine bytes: one version byte (`1`), then a 64-bit identifier, **big endian**. A longer value from a
later version is read by its first nine bytes; a shorter value or another version is ignored.

The identifier is random and is replaced together with the advertised token. It is only visible to
a device that connected, never in an advertisement. Identifiers are compared as **unsigned** 64-bit
numbers, so that every platform orders them the same way.

* A read of the characteristic returns the device's current identifier.
* A write (with response) from the central tells the peripheral the central's identifier.

## Connection setup (central side)

In this order, one GATT operation at a time:

1. Connect (no auto-connect, LE transport).
2. Discover services; the service, RX and TX (with the notify property and a CCCD) must exist,
   otherwise disconnect. The HELLO characteristic may exist.
3. Negotiate the ATT MTU where the platform lets the application do it (Android asks for 517 and
   skips the request if the stack already exchanged it). Apple platforms negotiate by themselves
   when the connection is made; the application only reads the result.
4. Enable notifications on TX (write `0x0001` to the CCCD).
5. (1.1) If the HELLO characteristic exists: **read** it (the neighbour's identifier), then **write**
   our own identifier to it. This step is best effort: any failure only skips it, the link is usable
   either way.

The central only sends after step 4, and reports the link ready after step 5. The peripheral
considers the link **ready when the central subscribed** (step 4); it cannot request an MTU itself,
it only learns the one the central chose. The central's HELLO write arrives after that and is
reported as the neighbour's identifier.

A neighbour that is ready on the peripheral side may start sending right after step 4, while the
central is still in step 5. A central must therefore **keep the notifications that arrive during
step 5** and deliver them once the link is ready, instead of dropping them.

A link that is not ready 15 s after the connection started is dropped.

## Duplicate links (1.1)

When both devices run both sides, a device may still end up with two links to the same neighbour
(it connected to the neighbour and the neighbour connected to it, for example because one of them
has no token). After the HELLO exchange both ends know the identifiers of both devices, so the two
links can be recognised: they carry the same neighbour identifier.

Rule: of the links that lead to one neighbour, **the one whose client has the lower identifier
stays** (the identifier of the client of a link is our own on a link we opened, the neighbour's on a
link it opened). Both devices reach the same verdict from the same two numbers and each closes its
end of the extra link. Details:

* Two links opened by the *same* client are left to that client: the server side does not close
  either, because it cannot tell which one the client wants to keep.
* The client of a link that was closed as a duplicate leaves that address alone for a long time
  (5 minutes by default); otherwise its next scan would open the link again.
* A device that **cannot disconnect a central** (an Apple peripheral: CoreBluetooth has no call for
  it) silences its end of the extra link instead: nothing is sent on it, what arrives is ignored and
  the core is told the link is gone. The other end sees the same link as one it opened itself and
  closes it for real.
* Identifiers that never arrive (a 1.0 neighbour) leave both links in place; the mesh works with
  two links, it only wastes a connection.

## Packets

* **One core packet is exactly one write (RX) or one notification (TX).** There is no framing,
  header or checksum on top of the core's own packet format; the core already fragments to fit.
* The size of the packets a link carries is `min(ATT MTU - 3, 512)`. This is the value reported to
  the core as the link MTU. (Apple platforms report this value directly: `maximumWriteValueLength`
  of the central, `maximumUpdateValueLength` of the peripheral.)
* The core requires at least `min_link_mtu` (64 in protocol v1). A link whose MTU is smaller is not
  reported to the core and is closed once the handshake time is over.
* If the MTU grows after the link became ready, the larger value is reported again for the same
  neighbour. Platforms without a callback for it ask again regularly.
* Empty writes and notifications are ignored.

## Sending

* A transport keeps one bounded queue per link (32 KiB by default) and has **one send in flight**
  per link: the next write or notification starts when the platform reports the previous one done.
  Where the platform gives no completion (a write without response on Apple platforms), the send is
  done when the platform accepted it, and the platform's own "ready to send" signal is the flow
  control.
* A packet that does not fit the queue, or is larger than the link MTU, is dropped; the mesh
  repairs losses by itself.
* A send that the platform refuses (busy) is retried (every 25 ms, at most 40 times on Android; when
  the platform says it is ready again on Apple platforms); a send that does not complete within 5 s,
  or three failed sends in a row, close the link.

## Handles

The transport identifies a connection to the core with a 64-bit handle that is assigned when the
link becomes usable and **never reused** during the life of the transport. A reconnect, even to
the same device, gets a new handle. The core keys all per-neighbour state by handle and knows
nothing about radio addresses (which Apple platforms do not show at all: a peripheral is known by an
identifier that is stable on that device only).

## Compatibility

| Our side   | Neighbour                 | What happens                                                                                                                                         |
|------------|---------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------|
| 1.1        | 1.0 (no token, no HELLO)  | The neighbour is connected to whenever we see it. No identifiers are exchanged, so duplicate links to it are not detected. Everything else works.    |
| 1.1        | 1.1 without token (Apple) | Same as above for the token; HELLO works, so duplicates are detected.                                                                                |
| 1.0        | 1.1                       | The neighbour sees no HELLO characteristic on us and skips step 5. Our devices ignore the token and the HELLO characteristic they do not know about. |

## Known limits of this version

* Radio addresses are random and rotate; links are keyed by address (or the platform's identifier)
  only while they exist.
* Apple platforms advertise no token and cannot drop a central; both are handled as described above.
* Apple platforms advertise and scan in the foreground only in this version. In the background the
  service UUID moves to an area that only Apple scanners can see (and a scan needs the service UUID
  as a filter); background operation is a separate phase of the Apple client and may add rules here.
* Power profiles, connection rotation and back-off per error code are implementation policy, not
  part of the profile.
