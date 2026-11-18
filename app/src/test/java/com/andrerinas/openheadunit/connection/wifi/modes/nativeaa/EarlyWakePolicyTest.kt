package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EarlyWakePolicyTest {

    private val none = Triple("", "", "")
    private val real = Triple("DIRECT-ab", "192.168.49.1", "aa:bb:cc:dd:ee:ff")

    @Test
    fun `wakes early once the listeners are open`() {
        assertTrue(
            EarlyWakePolicy.mayWakeBeforeCredentials(
                listenersOpen = true, credentialsPresent = false,
                userExited = false, sessionUp = false
            )
        )
    }

    @Test
    fun `never wakes before the listeners are accepting`() {
        assertFalse(
            EarlyWakePolicy.mayWakeBeforeCredentials(
                listenersOpen = false, credentialsPresent = false,
                userExited = false, sessionUp = false
            )
        )
    }

    @Test
    fun `credentials already there is the ordinary path, not the early one`() {
        assertFalse(
            EarlyWakePolicy.mayWakeBeforeCredentials(
                listenersOpen = true, credentialsPresent = true,
                userExited = false, sessionUp = false
            )
        )
    }

    @Test
    fun `a user exit and a live session both hold the wake`() {
        assertFalse(
            EarlyWakePolicy.mayWakeBeforeCredentials(
                listenersOpen = true, credentialsPresent = false,
                userExited = true, sessionUp = false
            )
        )
        assertFalse(
            EarlyWakePolicy.mayWakeBeforeCredentials(
                listenersOpen = true, credentialsPresent = false,
                userExited = false, sessionUp = true
            )
        )
    }

    @Test
    fun `credentials arriving under an early loop does not restart it`() {
        assertFalse(EarlyWakePolicy.shouldRestartLoop(loopActive = true, previousKey = none, newKey = real))
    }

    @Test
    fun `an unchanged key does not restart the loop`() {
        assertFalse(EarlyWakePolicy.shouldRestartLoop(loopActive = true, previousKey = real, newKey = real))
    }

    @Test
    fun `a genuinely new group restarts the loop`() {
        val other = Triple("DIRECT-zz", "192.168.49.1", "11:22:33:44:55:66")
        assertTrue(EarlyWakePolicy.shouldRestartLoop(loopActive = true, previousKey = real, newKey = other))
    }

    @Test
    fun `no loop running always starts one`() {
        assertTrue(EarlyWakePolicy.shouldRestartLoop(loopActive = false, previousKey = real, newKey = real))
        assertTrue(EarlyWakePolicy.shouldRestartLoop(loopActive = false, previousKey = null, newKey = none))
    }

    @Test
    fun `credentials going away restarts the loop`() {
        assertTrue(EarlyWakePolicy.shouldRestartLoop(loopActive = true, previousKey = real, newKey = none))
    }

    // --- a wake that brought the phone back to nothing is not repeated ---

    @Test
    fun `an early loop stops once its handshake ended for lack of credentials`() {
        assertTrue(EarlyWakePolicy.stopAfterCredentialsFailure(loopStartedEmpty = true, credentialsPresent = false))
    }

    @Test
    fun `a credentialed loop, or one whose credentials exist now, keeps going`() {
        assertFalse(EarlyWakePolicy.stopAfterCredentialsFailure(loopStartedEmpty = false, credentialsPresent = false))
        assertFalse(EarlyWakePolicy.stopAfterCredentialsFailure(loopStartedEmpty = true, credentialsPresent = true))
    }

    @Test
    fun `a second empty wake waits for the credentials`() {
        assertFalse(EarlyWakePolicy.mayStartWithoutCredentials(keyIsEmpty = true, earlyWakeSpent = true))
        assertTrue(EarlyWakePolicy.mayStartWithoutCredentials(keyIsEmpty = true, earlyWakeSpent = false))
    }

    @Test
    fun `a credentialed wake is never held by a spent early one`() {
        assertTrue(EarlyWakePolicy.mayStartWithoutCredentials(keyIsEmpty = false, earlyWakeSpent = true))
    }

    @Test
    fun `an empty key is all three fields empty`() {
        assertTrue(EarlyWakePolicy.isEmptyKey(none))
        assertFalse(EarlyWakePolicy.isEmptyKey(real))
        assertFalse(EarlyWakePolicy.isEmptyKey(Triple("DIRECT-ab", "", "")))
    }

    @Test
    fun `a phone that holds this unit's endpoint is not woken before the network exists`() {
        assertTrue(EarlyWakePolicy.mayWakeBeforeCredentials(true, false, false, false))
        assertFalse(EarlyWakePolicy.mayWakeBeforeCredentials(true, false, false, false, phoneHoldsEndpoint = true))
        assertEquals(4_000, EarlyWakePolicy.credentialWaitMs(false))
        assertEquals(20_000, EarlyWakePolicy.credentialWaitMs(true))
    }
}
