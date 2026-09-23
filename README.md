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

#### Server options

| Variable | Effect |
|---|---|
| `NOTES_DIR` | Directory of `.org` files to serve. Defaults to the bundled samples. |
| `KORGNOTE_TOKEN` | Require `Authorization: Bearer <token>` on `/api`, and listen on all interfaces. |
| `KORGNOTE_ALLOW_LAN` | `1` to listen on all interfaces **without** a token. For testing only. |
| `KORGNOTE_PORT` | Port to listen on. Defaults to 8080; handy for a second instance while testing. |

With no variables set the server listens on **127.0.0.1 only**, so an unauthenticated server is not
reachable from the network. It logs, on startup, one line saying which of these three modes it is in.

Testing against a phone or another machine needs one of the two open modes:

```shell
# Convenient: reachable on the LAN, no token to type into the app.
NOTES_DIR=~/notes KORGNOTE_ALLOW_LAN=1 ./gradlew :server:run

# Safer: reachable on the LAN, but every request must carry the token.
NOTES_DIR=~/notes KORGNOTE_TOKEN=$(openssl rand -hex 16) ./gradlew :server:run
```

With a token, put the same value in the app under **Settings → Access token**. Leave that field empty
when the server runs without one.

`KORGNOTE_ALLOW_LAN` is a deliberate hole, kept because developing against a real device otherwise
means re-entering a token on every device after every reinstall. It is safe on a home network you
control and unsafe on anything shared — a café, an office, a conference.

#### TLS

The server speaks plain HTTP and does not terminate TLS. Traffic on a LAN you control is usually
fine; anything crossing a network you do not control should go through a reverse proxy that holds
the certificate, with the server bound to loopback behind it:

```nginx
server {
    listen 443 ssl;
    server_name notes.example.com;

    ssl_certificate     /etc/letsencrypt/live/notes.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/notes.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

Set `KORGNOTE_TOKEN` as well: the proxy protects the traffic, the token protects the endpoint. Point
the app at `https://notes.example.com` (host field) with port `443`.

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