# Smart WiFi

Keeps an Android phone on the best **saved** Wi-Fi network: it ranks visible saved
networks by signal strength with a bonus for 5 GHz and 6 GHz, and switches when a clearly
better one is available or the current network loses internet access.

Built for homes with several routers that broadcast *different* SSIDs on the same
LAN, where Android's own selection keeps clinging to a weak network.

## How it decides

- Score = RSSI + band bonus (5 GHz +10, 6 GHz +12). The bonus only applies while the
  signal is usable (≥ −72 dBm), so a fading 6 GHz link doesn't beat solid 5 GHz.
- Candidates must be saved, recently scanned, at least −78 dBm, and match the saved
  security type. Open networks are ignored unless enabled.
- A switch needs a margin (default 8) confirmed on two consecutive checks, with a
  30-second cooldown. If Wi-Fi has had no validated internet for 15 seconds, the
  app switches straight away.
- If the new network doesn't connect and validate within 30 seconds, it's penalized
  for 5 minutes.
- The app never toggles Wi-Fi or mobile data and leaves VPNs alone.

## Requirements

- Android 12+ and [Shizuku](https://shizuku.rikka.app/). Switching uses the system
  Wi-Fi service as the shell user, so no network passwords are needed.

## Diagnostics

```
adb shell dumpsys activity service io.github.darkaxt.smartwifi/io.github.darkaxt.smartwifi.SwitchService
```

Logcat tag: `SmartWiFi`.

## Building

Debug: `./gradlew :app:assembleDebug` (package suffix `.debug`).

Release builds only succeed with the permanent key whose certificate SHA-256 is
pinned as `SMARTWIFI_SIGNING_SHA256` in `gradle.properties`. CI rebuilds that key
from repository secrets; signed APKs are attached to tagged GitHub releases.

## License

MIT
