# Screenshots

The pictures in the main README are of the web client at a phone's width, showing the made-up notes
in [notes/](notes). Its dates are around the end of October 2026, so the calendar shows them on the
screen it opens on only in that week.

To take them again, serve these notes from a server of their own, on ports and a state directory
that leave a real one alone:

```shell
./gradlew :composeApp:wasmJsBrowserDistribution :server:installDist
NOTES_DIR=docs/screenshots/notes KORGNOTE_REQUIRE_NOTES_DIR=1 \
KORGNOTE_PORT=18080 KORGNOTE_HTTPS_PORT=18443 KORGNOTE_STATE_DIR=$(mktemp -d) \
WASM_DIR=$PWD/composeApp/build/dist/wasmJs/productionExecutable server/build/install/server/bin/server
```

then open <http://127.0.0.1:18080/> in a browser 400 px wide.
