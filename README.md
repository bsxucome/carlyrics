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

- The phone companion is the only runtime source of playback metadata, artwork, and lyrics
- The phone companion reads the active media session, falls back to notification artwork when needed, fetches lyrics, and serves that state over Bluetooth Classic RFCOMM
- The head-unit app only connects to the phone companion, renders the remote session state, and sends playback control actions back to the phone
- The old local head-unit media observer and local head-unit lyrics lookup path have been removed from the active architecture

## Implemented features

- Large-screen now-playing UI for car dashboards
- Artwork, title, artist, package name, and play-state display
- Previous / play-pause / next controls
- Auto lyric matching on the phone companion through LRCLIB exact lookup plus search fallback
- Persistent phone-side lyric cache for faster repeat playback
- Optional HTTPS LRCLIB-compatible mirror with automatic official-server fallback
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
  Phone media-session observer, artwork resolution, and lyrics publishing flow
- `phone/src/main/java/com/bsxu/carlyrics/phone/companion/PhoneConnectionManager.java`
  The only Bluetooth transport implementation on the phone: server, handshake, keepalive, state publish, and control-message handling
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

### Lyrics network settings

- The phone companion uses `https://lrclib.net` by default.
- A trusted LRCLIB-compatible HTTPS mirror can be configured from the phone app under `Lyrics network source`.
- When a mirror is configured, the phone tries it first and falls back to the official LRCLIB server.
- The project-owned Worker can be entered manually as `https://carlyrics-lrclib-proxy.bsxu579.workers.dev`.
- Successfully matched lyrics are cached on the phone for up to 200 tracks, so repeat playback can display lyrics without waiting for the network.

## Normal usage

- Keep the phone companion installed on the same phone you use for music playback
- Keep Bluetooth enabled on both devices
- Launch the phone companion after reboot if your device aggressively stops background services
- The head-unit app does not scan the local media session or fetch lyrics by itself; if the phone companion is not connected, the head unit is intentionally render-only
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
In most cases, Bluetooth only exposes media metadata and playback state, so this project relies on the phone companion to collect the media session and perform lyrics lookup before sending the result to the head unit.

Head-unit Bluetooth stacks vary a lot:

- Some systems expose a normal media session and work well
- Some only expose notification data and may not provide accurate timeline progress
- Some vendor builds use private Bluetooth packages that may need special handling later

## Suggested next steps

1. Test the diagnostics panel while switching between phone players and Bluetooth sources
2. Add a second phone-side lyrics provider if LRCLIB coverage is not enough for your music library
3. Harden pairing / trusted-device recovery flows across phone replacements and app reinstalls
4. Add richer head-unit diagnostics or onboarding if users frequently miss the phone companion setup requirements
