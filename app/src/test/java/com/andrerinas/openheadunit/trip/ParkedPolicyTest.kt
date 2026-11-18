package com.andrerinas.openheadunit.trip

import com.andrerinas.openheadunit.aap.EarphoneBoost
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkedPolicyTest {

    @Test
    fun `a lit screen with a Parked app open keeps Android Auto asleep`() {
        assertTrue(ParkedPolicy.holdsAutoStart(1_000, screenOn = true))
        assertTrue(ParkedPolicy.holdsAutoStart(ParkedPolicy.MAX_HOLD_MS - 1, screenOn = true))
    }

    @Test
    fun `a dark screen is a drive beginning, not a film`() {
        assertFalse(ParkedPolicy.holdsAutoStart(1_000, screenOn = false))
        // Unreadable counts as dark: a ride without Android Auto is the worse mistake.
        assertFalse(ParkedPolicy.holdsAutoStart(1_000, screenOn = null))
    }

    @Test
    fun `the hold ends by itself and does not survive a reboot`() {
        assertFalse(ParkedPolicy.holdsAutoStart(ParkedPolicy.MAX_HOLD_MS, screenOn = true))
        // The clock restarts with the unit, so a hold from before a reboot reads as negative.
        assertFalse(ParkedPolicy.holdsAutoStart(-5_000, screenOn = true))
    }

    @Test
    fun `earphones left in while the car radio plays carry no sound`() {
        assertTrue(EarphoneBoost.earphonesCarrySound(earphonesIn = true, bluetoothMedia = false))
        assertFalse(EarphoneBoost.earphonesCarrySound(earphonesIn = true, bluetoothMedia = true))
        assertFalse(EarphoneBoost.earphonesCarrySound(earphonesIn = false, bluetoothMedia = false))
    }
}
