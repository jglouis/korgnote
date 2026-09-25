package com.korgnote.server

/**
 * How the server exposes itself: which interface it listens on, and whether a token is required.
 *
 * The default is the safe one — loopback only, so an unauthenticated server is not reachable from
 * the network at all. Setting `KORGNOTE_TOKEN` opens it to the LAN behind bearer auth.
 *
 * `KORGNOTE_ALLOW_LAN=1` opens it to the LAN with **no** authentication. That exists because the
 * app is developed against real phones over a LAN, and making that require a token every time would
 * be a tax on every test run. It is a deliberate, logged, opt-in hole: never use it on a network you
 * do not control.
 *
 * `KORGNOTE_REQUIRE_TOKEN=1` turns a missing token into a refusal to start. The container image sets
 * it, because a container is exactly where a port gets published by accident, and inside one the
 * loopback default would only make the server unreachable rather than safe.
 */
data class ServerSecurity(
    val token: String?,
    val allowLanWithoutToken: Boolean,
    val tokenMandatory: Boolean = false,
) {
    /** Loopback unless something has explicitly made it safe — or explicitly accepted the risk. */
    val bindHost: String
        get() = if (token != null || allowLanWithoutToken) ALL_INTERFACES else LOOPBACK

    val requiresToken: Boolean get() = token != null

    val reachableFromNetwork: Boolean get() = bindHost == ALL_INTERFACES

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val ALL_INTERFACES = "0.0.0.0"

        const val TOKEN_VARIABLE = "KORGNOTE_TOKEN"
        const val ALLOW_LAN_VARIABLE = "KORGNOTE_ALLOW_LAN"
        const val REQUIRE_TOKEN_VARIABLE = "KORGNOTE_REQUIRE_TOKEN"

        fun fromEnvironment(read: (String) -> String? = System::getenv): ServerSecurity =
            ServerSecurity(
                token = read(TOKEN_VARIABLE)?.trim()?.takeUnless { it.isEmpty() },
                allowLanWithoutToken = isSet(read(ALLOW_LAN_VARIABLE)),
                tokenMandatory = isSet(read(REQUIRE_TOKEN_VARIABLE)),
            )

        private fun isSet(value: String?): Boolean = value?.trim()?.lowercase() in setOf("1", "true", "yes")

        /** No token and reachable: what the test host and local development use. */
        fun open(): ServerSecurity = ServerSecurity(token = null, allowLanWithoutToken = true)
    }

    /** Why this configuration must not start, or null when it may. */
    fun startupError(): String? =
        if (tokenMandatory && token == null) {
            "$REQUIRE_TOKEN_VARIABLE is set but $TOKEN_VARIABLE is not: refusing to start without a token. " +
                "Generate one with `openssl rand -hex 16`."
        } else {
            null
        }

    /** A line for the log explaining, in one sentence, what this server will accept. */
    fun describe(): String = when {
        requiresToken -> "Listening on $bindHost; /api requires a bearer token ($TOKEN_VARIABLE)."
        allowLanWithoutToken ->
            "Listening on $bindHost with NO authentication because $ALLOW_LAN_VARIABLE is set. " +
                "Convenient for testing on a device; unsafe on a shared network."
        else ->
            "Listening on $bindHost (loopback only). Set $TOKEN_VARIABLE to serve the LAN with a " +
                "token, or $ALLOW_LAN_VARIABLE=1 to serve it without one while testing."
    }
}
