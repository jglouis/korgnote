# Screenshots

These screenshots show the web app at phone width, using the sample notes in [notes/](notes).
The notes are dated around late October 2026. `overview.png`, at the top of the main README, shows
four of these views side by side.

To take them again, run a separate server with these notes, so your real server is not touched:

```shell
./gradlew :composeApp:wasmJsBrowserDistribution :server:installDist
NOTES_DIR=docs/screenshots/notes KORGNOTE_REQUIRE_NOTES_DIR=1 \
KORGNOTE_PORT=18080 KORGNOTE_HTTPS_PORT=18443 KORGNOTE_STATE_DIR=$(mktemp -d) \
WASM_DIR=$PWD/composeApp/build/dist/wasmJs/productionExecutable server/build/install/server/bin/server
```

Then open <http://127.0.0.1:18080/> in a browser window 400 px wide.

## Store screenshots

The store listing's screenshots, in
[fastlane/…/phoneScreenshots](../../fastlane/metadata/android/en-US/images/phoneScreenshots), are of
the Android app on a 1080×2400 phone, with the same notes. Use a phone without your own notes:

```shell
adb push docs/screenshots/notes/. /sdcard/Documents/Notes/
adb install androidApp/build/outputs/apk/release/androidApp-release.apk
```

Choose **A folder on this device**, then `Documents/Notes`, and **Not now** for notifications. Take
each view with `adb exec-out screencap -p > shot.png`, and crop off the status and navigation bars:
`sips -c 2152 1080 --cropOffset 110 0 shot.png`. The code block's lines are kept short so they fit
the phone's width.
