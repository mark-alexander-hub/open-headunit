package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.direct.P2pGroupIdentityPolicy
import com.andrerinas.openheadunit.connection.wifi.direct.StoredP2pIdentity

/**
 * Whether the WiFi Direct network a WPP endpoint was advertised under has to come back once.
 *
 * A phone holding that endpoint joins only the network it was stored with, never falls back to
 * Bluetooth, and can only be told to drop it by a rejection on that network. So a rename brings
 * the advertised pair up one more time, endpoint withheld, before the new one goes on the air.
 */
object EndpointRetirementPolicy {

    /**
     * Whether a name this app asks for can reach the air on this unit.
     *
     * The platform offering the API is not the unit honouring it: some firmware refuses every named
     * create and hosts only its own stored profile. There the kept pair never goes on the air, so
     * comparing it with the advertised one reads every bring-up as a rename, and the phone's dial
     * to a network that has not moved is rejected every second connection. Such a unit is judged
     * like one that cannot name a group at all.
     *
     * @param namedRefusedOn the build fingerprint the refusal was measured on, or null.
     */
    fun appNamesGroup(platformNamesGroups: Boolean, namedRefusedOn: String?, runningBuild: String): Boolean =
        platformNamesGroups && !(namedRefusedOn != null && namedRefusedOn == runningBuild)

    /**
     * Whether the next create owes the advertised network a retirement.
     *
     * Where the app names the group, anything but the kept pair is a move. Below API 29 the platform
     * reinvokes its own profile, so only a pending purge moves the name.
     */
    fun owed(
        advertised: StoredP2pIdentity?,
        keepIdentity: Boolean,
        kept: StoredP2pIdentity?,
        appNamesGroup: Boolean,
        rotationPending: Boolean,
    ): Boolean {
        if (advertised == null) return false
        return if (appNamesGroup) !(keepIdentity && kept == advertised)
        else !keepIdentity || rotationPending
    }

    /** Whether the group on the air is the advertised one being retired rather than a live identity. */
    fun isRetiring(
        advertised: StoredP2pIdentity?,
        keepIdentity: Boolean,
        kept: StoredP2pIdentity?,
        appNamesGroup: Boolean,
        rotationPending: Boolean,
        liveName: String,
        livePassphrase: String,
    ): Boolean =
        owed(advertised, keepIdentity, kept, appNamesGroup, rotationPending) &&
            advertised?.networkName == liveName && advertised.passphrase == livePassphrase

    /** The pair to remember once an endpoint went out on [strategy], or null when there is none. */
    fun recordsAdvertisement(strategy: NativeStrategy, liveName: String, livePassphrase: String): StoredP2pIdentity? {
        if (strategy != NativeStrategy.WIFI_DIRECT) return null
        return StoredP2pIdentity(liveName, livePassphrase).takeIf { P2pGroupIdentityPolicy.isValid(it) }
    }
}
