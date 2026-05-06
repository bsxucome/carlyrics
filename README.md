# Car Lyrics Display

Lightweight Android head-unit app for Bluetooth music lyrics display.

## Goals

- Show current track title, artist, artwork, and playback controls while a phone is playing music over Bluetooth
- Keep synchronized lyrics as the core feature
- Stay compatible with lower-end Android head units by using classic `View + Java`

## Current architecture

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

- `app/src/main/java/com/codex/carlyrics/MainActivity.java`
  Main UI, lyric rendering, import flow, and diagnostics
- `app/src/main/java/com/codex/carlyrics/playback/MediaObserverService.java`
  Media-session and notification observer
- `app/src/main/java/com/codex/carlyrics/playback/PlaybackRepository.java`
  Playback state store and transport control bridge
- `app/src/main/java/com/codex/carlyrics/lyrics/LyricsRepository.java`
  Lyric cache, imported-lyrics override, and lookup orchestration
- `app/src/main/java/com/codex/carlyrics/lyrics/LrcLibLyricsClient.java`
  LRCLIB exact-match and search client
- `app/src/main/java/com/codex/carlyrics/lyrics/LrcParser.java`
  LRC parser

## Build notes

- Minimum SDK: `21`
- Target SDK: `34`
- Local SDK path is currently set in `local.properties` to `D:\Android`

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
