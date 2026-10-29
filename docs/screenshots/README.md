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
