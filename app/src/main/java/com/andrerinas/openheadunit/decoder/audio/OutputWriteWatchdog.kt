package com.andrerinas.openheadunit.decoder.audio

/** A nonblocking device write must eventually progress even when it reports no error.
 * A fresh output is allowed twice without progress in each recovery burst; the owner must
 * stop or cool down before granting another burst on a broken route. */
internal class OutputWriteWatchdog(
    private val stableProgressUnits: Int = 1,
    /**
     * How long a write may block on an output that is fresh or has sat idle, before it counts as
     * stalled. A Bluetooth sink suspends its stream a few seconds after the sound stops and needs
     * about a second to start it again, during which nothing is accepted; judged by [STALL_MS],
     * every prompt into a quiet car radio was a "stall" and the reopen it earned cost the start of
     * the prompt. The default leaves it at [STALL_MS], which is the behaviour without this.
     */
    private val wakeGraceMs: Long = STALL_MS,
) {
    companion object {
        const val STALL_MS = 500L
        /** Shorter than any sink's suspend delay, longer than a gap between two writes. */
        const val IDLE_BEFORE_WAKE_MS = 2000L
    }
    enum class Action { WAIT, REOPEN, STOP }
    private var blockedSinceMs = -1L
    private var lastProgressMs = -1L
    private var recoveryAttempts = 0
    private var progressUnits = 0L

    /**
     * Zero clears only the blocked-write timer when the owner tracks sustained health itself.
     * [nowMs], when given, is what tells a write into a running stream from one that has to wake it.
     */
    fun onProgress(units: Int = 1, nowMs: Long = -1L) {
        blockedSinceMs = -1L
        if (nowMs >= 0) lastProgressMs = nowMs
        progressUnits = (progressUnits + units).coerceAtMost(stableProgressUnits.toLong())
        if (progressUnits >= stableProgressUnits) recoveryAttempts = 0
    }

    fun onBlocked(nowMs: Long): Action {
        if (blockedSinceMs < 0) blockedSinceMs = nowMs
        val waking = lastProgressMs < 0 || blockedSinceMs - lastProgressMs >= IDLE_BEFORE_WAKE_MS
        if (nowMs - blockedSinceMs < (if (waking) wakeGraceMs else STALL_MS)) return Action.WAIT
        return onFailure()
    }

    /** A dead AudioTrack cannot make progress by waiting for writable space. */
    fun onFailure(): Action {
        blockedSinceMs = -1L
        progressUnits = 0
        return if (recoveryAttempts++ < 2) Action.REOPEN else Action.STOP
    }
}
