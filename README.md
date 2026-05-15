# Car Lyrics Display

Lightweight Android head-unit app for Bluetooth music lyrics display.

This project ships as two Android apps:

- `app`: the head-unit app installed on the car screen
- `phone`: the phone companion app installed on the Android phone

## Goals

- Show current track title, artist, artwork, and playback controls while a phone is playing music over Bluetooth
- Keep synchronized lyrics as the core feature
- Stay compatible with lower-end Android head units by using classic `View + Java`

## Current architecture

- The phone companion reads the current media session, playback state, artwork, and lyrics
- The head-unit app connects to the phone companion over Bluetooth Classic RFCOMM
- `NotificationListenerService` watches active media notifications
- `MediaSessionManager` provides the best available media session, playback state, and transport controls
- The app prefers media-session metadata and falls back to notification metadata when needed
- Lyrics are matched online first, then cached locally
- Users can import a local `.lrc` file for the current track and override automatic matching

## Implemented features

- Large-screen now-playing UI for car dashboards
- Artwork, title, artist, package name, and play-state display
- Previous / play-pause / next controls
- Auto lyric matching through LRCLIB exact lookup plus search fallback
- Local `.lrc` or plain-text lyric import for the current track
- Manual-lyrics override removal and automatic matching restore
- Synced lyric highlight and auto-scroll
- Lightweight diagnostics panel for package/source debugging on real head units

## Important files

- `app/src/main/java/com/bsxu/carlyrics/MainActivity.java`
  Main UI, lyric rendering, import flow, and diagnostics
- `app/src/main/java/com/bsxu/carlyrics/companion/HeadUnitCompanionManager.java`
  Bluetooth client, handshake, reconnect, and remote session state on the head unit
- `phone/src/main/java/com/bsxu/carlyrics/phone/PhoneMainActivity.java`
  Phone-side setup UI for permissions and service readiness
- `phone/src/main/java/com/bsxu/carlyrics/phone/companion/PhoneCompanionService.java`
  Phone media-session observer and lyrics publishing flow
- `phone/src/main/java/com/bsxu/carlyrics/phone/companion/PhoneConnectionManager.java`
  Bluetooth server, handshake, keepalive, and control-message handling on the phone
- `bridge/src/main/java/com/bsxu/carlyrics/bridge/`
  Shared wire protocol models and codec

## Release packages

Each GitHub Release contains two APKs:

- `carlyrics-headunit-vX.Y.Z-release.apk`
  Install this on the Android head unit
- `carlyrics-phone-companion-vX.Y.Z-release.apk`
  Install this on the Android phone that plays music

There is also a `SHA256SUMS.txt` file and a zip archive containing the same release assets.

## Install and first-time setup

1. Download the latest release assets from GitHub Releases.
2. Install `carlyrics-phone-companion-vX.Y.Z-release.apk` on the Android phone.
3. Install `carlyrics-headunit-vX.Y.Z-release.apk` on the Android head unit.
4. Pair the phone and the head unit in normal Bluetooth settings first.
5. Open the phone companion app and grant:
   notification access, app notifications, and Bluetooth / nearby devices permission.
6. Start music playback on the phone once so the companion can detect the active media session.
7. Open the head-unit app, tap `Connect phone`, and select the paired phone.
8. Wait for the phone companion state to become ready, then verify title, artwork, progress, and lyrics appear on the head unit.

## Normal usage

- Keep the phone companion installed on the same phone you use for music playback
- Keep Bluetooth enabled on both devices
- Launch the phone companion after reboot if your device aggressively stops background services
- Use the diagnostics panel on the head unit if metadata or lyrics do not appear as expected

## Upgrade notes

- Install upgrades with the same signing key so Android can update the existing apps in place
- The head-unit app and phone companion should be upgraded together when possible
- If you publish your own builds, keep `versionCode` increasing for both modules

## Build notes

- Minimum SDK: `21`
- Target SDK: `34`
- This repository builds both the head-unit app and the phone companion app
- Set your own Android SDK path in `local.properties`

## Real-world limitations

This app cannot directly read a phone player's private lyric stream from standard Bluetooth audio.
In most cases, Bluetooth only exposes media metadata and playback state to the head unit.
That means lyrics must usually be matched on the head unit side by track metadata, or imported manually.

Head-unit Bluetooth stacks vary a lot:

- Some systems expose a normal media session and work well
- Some only expose notification data and may not provide accurate timeline progress
- Some vendor builds use private Bluetooth packages that may need special handling later

## Suggested next steps

1. Install the APK on the target head unit and verify which package name exposes the Bluetooth media session
2. Test the diagnostics panel while switching between phone players and Bluetooth sources
3. Add a second lyrics provider if LRCLIB coverage is not enough for your music library
4. Add a notification-only fallback branch if you must support Android 4.4 based head units
