# Dotplay Companion

**Dot down what you play, on your Android handheld.**

Dotplay Companion sends your playtime from an Android device to your [Dotplay](https://dotplayapp.com) profile:
games you start in the **Cocoon** launcher, emulators, Android games, and apps that are really one game
(a Switch 2 emulator, a PC recomp like Ship of Harkinian). Made for the **AYN Thor**, works on any Android 8+ device.

## Install (Obtainium)

1. Install [Obtainium](https://github.com/ImranR98/Obtainium) on your device.
2. **Add App** → paste `https://github.com/yasuperu/dotplay-companion` → **Add** → **Install**.

Obtainium keeps the app up to date from the releases of this repository. You can also download the APK
from [Releases](https://github.com/yasuperu/dotplay-companion/releases) yourself.

## Set up (about a minute)

Open the app and follow the steps:

1. **Link your account.** The app shows a code like `K7Q-4ZP`. Tap *Open the website*, sign in to Dotplay
   and press **Link**. No keys or URLs to copy.
2. **Usage access.** Android only shares how long an app was on screen with this permission. Nothing else is read.
3. **Cocoon folder.** Pick the `Cocoon` folder on internal storage. The app reads which game Cocoon started and counts
   the time the emulator was on screen.
4. **Extras (optional).** Android games to track, and apps that count as a specific game.
5. **Done.** The app syncs every hour and whenever you open it.

On dual-screen handhelds (AYN Thor) the second screen shows your week while the app is open.

## How playtime is measured

- **Cocoon games:** Cocoon writes every game start to `Cocoon/launch_debug.log` (title, system folder, emulator).
  The time that emulator is in the foreground (Android usage stats) is credited to the game Cocoon last started in it.
- **Android games and "apps as games":** foreground time of the apps you choose.
- **History:** Android keeps detailed usage for about a week, so tracking starts when you install the app.
  Older Cocoon history can be imported from a play-history file (*Home → Import a play-history file*).
- Sending is idempotent (minutes per day), so syncing more often never counts time twice.

## Privacy

The app only sends game titles, systems and minutes per day to your Dotplay account, over HTTPS.
See the [Dotplay privacy statement](https://dotplayapp.com/privacy).

## Build it yourself

```bash
gradle assembleDebug      # JDK 17, Android SDK 35
```

Release builds are signed in GitHub Actions when a `v*` tag is pushed (secrets `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`).

Fonts: [Poppins](https://fonts.google.com/specimen/Poppins), SIL Open Font License (see `FONT_LICENSE_OFL.txt`).
