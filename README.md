This is a Kotlin Multiplatform project targeting Android, iOS, Web, Desktop (JVM), Server.

* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
    - [commonMain](./composeApp/src/commonMain/kotlin) is for code that’s common for all targets.
    - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
      For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
      the [iosMain](./composeApp/src/iosMain/kotlin) folder would be the right place for such calls.
      Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./composeApp/src/jvmMain/kotlin)
      folder is the appropriate location.

* [/iosApp](./iosApp/iosApp) contains iOS applications. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.

* [/server](./server/src/main/kotlin) is for the Ktor server application.

* [/shared](./shared/src) is for the code that will be shared between all targets in the project.
  The most important subfolder is [commonMain](./shared/src/commonMain/kotlin). If preferred, you
  can add code to the platform-specific folders here too.

### Build and Run Android Application

To build and run the development version of the Android app, use the run configuration from the run widget
in your IDE’s toolbar or build it directly from the terminal:

- on macOS/Linux
  ```shell
  ./gradlew :composeApp:assembleDebug
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:assembleDebug
  ```

A debug build installs as its own app, *KorgNote Debug* (`com.korgnote.androidapp.debug`), beside
the release one.

#### Release build

The release key lives in KeePassXC: its keystore attached to an entry (`korgnote-release.jks` on
*Korgnote keystore* unless `KORGNOTE_KDBX_ATTACHMENT` and `KORGNOTE_KDBX_ENTRY` say otherwise),
the alias as the entry's username and the keystore password as its password; a database opened with
a key file as well wants `KORGNOTE_KDBX_KEYFILE`. This asks for the database's password, builds a
signed APK and prints its certificate's SHA-256:

```shell
scripts/release.sh ~/path/to/database.kdbx      # or set KORGNOTE_KDBX
adb -s <serial> install -r androidApp/build/outputs/apk/release/androidApp-release.apk
```

Raise `versionCode` in `androidApp/build.gradle.kts` for each release: Android refuses to install a
lower one over a higher one, and Google Play wants every upload higher than the last.

### Build and Run Desktop (JVM) Application

To build and run the development version of the desktop app, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:

- on macOS/Linux
  ```shell
  ./gradlew :composeApp:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:run
  ```

### Build and Run Server

To build and run the development version of the server, use the run configuration from the run widget
in your IDE’s toolbar or run it directly from the terminal:

- on macOS/Linux
  ```shell
  ./gradlew :server:run
  ```
- on Windows
  ```shell
  .\gradlew.bat :server:run
  ```

#### Two ports: this computer, and paired devices

The server has two doors:

- **8080, plain HTTP, this computer only.** It listens on `127.0.0.1`, so nothing else on the
  network can reach it, and it asks for no token. The web client, the desktop app and the
  **Devices page** (`http://localhost:8080/devices`) use it.
- **8443, HTTPS, for paired devices.** It listens on every interface and serves nothing but the
  sync API, to devices that paired. The certificate is the server's own, made on first start; a
  device trusts it by its fingerprint alone, so there is no domain, authority or renewal involved.

To pair a phone: open the Devices page on the computer, press **Show a pairing code**, and scan it
in the app under **Settings → Pair with a computer** (or paste the link shown under it). The code
carries this computer's addresses, the certificate's fingerprint and a one-time code the phone
trades for a token of its own. `scripts/pair.sh` prints the same QR code in the terminal. The
Devices page lists the paired devices, when each was last seen, and revokes them.

The certificate, the server's id and the paired devices are kept in `~/.korgnote-server`, outside
the notes. Deleting it makes a new certificate, and every device has to pair again.

| Variable | Effect |
|---|---|
| `NOTES_DIR` | Directory of `.org` files to serve. Defaults to the bundled samples. |
| `KORGNOTE_PORT` | The plain port. Defaults to 8080; handy for a second instance while testing. |
| `KORGNOTE_HTTP_HOST` | Where the plain port listens. Defaults to `127.0.0.1`; the container image sets `0.0.0.0` and the compose file publishes it on loopback. |
| `KORGNOTE_HTTPS_PORT` | The port for paired devices. Defaults to 8443. |
| `KORGNOTE_ADVERTISED_PORT` | The HTTPS port a pairing code names, when it is published as another. |
| `KORGNOTE_ADVERTISED_ADDRESSES` | Addresses for pairing codes, comma-separated. Found by the server itself outside a container. |
| `KORGNOTE_SERVER_NAME` | What paired devices call the server. |
| `KORGNOTE_STATE_DIR` | Where the certificate and devices are kept. Defaults to `~/.korgnote-server`. |
| `KORGNOTE_DISABLE_WEB` | `1` to serve the API only, even when a web client build is present. |
| `KORGNOTE_REQUIRE_NOTES_DIR` | `1` to refuse to start when `NOTES_DIR` is missing or not writable, instead of serving the samples. Set by the container image. |

The plain port trusts whoever reaches it, so it must never be reachable from the network. The
server also refuses there a browser page that reached it through another name (DNS rebinding) or
that tries to change notes from another site.

Phones and the Android emulator pair like any other device: the plain port is not theirs to
use, and the app on Android refuses plain HTTP altogether.

#### In a container

A `Dockerfile` and a `docker-compose.yml` at the repository root run the server with a notes
directory from the host. `scripts/run-server-container.sh [NOTES_DIR]` sets everything up: it keeps
the notes directory in `.env` (not committed), makes `~/.korgnote-server` for the state, and passes
the container this computer's addresses and name, which it cannot see from inside. Extra arguments
go to `docker compose up`, e.g. `-d` to run in the background.

The compose file publishes 8443 to the network and 8080 on `127.0.0.1` only. The image is stricter
than a plain run: a missing or unwritable `/notes` stops it rather than falling back to the
samples, so a wrong volume path fails loudly. It runs as `KORGNOTE_UID:KORGNOTE_GID` so notes
created from the app belong to you on the host. `KORGNOTE_HOST_PORT` and `KORGNOTE_HTTPS_HOST_PORT`
change the published ports.

The image also carries the web client, built in the same stage, and serves it at `/` on the plain
port: open `http://localhost:8080/`. Set `KORGNOTE_DISABLE_WEB=1` to serve the API only.

Edits made on the host reach connected clients through the server's file watcher. That has been
checked through a Docker Desktop bind mount on macOS; if a host edit ever does not show up, check the
watcher on the machine that runs the container.

Responses are gzipped when the client asks for it, which matters most for `/api/org-content` — over a
megabyte of text for a real notes directory.

### Build and Run Web Application

The Ktor server is configured to host an existing wasm distribution of the Compose app.
Before running the server, run this:

- on macOS/Linux
  ```shell
  ./gradlew :composeApp:wasmJsBrowserDistribution
  ```
- on Windows
  ```shell
  .\gradlew.bat :composeApp:wasmJsBrowserDistribution
  ```

### Build and Run iOS Application

To build and run the development version of the iOS app, use the run configuration from the run widget
in your IDE’s toolbar or open the [/iosApp](./iosApp) directory in Xcode and run it from there.

### Run Tests

#### One-time setup on macOS: point the tools at Xcode

The iOS tests run in the simulator, which needs `simctl` from the full Xcode. If `xcode-select -p`
prints `/Library/Developer/CommandLineTools`, the terminal build fails with:

```
xcrun: error: unable to find utility "simctl", not a developer tool or in PATH
> Task :shared:iosSimulatorArm64Test FAILED
```

IntelliJ finds Xcode by itself, which is why `allTests` works there but not in the terminal. Fix it once for
the whole machine:

```shell
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
```

Or, without `sudo`, only for the current shell (or put it in `~/.zshrc`):

```shell
export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
```

On Linux and Windows the iOS test tasks are skipped, so this step doesn't apply.

#### Run everything

```shell
./gradlew allTests :server:test --continue
```

- `allTests` is the same task IntelliJ runs. Started from the root, it runs `:shared:allTests` and
  `:composeApp:allTests`: the JVM, iOS simulator and Wasm (headless browser) tests of both modules.
- `:server:test` has to be listed separately. The server is a plain JVM module, so `allTests` does not
  include it.
- `--continue` keeps going after a failing module, so one run reports every failure.

To run only one target or module, which is much faster while working on it:

```shell
./gradlew :shared:jvmTest                 # shared, JVM only
./gradlew :composeApp:jvmTest             # compose UI tests on the desktop runtime
./gradlew :shared:iosSimulatorArm64Test   # shared, iOS simulator only
./gradlew :shared:wasmJsTest              # shared, Wasm in a headless browser
./gradlew :server:test --tests 'com.korgnote.server.FileStoreTest'   # a single class
```

HTML reports are written to `<module>/build/reports/tests/<task>/index.html`. `allTests` also writes a
combined report to `<module>/build/reports/tests/allTests/index.html`.

#### Force tests to run again (bypass the cache)

Gradle skips a test task whose inputs have not changed. It prints `UP-TO-DATE`, or `FROM-CACHE` when it
reuses the result from the build cache (`org.gradle.caching=true` in `gradle.properties`). Nothing runs,
and the old result is reported as a pass. This is usually what you want, but not when chasing a flaky
test or after changing something Gradle cannot see, such as a file under `notes/`.

The `--rerun` flag only affects the task written just before it. It works for a single task:

```shell
./gradlew :shared:jvmTest --rerun
```

but `./gradlew allTests --rerun` only reruns the report task, not the tests it depends on. To rerun
every test without recompiling anything, delete the test results and turn off the build cache for this
run:

```shell
./gradlew cleanAllTests allTests :server:cleanTest :server:test --no-build-cache --continue
```

Without `--no-build-cache`, Gradle restores the deleted results from the cache and prints `FROM-CACHE`
instead of running the tests. You can put `clean` in front of any task name to clean it
(`cleanJvmTest`, `:server:cleanTest`, …).

The heavy option is `--rerun-tasks`. It reruns every task in the build, including all compilation for
every target, so it takes minutes instead of seconds. Only use it if you suspect the build itself.

---

Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html),
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform/#compose-multiplatform),
[Kotlin/Wasm](https://kotl.in/wasm/)…

We would appreciate your feedback on Compose/Web and Kotlin/Wasm in the public Slack
channel [#compose-web](https://slack-chats.kotlinlang.org/c/compose-web).
If you face any issues, please report them on [YouTrack](https://youtrack.jetbrains.com/newIssue?project=CMP).