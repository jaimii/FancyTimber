package project.kompass.fancyTimber.util

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin

interface SchedulerTask {
    fun cancel()
}

object SchedulerUtil {

    private val isFolia: Boolean = try {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
        true
    } catch (_: ClassNotFoundException) {
        false
    }

    val schedulerName: String
        get() = if (isFolia) "Folia" else "Paper"

    private fun wrap(task: ScheduledTask): SchedulerTask {
        return object : SchedulerTask {
            override fun cancel() {
                task.cancel()
            }
        }
    }

    /**
     * Runs a task on the region thread owning the given Location (Folia-safe).
     */
    fun runRegionTask(plugin: Plugin, location: Location, task: Runnable): SchedulerTask {
        val scheduled = Bukkit.getRegionScheduler().run(plugin, location) { task.run() }
        return wrap(scheduled)
    }

    /**
     * Runs a delayed task on the region thread owning the given Location.
     */
    fun runRegionTaskLater(plugin: Plugin, location: Location, delayTicks: Long, task: Runnable): SchedulerTask {
        val safeDelay = delayTicks.coerceAtLeast(1L)
        val scheduled = Bukkit.getRegionScheduler().runDelayed(plugin, location, { task.run() }, safeDelay)
        return wrap(scheduled)
    }

    /**
     * Runs a repeating timer task on the region thread owning the given Location.
     */
    fun runRegionTaskTimer(plugin: Plugin, location: Location, delayTicks: Long, periodTicks: Long, task: Runnable): SchedulerTask {
        val safeDelay = delayTicks.coerceAtLeast(1L)
        val safePeriod = periodTicks.coerceAtLeast(1L)
        val scheduled = Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, { task.run() }, safeDelay, safePeriod)
        return wrap(scheduled)
    }

    /**
     * Runs a delayed task on the thread owning the specific Entity (used by PacketEventsInterceptor).
     */
    fun runEntityTaskLater(plugin: Plugin, entity: Entity, delayTicks: Long, task: Runnable): SchedulerTask? {
        val safeDelay = delayTicks.coerceAtLeast(1L)
        return entity.scheduler.runDelayed(plugin, { task.run() }, null, safeDelay)?.let(::wrap)
    }

    /**
     * Runs a pure-math task asynchronously on the Paper/Folia AsyncScheduler worker pool
     * (used by BlockBreakListener to pre-calculate animation frames).
     */
    fun runAsync(plugin: Plugin, task: Runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin) { task.run() }
    }
}