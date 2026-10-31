# Device matrix and manual checks

**Status: not run.** This file is the plan and the table to fill in on real phones; no result below is a
measurement. Emulators cannot do the Bluetooth part, and they do not have the phone makers' background rules.

## Why these phones

The mesh lives in a foreground service (`connectedDevice`). Android itself keeps such a service alive; the phone
makers add lists that kill it with the screen off. The app shows the right place per maker (Settings, "Keeping the
mesh running"); this matrix finds out whether that advice is enough.

| Group           | Examples                                                 | Known to need                                                         |
|-----------------|----------------------------------------------------------|-----------------------------------------------------------------------|
| Google          | Pixel                                                    | Nothing beyond the battery setting. The reference phone.              |
| Samsung         | Galaxy A and S series (One UI)                           | Not in "Sleeping apps" / "Deep sleeping apps"; "Never sleeping apps". |
| Xiaomi          | Redmi, POCO (HyperOS, MIUI)                              | Autostart, battery saver "No restrictions", lock in recents.          |
| OPPO group      | OPPO, OnePlus, realme, vivo                              | Auto-launch, background activity, unrestricted battery use.           |
| Huawei / Honor  |                                                          | App launch: manual management with all three switches.                |
| Old and low-end | Android 8 to 10, 2 GB RAM                                | Scan and connect limits, memory pressure.                             |
| New             | Android 14 and 15, a phone with 16 KB pages if available | Foreground-service rules, 16 KB alignment.                            |

Menu names change between versions; the app text says so. Write down the exact path that worked.

## Test procedure (per phone, with at least two others)

Use three phones where one is the relay: A and C are out of Bluetooth range of each other and both in range of B.

1. **Install and identity.** Fresh install, onboarding, identity created. Note the Android version and build.
2. **Join and find.** Join the mesh on all phones. Time until each shows a neighbour (Network status).
3. **Direct message** A to C through B, both directions. Delivered state reaches "Delivered".
4. **Announcement** from A with each severity; it reaches C. Check the notification with C's app closed.
5. **Screen off, 30 minutes**, phones on a table, battery at least 50 %. Then send A to C. Record: did it arrive,
   how long did it take, did the notification "Mesh is running" stay, battery drop per hour.
6. **Screen off, 2 hours** on the phone under test only (the others keep the screen on). Same questions.
7. **App swiped away from recents.** Does the service stay? (It should on Pixel; record what each maker does.)
8. **Bluetooth off and on** while joined: the app must say so and recover without a restart.
9. **Reboot.** The mesh must not start by itself (the person decides); onboarding must not appear again.
10. **Power profiles.** Repeat step 5 with each profile (Settings): note the battery drop per hour for each.

## Results

| Phone | Android | Maker advice followed | 1 | 2 (s) | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 (%/h per profile) | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | | | | | |

## TalkBack walk-through (once per release, Turkish and English)

Turn on TalkBack and go through the app without looking at the screen.

* Onboarding: every step has a title that is read as a heading; the buttons say what they do.
* Home: the status ("Searching", "Connected") is announced when it changes; "Network details" opens the network screen.
* Network status: each neighbour is one item; each counter reads "name, number" as one item; the storage bar is not read twice.
* Chats: a message reads "Sent by you / Received message, text, time, state" as one item; a failed send is announced.
* Announcements: a card reads severity, sender, time, text, state as one item; the compose form has labelled fields and a radio group for severity and lifetime.
* Contacts: the QR code has a description; a card can be entered as text without the camera.
* Settings: each switch reads its state; each choice list reads "radio group" and the selected item.
* Nothing needs a gesture that TalkBack cannot do; every action has a button with a label.

Also: font size at the largest setting and display size at the largest; high-contrast mode; landscape; a tablet.

## Localization checks

* Switch the phone to Turkish and to English; every screen, every notification, the notification channels in the system settings.
* Debug build: enable the developer pseudo-locales and select `English (XA)` (long, accented text: nothing may be cut off or overlap) and `ar-XB` (right to left: icons, back buttons, progress bars and lists must mirror).
* Numbers and sizes use the phone's formatting (Network status in Turkish shows "1,5 MB" and "1.234").
