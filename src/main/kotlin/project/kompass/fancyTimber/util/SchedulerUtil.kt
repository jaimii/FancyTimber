package project.kompass.fancyTimber.util

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

interface SchedulerTask {
    fun cancel()
    val isCancelled: Boolean
}

object SchedulerUtil {

    val isFolia: Boolean = try {
        Class.forName("io.papermc.paper.threadedregions.RegionizedServer")
        true
    } catch (e: ClassNotFoundException) {
        false
    }

    val schedulerName: String
        get() = if (isFolia) "Folia" else "Paper"

    private fun wrap(task: ScheduledTask?): SchedulerTask? {
        if (task == null) return null
        return object : SchedulerTask {
            override fun cancel() {
                task.cancel()
            }

            override val isCancelled: Boolean
                get() = task.isCancelled
        }
    }

    /**
     * Runs a task on the global region thread (or main thread on standard Paper).
     */
    fun runTask(plugin: Plugin, task: Runnable): SchedulerTask {
        val scheduled = Bukkit.getGlobalRegionScheduler().run(plugin) { task.run() }
        return wrap(scheduled)!!
    }

    /**
     * Runs a delayed task on the global region thread.
     */
    fun runTaskLater(plugin: Plugin, delayTicks: Long, task: Runnable): SchedulerTask {
        val safeDelay = delayTicks.coerceAtLeast(1L)
        val scheduled = Bukkit.getGlobalRegionScheduler().runDelayed(plugin, { task.run() }, safeDelay)
        return wrap(scheduled)!!
    }

    /**
     * Runs a repeating timer task on the global region thread.
     */
    fun runTaskTimer(plugin: Plugin, delayTicks: Long, periodTicks: Long, task: Runnable): SchedulerTask {
        val safeDelay = delayTicks.coerceAtLeast(1L)
        val safePeriod = periodTicks.coerceAtLeast(1L)
        val scheduled = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, { task.run() }, safeDelay, safePeriod)
        return wrap(scheduled)!!
    }

    /**
     * Runs a task on the specific region thread owning the given Location (Folia-safe).
     */
    fun runRegionTask(plugin: Plugin, location: Location, task: Runnable): SchedulerTask {
        val scheduled = Bukkit.getRegionScheduler().run(plugin, location) { task.run() }
        return wrap(scheduled)!!
    }

    /**
     * Runs a delayed task on the specific region thread owning the given Location.
     */
    fun runRegionTaskLater(plugin: Plugin, location: Location, delayTicks: Long, task: Runnable): SchedulerTask {
        val safeDelay = delayTicks.coerceAtLeast(1L)
        val scheduled = Bukkit.getRegionScheduler().runDelayed(plugin, location, { task.run() }, safeDelay)
        return wrap(scheduled)!!
    }

    /**
     * Runs a repeating timer task on the specific region thread owning the given Location.
     */
    fun runRegionTaskTimer(plugin: Plugin, location: Location, delayTicks: Long, periodTicks: Long, task: Runnable): SchedulerTask {
        val safeDelay = delayTicks.coerceAtLeast(1L)
        val safePeriod = periodTicks.coerceAtLeast(1L)
        val scheduled = Bukkit.getRegionScheduler().runAtFixedRate(plugin, location, { task.run() }, safeDelay, safePeriod)
        return wrap(scheduled)!!
    }

    /**
     * Runs a task on the thread owning the specific Entity (e.g., a Player or BlockDisplay).
     */
    fun runEntityTask(plugin: Plugin, entity: Entity, task: Runnable): SchedulerTask? {
        val scheduled = entity.scheduler.run(plugin, { task.run() }, null)
        return wrap(scheduled)
    }

    /**
     * Runs a delayed task on the thread owning the specific Entity.
     */
    fun runEntityTaskLater(plugin: Plugin, entity: Entity, delayTicks: Long, task: Runnable): SchedulerTask? {
        val safeDelay = delayTicks.coerceAtLeast(1L)
        val scheduled = entity.scheduler.runDelayed(plugin, { task.run() }, null, safeDelay)
        return wrap(scheduled)
    }

    /**
     * Runs a task asynchronously on the Paper AsyncScheduler worker pool.
     */
    fun runAsync(plugin: Plugin, task: Runnable) {
        Bukkit.getAsyncScheduler().runNow(plugin) { task.run() }
    }

    /**
     * Runs a delayed task asynchronously on the Paper AsyncScheduler worker pool.
     */
    fun runAsyncLater(plugin: Plugin, delayTicks: Long, task: Runnable) {
        val delayMs = (delayTicks.coerceAtLeast(1L)) * 50L
        Bukkit.getAsyncScheduler().runDelayed(plugin, { task.run() }, delayMs, TimeUnit.MILLISECONDS)
    }

    /**
     * Supplies a value asynchronously and returns a CompletableFuture.
     */
    fun <T> supplyAsync(plugin: Plugin, supplier: () -> T): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        runAsync(plugin) {
            try {
                future.complete(supplier())
            } catch (t: Throwable) {
                future.completeExceptionally(t)
            }
        }
        return future
    }
}