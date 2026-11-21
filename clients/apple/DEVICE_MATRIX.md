# Device matrix of the Apple clients (phase I9)

Nothing in this file has been run yet: it is the plan, the table to fill in, and the expected result of every row, so that a
test round on real devices produces a result and not an impression. Copy the tables into the release's notes (or a file in
`docs/`) and fill in the "Result" column with `pass`, `fail` (with what happened) or `n/a`.

Rules of the round: install the **release** build of the tag under test (the `-notarized.dmg` for a Mac, TestFlight or the
unsigned `.ipa` signed with your own Apple ID for iPhones; the file names are in `README.md`), from a clean state (delete the
app, so the Keychain item and the database are made again), with the log of the test bench open
(the `BleLinkTest` screen of a debug build, and the Network screen of every build) when a row asks for it.

## 1. The devices

Pick at least one of each row; two of the first row (a link needs two devices).

| Group | Why it is in the matrix | Examples | Result |
| --- | --- | --- | --- |
| iPhone with the minimum system (iOS 17.x) | The oldest system the app promises | iPhone XS/XR or SE (2nd generation), iOS 17.0 to 17.x | |
| iPhone with the newest system | What most people have | The newest iPhone, the newest iOS | |
| Small screen | Large text and long names have the least room | iPhone SE (3rd generation) or a "mini" | |
| Large screen | Layout with a wide column | iPhone Pro Max / Plus | |
| iPad | `NavigationSplitView`, both orientations, multitasking | Any iPad with iPadOS 17 or later | |
| Mac with Apple silicon, macOS 14 | The main Mac | Any M-series Mac | |
| Mac with Intel, macOS 14 | The `x86_64` file | An Intel Mac that runs macOS 14 | |
| Android phone | The other end of every link | The Android client's own matrix: `clients/android/DEVICE_MATRIX.md` | |

## 2. The conditions of the system (every one on at least one iPhone and, where it exists, on the Mac)

| # | Condition | How to set it | Expected | Result |
| --- | --- | --- | --- | --- |
| C1 | Bluetooth permission **not yet asked** | Clean install | Joining asks; the text of the dialog is the one of `InfoPlist.xcstrings` in the language of the phone (check both English and Turkish) | |
| C2 | Bluetooth permission **denied** | Settings > Crisis Network > Bluetooth off, or "Don't allow" | The Home screen shows the blocker card with a button that opens the system settings; no crash; joining after allowing it works without a restart | |
| C3 | Bluetooth **switched off** in Control Centre | Control Centre | The card says so; switching it on lets the person join; a running node resumes by itself | |
| C4 | **Low Power Mode** on | Settings > Battery | The power profile follows (see "Network" and `home_profile_line`); links stay up in the foreground; write down the time to find a neighbour with and without | |
| C5 | **Background App Refresh off** (for the app, and for everything) | Settings > General > Background App Refresh | The introduction/Settings explain what it costs; with the app in the background the node may stop finding or keeping neighbours (write down what happens); coming back to the app restores it | |
| C6 | **Notifications denied** | Settings > Notifications | A message still arrives and is shown in the chat; no notification; the Settings row says why and where to change it | |
| C7 | **Notification previews hidden** (lock screen) | Settings > Notifications > Show Previews: Never | The notification text is the generic line, never a name or a message | |
| C8 | **Camera denied** | Settings > Crisis Network > Camera off | The scan screen explains and offers the settings and the text entry; on a Mac: the picture file, the clipboard and the drop still work | |
| C9 | **Focus / Do Not Disturb** | Control Centre | Notifications wait or not as the Focus says; the node is not affected | |
| C10 | **Airplane Mode with Bluetooth on** | Control Centre | The mesh works (it needs no network) | |
| C11 | **App killed** by the person (swipe away), then a neighbour sends | Task switcher | iOS does not relaunch an app that was swiped away for Bluetooth: write down what happens, and that opening the app brings the mesh back | |
| C12 | **Restart** of the phone, **before** the first unlock | Restart, do not unlock | The node does not run (the key needs the first unlock); after the unlock it starts without a click when "join at launch" was on | |
| C13 | **Language** English / Turkish, and a change while the app runs | Settings > Language | Every screen follows at once; the sentences read by VoiceOver follow too | |
| C14 | **Dark theme, high contrast, "Increase Contrast" and "Reduce Transparency"** | Display settings, Settings > Appearance | Text stays readable; no information only in colour | |
| C15 | **Mac: window closed, Dock icon hidden, open at login, sleep and wake, lid closed** | The "Exit check of I8" in `README.md` | As written there | |

## 3. Accessibility

### VoiceOver (on an iPhone and on a Mac; in English and in Turkish)

Turn VoiceOver on (triple-click the side button after Settings > Accessibility > Accessibility Shortcut), then without looking:

| # | Do | Expected | Result |
| --- | --- | --- | --- |
| V1 | Go through the introduction to the end | Every step has a title read as a heading; every button says what it does; the Bluetooth and notification steps can be completed | |
| V2 | Home: join the mesh | The state is **announced** when it settles ("connected", "searching", "offline"), not while it only says "starting"; the safety number is read in groups | |
| V3 | Chats: open a chat, write and send a message | Each message is one element with sender, text and "sent" / "received" hint; the send button and the field are reachable; a failed send is announced | |
| V4 | Contacts: add a card as text, open "Show my card" | The QR code has a label; the card text can be copied or shared; the Mac's "copy/save as picture" buttons are reachable | |
| V5 | Announcements: read one, write one | Severity is read in words (never only colour); the life-time choice is reachable | |
| V6 | Network and Settings | Rows are single elements with their value; toggles say on/off; the storage bar has a spoken value | |
| V7 | The rotor "Headings" on each screen | Jumps between the titles | |

### Dynamic Type

Set the text size to the **default**, **Larger Accessibility Sizes > AX3** and the **largest (AX5)**, on the small iPhone and on an iPad, in portrait and landscape:

| # | Check | Expected | Result |
| --- | --- | --- | --- |
| D1 | Every screen | Nothing is cut off or overlaps; every screen scrolls; no text ends in "…" at the accessibility sizes | |
| D2 | The chat composer | At AX sizes the send button moves under the field and is as wide as the screen; at other sizes it grows with the text | |
| D3 | Rows (chats, contacts, announcements) | At AX sizes the whole name and the preview are shown | |
| D4 | Buttons | At least 48 pt high and as high as their text needs; the label wraps | |
| D5 | The in-app text size (Settings > Appearance) | It adds to the system size and never goes below the readable minimum | |
| D6 | "Bold Text", "Button Shapes", "Reduce Motion" | Honoured | |

## 4. Two devices, two systems

For every pair, in both directions, with both apps in the foreground first, then one in the background (iPhone: locked):

| Pair | Link comes up | Message A to B | Message B to A | In the background | Result |
| --- | --- | --- | --- | --- | --- |
| iPhone and Android phone | | | | | |
| iPhone and iPhone | | | | | |
| iPhone and Mac | | | | | |
| Mac and Android phone | | | | | |
| Three devices in a line (A - B - C, A and C out of range of each other), a message from A to C | | | | | |

Record per pair: seconds until the first link, and whether there is **one** link or two (the tie-break should leave one).

## 5. Before every release

1. `README.md` > "Exit check of I3" (three mixed devices and the battery) and "Exit check of I8" (Macs).
2. This file: sections 2 to 4 on the devices of section 1 that you have.
3. `bash scripts/check-mac-app.sh` and `bash scripts/check-privacy-manifest.sh` on the built Mac app (the release workflow runs them).
4. The upload of a build to TestFlight and the answer to the export compliance question (`EXPORT_COMPLIANCE.md`).
