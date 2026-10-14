package com.andrerinas.openheadunit.decoder.audio

/** Output-thread recovery preserves software staging and the caller's current unwritten block.
 * Lifecycle failures and open failures use the same bounded budget as write errors. */
internal class RecoveringPcmOutput(
    initial: PcmOutput?,
    private val reopen: () -> PcmOutput,
    private val nowMs: () -> Long,
    private val report: (String) -> Unit
) : PcmOutput {
    private var delegate: PcmOutput? = initial
    private var metadata = PcmOutputMetadata(initial)
    private val pending = PendingPcm()
    private var watchdog = OutputWriteWatchdog(stableProgressUnits = 48000 * 2)
    private val playbackVersion = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var attemptedVersion = 0L
    private var started = false
    @Volatile private var retryAtMs = 0L
    private var cooldowns = 0
    private var budgetVersion = 0L
    private var stableSamples = 0L
    @Volatile private var terminal = false
    private var requestedFrames = metadata.minimumBufferFrames
    private var delegateEpoch = initial?.outputEpoch ?: 0L
    override var outputEpoch = 0L
        private set
    override var recoveryProgressSamples = 0L
        private set
    /** Transport records Start; only the output owner mutates the device/recovery state. */
    fun preparePlayback() { playbackVersion.incrementAndGet() }
    val isTerminal get() = terminal
    /** A Start received during the last attempt already authorizes another owner retry. */
    val isParked get() = terminal && playbackVersion.get() == attemptedVersion && nowMs() < retryAtMs
    /** A retained block can retry after cooldown even if the phone sends no further Start. */
    val retryReady get() = terminal && !isParked
    override val hasPendingRecovery get() = !pending.isEmpty || delegate?.hasPendingRecovery == true
    override val name get() = delegate?.name ?: metadata.name
    override val capacityFrames get() = delegate?.capacityFrames ?: metadata.capacityFrames
    override val bufferFrames get() = delegate?.bufferFrames ?: metadata.bufferFrames
    override val burstFrames get() = delegate?.burstFrames ?: metadata.burstFrames
    override val minimumBufferFrames get() = delegate?.minimumBufferFrames ?: metadata.minimumBufferFrames
    override val stagingBufferFrames get() = delegate?.stagingBufferFrames ?: metadata.stagingBufferFrames
    override val underruns get() = delegate?.underruns ?: metadata.underruns
    override val underrunsSupported get() = delegate?.underrunsSupported ?: metadata.underrunsSupported
    override val producerUnderruns get() = delegate?.producerUnderruns ?: metadata.producerUnderruns

    override fun setBufferFrames(frames: Int): Int {
        requestedFrames = frames
        val live = delegate ?: return bufferFrames
        try {
            val result = live.setBufferFrames(maxOf(frames, live.minimumBufferFrames))
            if (result >= 0 || result == -2) return result
            recover("buffer configuration: $result", watchdog.onFailure())
        } catch (e: Exception) { recover("buffer configuration: ${e.message}", watchdog.onFailure()) }
        return bufferFrames
    }

    override fun start() {
        started = true
        try { delegate?.start() }
        catch (e: Exception) { recover("start: ${e.message}", watchdog.onFailure()) }
    }

    override fun pause() {
        started = false
        try { delegate?.pause() }
        catch (e: Exception) { recover("pause: ${e.message}", watchdog.onFailure()) }
    }

    override fun write(data: ShortArray, offset: Int, count: Int): Int {
        val version = playbackVersion.get()
        if (version != budgetVersion) {
            budgetVersion = version
            cooldowns = 0
        }
        if (terminal) {
            if (isParked) return -6
            terminal = false
            watchdog = OutputWriteWatchdog(stableProgressUnits = 48000 * 2)
            retryAtMs = 0
        }
        val live = delegate ?: return if (nowMs() < retryAtMs) 0 else reopenOutput()
        val prefix = !pending.isEmpty
        val beforeProgress = live.recoveryProgressSamples
        val result = try { if (prefix) pending.writeTo(live) else live.write(data, offset, count) }
            catch (_: IllegalArgumentException) { return -2 }
            catch (e: Exception) { report("${live.name} write threw: ${e.message}"); -6 }
        val transferred = (live.recoveryProgressSamples - beforeProgress).coerceAtLeast(0)
        if (live.outputEpoch != delegateEpoch) {
            delegateEpoch = live.outputEpoch
            outputEpoch++
        }
        recoveryProgressSamples += transferred + if (prefix) result.coerceAtLeast(0) else 0
        if (result > 0 || transferred > 0) {
            val progress = result.coerceAtLeast(0).toLong() + transferred
            watchdog.onProgress(progress.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            stableSamples = (stableSamples + progress).coerceAtMost(48000L * 2)
            if (stableSamples >= 48000 * 2) cooldowns = 0
            if (result >= 0) return if (prefix) 0 else result
        }
        if (result == -2) return result // caller contract error
        val action = if (result < 0) watchdog.onFailure() else watchdog.onBlocked(nowMs())
        return recover(if (result < 0) "write: $result" else "500ms write stall", action)
    }

    private fun recover(reason: String, action: OutputWriteWatchdog.Action): Int {
        when (action) {
            OutputWriteWatchdog.Action.WAIT -> return 0
            OutputWriteWatchdog.Action.STOP -> {
                parkForRetry()
                retireForRecovery()
                return -6
            }
            OutputWriteWatchdog.Action.REOPEN -> {
                stableSamples = 0
                report("$name $reason; reopening output")
                retireForRecovery()
                return reopenOutput()
            }
        }
    }

    private fun parkForRetry() {
        // A route may recover without a phone-side Start. Bound each burst of reopen attempts,
        // then allow three automatic bursts after 1s, 2s and 5s. A permanently broken route
        // must not repeatedly interrupt the car radio by reacquiring focus forever. Retain
        // pending PCM after that budget; a new Start or sustained output resets the backoff.
        stableSamples = 0
        val delayMs = when (cooldowns) { 0 -> 1000L; 1 -> 2000L; 2 -> 5000L; else -> null }
        cooldowns = (cooldowns + 1).coerceAtMost(3)
        retryAtMs = if (delayMs == null) Long.MAX_VALUE else nowMs() + delayMs
        terminal = true
        report(if (delayMs == null) "$name automatic recovery exhausted; retaining PCM until Start"
            else "$name recovery paused; retaining PCM and retrying in ${delayMs}ms")
    }

    private fun retireForRecovery() {
        val old = delegate ?: return
        try { metadata = PcmOutputMetadata(old) } catch (_: Exception) { }
        delegate = null
        try { pending.prepend(old.closeForRecovery()) }
        catch (e: Exception) { report("output close failed: ${e.message}") }
    }

    private fun reopenOutput(): Int {
        attemptedVersion = playbackVersion.get()
        var candidate: PcmOutput? = null
        try {
            candidate = reopen()
            check(candidate.setBufferFrames(maxOf(requestedFrames, candidate.minimumBufferFrames)) >= 0)
            if (started) candidate.start()
            metadata = PcmOutputMetadata(candidate)
            delegate = candidate
            delegateEpoch = candidate.outputEpoch
            outputEpoch++
            return 0
        } catch (e: Exception) {
            try { candidate?.let { pending.prepend(it.closeForRecovery()) } } catch (_: Exception) { }
            report("output reopen failed: ${e.message}")
            if (watchdog.onFailure() == OutputWriteWatchdog.Action.STOP) {
                parkForRetry()
                return -6
            }
            retryAtMs = nowMs() + 250
            return 0
        }
    }

    override fun closeForRecovery(): ShortArray {
        retireForRecovery()
        return pending.take()
    }

    override fun close() {
        val old = delegate
        delegate = null
        pending.take()
        old?.close()
    }
}
