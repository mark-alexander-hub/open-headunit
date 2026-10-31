package com.andrerinas.openheadunit.trip

import android.content.Context
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.OemAppManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The hottest the unit got during a trip: the CPU and the battery, from the kernel's thermal zones.
 *
 * Record only - nothing is shown or changed while driving. An old phone on a dashboard is mostly
 * at risk from heat, and whether this one ever gets near trouble is the question these numbers
 * answer before anything acts on them.
 *
 * Many units (MediaTek on a GSI among them) keep the zones behind SELinux and report no battery
 * temperature to Android at all, so the zones are read through the same root or local ADB route as
 * the airplane mode. Root is never requested from here: no route already open means no reading.
 */
object TripHeat {

    private const val SAMPLE_MS = 60_000L
    /** Sensors that are absent or unplugged read far outside anything real. */
    private val PLAUSIBLE = -20_000..120_000
    private const val READ_ZONES =
        "for z in /sys/class/thermal/thermal_zone*; do echo \"\$(cat \$z/type) \$(cat \$z/temp)\"; done"

    data class Reading(val cpuMilliC: Int, val batteryMilliC: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile var cpuMax = -1; private set
    @Volatile var batteryMax = -1; private set

    /** A resumed trip carries its maxima on; a new one starts from nothing. */
    @Synchronized
    fun start(context: Context, cpuSoFar: Int, batterySoFar: Int) {
        stop()
        cpuMax = cpuSoFar
        batteryMax = batterySoFar
        val app = context.applicationContext
        job = scope.launch {
            while (isActive) {
                delay(SAMPLE_MS)
                sample(app)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun sample(context: Context) {
        if (OemAppManager.checkAvailableBackend() == OemAppManager.ExecutionBackend.NONE) return
        val (code, out) = try {
            OemAppManager.executeCommand(context, READ_ZONES)
        } catch (e: Exception) {
            return
        }
        if (code != 0) return
        val reading = parse(out) ?: return
        if (reading.cpuMilliC > cpuMax) cpuMax = reading.cpuMilliC
        if (reading.batteryMilliC > batteryMax) batteryMax = reading.batteryMilliC
        AppLog.d("TripHeat: cpu ${reading.cpuMilliC / 1000.0} °C, battery ${reading.batteryMilliC / 1000.0} °C")
    }

    /**
     * Lines of "type milli-degrees". The CPU is the hottest zone whose type names the CPU, the
     * battery the hottest naming the battery; -1 for either when no zone says so.
     */
    fun parse(output: String): Reading? {
        var cpu = -1
        var battery = -1
        output.lineSequence().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@forEach
            val type = parts[0].lowercase()
            val temp = parts[1].toIntOrNull() ?: return@forEach
            if (temp !in PLAUSIBLE) return@forEach
            when {
                "cpu" in type -> if (temp > cpu) cpu = temp
                "battery" in type -> if (temp > battery) battery = temp
            }
        }
        return if (cpu < 0 && battery < 0) null else Reading(cpu, battery)
    }
}
