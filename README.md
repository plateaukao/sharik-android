# Sharik (native Android)

Minimal Android client for [Sharik](https://github.com/marchellodev/sharik)'s
LAN sharing protocol. Plain framework Views, no AndroidX, no Compose, no
third-party dependencies — the release APK is about **80 KB**. English only,
follows the system light/dark theme.

## What it does

- **Share file…** — picks one or more files (Storage Access Framework).
- **Share from any app** — Sharik appears in the system Share sheet for any
  file type (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) and for text.
- **Share text…** — a snippet or a link.
- **Receive** — lists Sharik senders on the Wi-Fi; tap one to save files into
  `Download/` (folders recreated, names de-duplicated) or to show the text
  with a Copy button. Holds a `MulticastLock` while listening.
- History — tap to share again, long-press to remove, *Clear history* at the
  bottom. Device name (shown to senders) is the label at the bottom right.

minSdk 28 (Android 9; asks for storage permission there), targetSdk 36.

## Build

```
./gradlew assembleRelease       # app/build/outputs/apk/release/app-release.apk
```

Release signing reads `SHARIK_KEYSTORE`, `SHARIK_KEYSTORE_PASSWORD`,
`SHARIK_KEY_ALIAS`, `SHARIK_KEY_PASSWORD` from the environment or
`~/.gradle/gradle.properties`; without them the debug key is used.

## Protocol (compatible with Sharik 3.x, sharik-native, sharik.koplugin)

```
beacon   UDP 239.10.10.100:54545 every 1 s (also 255.255.255.255)
         {"sharik":"3.5.0","type":"file|text","name":…,"os":"android","port":N,"deviceName":…}
reply    receiver sends its device name back to the beacon's source ip:port
GET /    single file -> application/octet-stream + Content-Disposition
         text        -> text/plain
         several     -> text/html listing, <a href="/?q=INDEX" class="file">
GET /?q= one of the shared items (by index; content URIs have no usable path)
```

Sources: `Protocol.kt` (wire format), `ShareSession.kt` (ServerSocket HTTP
server + beacon), `ReceiveSession.kt` (multicast listener, downloads,
MediaStore/Downloads writer), `Store.kt` (history, device name,
ContentResolver helpers), `MainActivity.kt` (UI + intents).
