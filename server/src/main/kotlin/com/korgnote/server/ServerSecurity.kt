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
 */
data class ServerSecurity(
    val token: String?,
    val allowLanWithoutToken: Boolean,
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

        fun fromEnvironment(read: (String) -> String? = System::getenv): ServerSecurity =
            ServerSecurity(
                token = read(TOKEN_VARIABLE)?.trim()?.takeUnless { it.isEmpty() },
                allowLanWithoutToken = read(ALLOW_LAN_VARIABLE)?.trim()?.lowercase()
                    in setOf("1", "true", "yes"),
            )

        /** No token and reachable: what the test host and local development use. */
        fun open(): ServerSecurity = ServerSecurity(token = null, allowLanWithoutToken = true)
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
