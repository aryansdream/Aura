# Aura Sync

Aura Sync is the Linux/Windows desktop companion for Aura's local device ecosystem.

## Features

- Sign in to the same Aura account once, then discover Aura devices on the current Wi-Fi or a phone hotspot without WAN access.
- Encrypted text, clipboard, and file sharing between Aura Sync and the existing Android peer protocol.
- Show nearby Android devices and their focus state, plus cached Aura points and streak.
- Optional local media classifier. It uses a small Naive Bayes text model on this computer to classify media-session app/title metadata.
- When enabled on both the PC and Android, a high-confidence entertainment classification can send a signed local pause command. Android can also cover that app with its Accessibility overlay while Focus is active.
- Open the full Aura web dashboard from Aura Sync.

## Privacy and limits

Media detection is off by default. Android sends media-session metadata only while a Focus session is active and only after the user enables sharing. The PC decrypts and classifies it locally; no media metadata is sent to Aura's server. Aura does not capture audio or screen images. The app name and classification result remain in the desktop app's in-memory activity list; user corrections are stored locally.

The classifier is intentionally small and heuristic-quality; its confidence is an estimate, not a guarantee. It only pauses at 76% confidence or higher. A false positive can pause a player, so detection and pausing have separate controls. Android app covering requires Notification access for media sessions and Accessibility access for the overlay. On Linux, system media detection/control uses the optional `playerctl` command and therefore works with MPRIS-capable players. On Windows, it reads Windows media-session metadata and uses the system media-pause key.

Sharing and device discovery are limited to the local network. The account must be connected at least once to obtain the existing Aura LAN key; the cached key is protected by the OS secret store when available. Linux installations without a Secret Service provider should use full-disk encryption because Electron's OS credential protection may be unavailable. A firewall may ask to allow Aura Sync on private networks so peers can discover and reach one another.

## Run from source

```sh
npm install
npm start
```

## Build installers

Ubuntu/Linux:

```sh
npm run dist:linux
```

This creates `.deb` and AppImage packages under `release/`.

Windows:

```sh
npm run dist:windows
```

Build Windows installers on Windows. The repository workflow at `.github/workflows/aura-sync-build.yml` builds both platform artifacts on GitHub Actions and stores them as downloadable workflow artifacts; it does not publish a release automatically.
## Aura Sync 1.0.3 desktop release

The Ubuntu/Debian 1.0.3 build opens the full Aura planner inside the desktop app and includes the desktop local-network companion. The planner provides the web-backed Focus timer, timetable, subjects, attendance, study logs, scheduled tasks, and account data; the companion provides same-Wi-Fi device discovery, text/clipboard/file sharing, and optional local media metadata classification. Android-only OS integrations such as Android step/screen-time permissions and Accessibility overlays remain Android-specific.

The Windows installer remains at 1.0.2 in this release.
