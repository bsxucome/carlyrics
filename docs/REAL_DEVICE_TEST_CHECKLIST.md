# Real-Device Release Checklist

Run this checklist with the release-candidate phone and head-unit APKs before publishing.
Record device models, Android versions, player apps, and failures in the release notes.

## Installation And Pairing

- [ ] Fresh-install both APKs and complete the documented permission flow.
- [ ] Pair the devices in Android Bluetooth settings before connecting in Car Lyrics.
- [ ] Connect from the head unit and confirm both apps show the connected state.
- [ ] Upgrade both APKs in place and confirm the trusted-device relationship survives.
- [ ] Reset the trusted device on each side and confirm explicit reconnection works.

## Playback And Lyrics

- [ ] Test play, pause, previous, and next from the head unit.
- [ ] Confirm title, artist, album, duration, progress, and play state stay synchronized.
- [ ] Test at least two player apps and switch between them while playback is active.
- [ ] Verify synchronized lyrics, plain lyrics, blank timing gaps, and a track with no lyrics.
- [ ] Verify retrying lyrics and switching to a configured LRCLIB-compatible mirror.
- [ ] Confirm long titles, artists, and lyric lines do not break the layout.

## Artwork

- [ ] Test square, portrait, landscape, missing, and rapidly changing artwork.
- [ ] Confirm artwork and blurred backdrop update when the track changes.
- [ ] Confirm malformed or oversized artwork is ignored without losing playback metadata.
- [ ] Monitor both apps for crashes or visible memory pressure during repeated track changes.

## Connection Recovery

- [ ] Toggle Bluetooth off and on at the phone, then at the head unit.
- [ ] Move out of Bluetooth range and return after at least 30 seconds.
- [ ] Force-stop and reopen each app independently.
- [ ] Reboot the phone and confirm the foreground connection service recovers.
- [ ] Reboot the head unit and confirm trusted-device auto-reconnect.
- [ ] Revoke and restore Bluetooth permission.
- [ ] Disable and restore notification-listener access.
- [ ] Let the phone vendor battery manager stop background work, then follow the recovery UI.

## Compatibility

- [ ] Test the current phone APK with the previous head-unit APK.
- [ ] Test the current head-unit APK with the previous phone APK.
- [ ] Confirm unsupported protocol versions show a specific update-both-apps message.
- [ ] Confirm a trusted-device identity mismatch does not silently replace the trusted device.

## Release Evidence

- [ ] Save relevant Logcat excerpts for connection, lyrics lookup, and recovery failures.
- [ ] Record APK version names, version codes, and signing-certificate SHA-256.
- [ ] Run `testDebugUnitTest`, `lintDebug`, and the release build.
- [ ] Verify release APK checksums before uploading.
