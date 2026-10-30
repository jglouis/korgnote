// On wasm, Compose resources fetch the app's strings over HTTP from /composeResources/, which
// Karma doesn't serve on its own; without this every test that reads a string gets a 404.
// basePath is <root>/build/wasm/packages/<module>-test.
const path = require("path");
const composeResources = path.resolve(
    config.basePath,
    "../../../../composeApp/build/processedResources/wasmJs/main/composeResources",
);
config.files.push({
    pattern: `${composeResources}/**/*`,
    included: false,
    served: true,
    watched: false,
    nocache: true,
});
config.proxies["/composeResources/"] = `/absolute${composeResources}/`;
