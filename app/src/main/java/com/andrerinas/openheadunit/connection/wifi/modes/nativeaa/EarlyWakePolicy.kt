package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Whether the wake poke may start before the WiFi credentials exist.
 *
 * The wake used to be triggered only by a credential delivery, so the phone was not woken until the
 * group was up, its info had come back and its IP had resolved. The handshake does not need that
 * ordering: it sends the version exchange first and waits for credentials afterwards, so the
 * phone's own wake latency can run alongside the group forming instead of after it.
 */
object EarlyWakePolicy {

    /**
     * [listenersOpen] is the manager's "started, and the listener not closed for this session"
     * read. A phone woken to a head unit with no listener finds nothing to dial and the log still
     * says the poke worked.
     */
    fun mayWakeBeforeCredentials(
        listenersOpen: Boolean,
        credentialsPresent: Boolean,
        userExited: Boolean,
        sessionUp: Boolean,
        phoneHoldsEndpoint: Boolean = false,
    ): Boolean = listenersOpen && !credentialsPresent && !userExited && !sessionUp && !phoneHoldsEndpoint

    /** The wait for credentials before an ordinary wake, which then goes ahead without them. */
    const val CREDENTIAL_WAIT_MS = 4_000

    /** Long enough for a group that needed a WiFi restart first; measured at 5 to 8 s. */
    const val CREDENTIAL_WAIT_WITH_ENDPOINT_MS = 20_000

    /**
     * How long a wake waits for the network to exist.
     *
     * A phone that was handed this unit's endpoint does not wait to be told the network over
     * Bluetooth: the wake alone sends it looking for the network it stored. Woken a moment before
     * the group was on the air it found nothing, and came back 20 s later. So for that phone the
     * head start is not worth having, and the wake waits for the group.
     */
    fun credentialWaitMs(phoneHoldsEndpoint: Boolean): Int =
        if (phoneHoldsEndpoint) CREDENTIAL_WAIT_WITH_ENDPOINT_MS else CREDENTIAL_WAIT_MS

    /**
     * Whether a `triggerPoke()` with a new credential key should replace the running loop.
     *
     * False when the only change is credentials arriving under a loop that started without them:
     * the loop re-reads them every pass, so restarting it throws away the head start an early wake
     * just bought and pays the entry wait again.
     */
    fun shouldRestartLoop(
        loopActive: Boolean,
        previousKey: Triple<String, String, String>?,
        newKey: Triple<String, String, String>,
    ): Boolean = when {
        !loopActive -> true
        previousKey == newKey -> false
        previousKey != null && isEmptyKey(previousKey) && !isEmptyKey(newKey) -> false
        else -> true
    }

    /**
     * Whether a wake loop may start with no credentials. Once one has brought the phone back to a
     * head unit with no network, the next wake waits for the credentials to exist.
     */
    fun mayStartWithoutCredentials(keyIsEmpty: Boolean, earlyWakeSpent: Boolean): Boolean =
        !keyIsEmpty || !earlyWakeSpent

    /**
     * Whether a handshake that ended for lack of credentials should stop the running wake loop.
     * Only a loop that started empty: one started by a credential delivery is the ordinary wake,
     * and a group that went away under it is re-delivered by the recreate.
     */
    fun stopAfterCredentialsFailure(loopStartedEmpty: Boolean, credentialsPresent: Boolean): Boolean =
        loopStartedEmpty && !credentialsPresent

    fun isEmptyKey(key: Triple<String, String, String>): Boolean =
        key.first.isEmpty() && key.second.isEmpty() && key.third.isEmpty()
}
