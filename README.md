# KorgNote

KorgNote is an [Org-mode](https://orgmode.org) notes app with a calendar. It runs on Android, iOS,
desktop and the web, and syncs through a server you run yourself.

Your notes stay plain `.org` files in a folder. You can keep using Emacs, org-roam and git on them.

<p align="center">
  <img src="docs/screenshots/note.png" width="24%" alt="A note with a table and links">
  <img src="docs/screenshots/agenda.png" width="24%" alt="The agenda">
  <img src="docs/screenshots/week.png" width="24%" alt="The week view">
  <img src="docs/screenshots/month.png" width="24%" alt="The month view">
</p>

## Why

I keep my notes and tasks in Org files. On my phone, I tried Orgzly and Orgro, but they did not do
what I needed. I wanted one app where my notes and my calendar are the same thing.

This is a personal project that I use every day. Issues and pull requests are welcome, but I make no
promises about feature requests.

## Features

**Notes**
- Headings, TODO keywords, priorities, tags, checkboxes, tables, drawers and code blocks.
- Links: `id:`, `file:`, headings and custom IDs, with backlinks.
- New notes and daily notes are created the same way org-roam creates them.
- Search by words and tags. Pin the notes you use most.

**Editing**
- Tap a heading to edit it. Long-press to move, promote, demote or delete it.
- Suggestions above the keyboard for keywords, tags, dates and links.
- Change TODO states from a menu. Repeating tasks move to their next date when done.
- Edit tables as a grid. `#+TBLFM` formulas are computed the same way as in Emacs.

**Calendar**
- Agenda, day, week and month views, built from `SCHEDULED`, `DEADLINE` and timestamps.
- Times, ranges, repeating events and multi-day events.
- Reminders and a morning summary on your phone, even offline.

**Sync**
- Works offline. Changes sync when the server is reachable.
- Edits to the same note on two devices are merged. If they conflict, both versions are kept and you
  choose.
- Pair a phone by scanning a QR code. The connection uses TLS. No account or cloud service needed.

## Status

| Platform | Status |
|---|---|
| Android | Used every day |
| Server | Used every day (macOS, Docker) |
| Desktop | Works |
| Web | Works, on the same computer as the server |
| iOS | Builds and passes tests; not much tested on a real device |

There is no app store release yet. Build from source as described below.

## Quick start

1. Start the server on the computer that has your notes:

   ```shell
   scripts/run-server-container.sh ~/notes     # with Docker
   NOTES_DIR=~/notes ./gradlew :server:run      # without Docker
   ```

2. Open <http://localhost:8080/devices> and click **Show a pairing code**.
3. Install the Android app with `./gradlew :androidApp:installDebug`.
4. In the app, go to **Settings → Pair with a computer** and scan the code.

The phone syncs when it can reach the computer, on the same network or through a VPN.

## License

KorgNote is licensed under the [GNU General Public License v3.0](LICENSE) or later.

---

## Development

KorgNote is a [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)
project with a Compose Multiplatform UI and a Ktor server. Design notes are in [docs/](docs/README.md).

| Module | Contents |
|---|---|
| [shared/](shared/src) | Org parser, agenda and table formulas |
| [composeApp/](composeApp/src) | The app UI and sync client, for all platforms |
| [androidApp/](androidApp) | Android app |
| [iosApp/](iosApp/iosApp) | iOS app (open in Xcode) |
| [server/](server/src/main/kotlin) | Sync server, also serves the web app |

On Windows, use `gradlew.bat` instead of `./gradlew`.

### Android

```shell
./gradlew :androidApp:assembleDebug
```

The debug build installs as a separate app, *KorgNote Debug* (`com.korgnote.androidapp.debug`).

#### Release build

The signing key is stored in KeePassXC. The script asks for the database password, builds a signed
APK and prints the certificate's SHA-256:

```shell
scripts/release.sh ~/path/to/database.kdbx      # or set KORGNOTE_KDBX
adb -s <serial> install -r androidApp/build/outputs/apk/release/androidApp-release.apk
```

By default, the keystore is the attachment `korgnote-release.jks` on the entry *Korgnote keystore*.
The entry's username is the key alias and its password is the keystore password. To change this,
set `KORGNOTE_KDBX_ENTRY` and `KORGNOTE_KDBX_ATTACHMENT`. If the database also needs a key file, set
`KORGNOTE_KDBX_KEYFILE`.

Increase `versionCode` in `androidApp/build.gradle.kts` for each release.

### Desktop

```shell
./gradlew :composeApp:run
```

### iOS

Open [iosApp/](iosApp) in Xcode and run it.

### Web

Build the web app, then start the server. The server serves it at <http://localhost:8080/>.

```shell
./gradlew :composeApp:wasmJsBrowserDistribution
```

### Server

```shell
./gradlew :server:run
```

The server uses two ports:

- **8080 (HTTP)** for this computer only: the web app, the desktop app and the Devices page. It
  listens on `127.0.0.1` and has no password, so never expose it to the network.
- **8443 (HTTPS)** for paired devices. The server creates its own certificate on first start, and
  devices trust it by its fingerprint.

To pair a device, open <http://localhost:8080/devices>, click **Show a pairing code** and scan it in
the app under **Settings → Pair with a computer**. `scripts/pair.sh` shows the same code in the
terminal. You can also revoke devices from the Devices page.

The certificate and the list of paired devices are stored in `~/.korgnote-server`. If you delete
this folder, every device must pair again.

| Variable | Meaning |
|---|---|
| `NOTES_DIR` | Folder with your `.org` files. Without it, the server shows sample notes. |
| `KORGNOTE_PORT` | HTTP port. Default: 8080. |
| `KORGNOTE_HTTP_HOST` | Address the HTTP port listens on. Default: `127.0.0.1`. |
| `KORGNOTE_HTTPS_PORT` | HTTPS port for paired devices. Default: 8443. |
| `KORGNOTE_ADVERTISED_PORT` | HTTPS port put in pairing codes, if different from the real one. |
| `KORGNOTE_ADVERTISED_ADDRESSES` | Addresses put in pairing codes, separated by commas. Found automatically outside Docker. |
| `KORGNOTE_SERVER_NAME` | Name shown to paired devices. |
| `KORGNOTE_STATE_DIR` | Where the certificate and devices are stored. Default: `~/.korgnote-server`. |
| `KORGNOTE_DISABLE_WEB` | Set to `1` to not serve the web app. |
| `KORGNOTE_REQUIRE_NOTES_DIR` | Set to `1` to stop if `NOTES_DIR` is missing or not writable. |

#### Docker

```shell
scripts/run-server-container.sh ~/notes     # add -d to run in the background
```

The script saves the notes folder in `.env` and passes your computer's name and addresses to the
container. Port 8443 is open to the network; port 8080 is only open on `127.0.0.1`. The container
stops if the notes folder is missing, and runs as your user so new notes belong to you. To change
the ports, set `KORGNOTE_HOST_PORT` and `KORGNOTE_HTTPS_HOST_PORT`.

Changes you make to the notes on the computer, for example in Emacs, show up in the app right away.

### Tests

Run all tests:

```shell
./gradlew allTests :server:test --continue
```

Run one part only:

```shell
./gradlew :shared:jvmTest
./gradlew :composeApp:jvmTest
./gradlew :shared:iosSimulatorArm64Test
./gradlew :shared:wasmJsTest
./gradlew :server:test --tests 'com.korgnote.server.FileStoreTest'
```

Reports are in `<module>/build/reports/tests/`.

To run tests again even if nothing changed:

```shell
./gradlew :shared:jvmTest --rerun                                                            # one task
./gradlew cleanAllTests allTests :server:cleanTest :server:test --no-build-cache --continue  # all tests
```

On macOS, the iOS tests need the full Xcode. If they fail with `unable to find utility "simctl"`,
run:

```shell
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```
