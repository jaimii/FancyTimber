package project.kompass.fancyTimber.util

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Method

object ProtectionManager {

    private var initialized = false
    private var hasAnyProtection = false

    // Cached WorldGuard reflection handles
    private var hasWorldGuard = false
    private var wgRegionContainer: Any? = null
    private var wgCreateQueryMethod: Method? = null
    private var wgAdaptLocMethod: Method? = null
    private var wgPluginInst: Any? = null
    private var wgWrapPlayerMethod: Method? = null
    private var wgFlagArray: Any? = null
    private var wgTestStateMethod: Method? = null

    // Cached GriefPrevention reflection handles
    private var hasGriefPrevention = false
    private var gpDataStore: Any? = null
    private var gpGetClaimAtMethod: Method? = null

    // Cached Towny reflection handles
    private var hasTowny = false
    private var townyInstance: Any? = null
    private var townyDestroyMethod: Method? = null

    // Cached LWC reflection handles
    private var hasLWC = false
    private var lwcInstance: Any? = null
    private var lwcFindProtectionMethod: Method? = null
    private var lwcCanAccessMethod: Method? = null

    private val simulating = ThreadLocal.withInitial { false }

    fun isSimulating(): Boolean = simulating.get()

    fun warmup() {
        ensureInitialized()
    }

    @Synchronized
    private fun ensureInitialized() {
        if (initialized) return

        // 1. Initialize WorldGuard cache
        if (Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            try {
                val wgClass = Class.forName("com.sk89q.worldguard.WorldGuard")
                val wgInstance = wgClass.getMethod("getInstance").invoke(null)
                val platform = wgClass.getMethod("getPlatform").invoke(wgInstance)
                wgRegionContainer = platform.javaClass.getMethod("getRegionContainer").invoke(platform)
                wgCreateQueryMethod = wgRegionContainer!!.javaClass.getMethod("createQuery")

                val bukkitAdapterClass = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter")
                wgAdaptLocMethod = bukkitAdapterClass.getMethod("adapt", Location::class.java)

                val wgPluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin")
                wgPluginInst = wgPluginClass.getMethod("inst").invoke(null)
                wgWrapPlayerMethod = wgPluginInst!!.javaClass.getMethod("wrapPlayer", Player::class.java)

                val flagsClass = Class.forName("com.sk89q.worldguard.protection.flags.Flags")
                val buildFlag = flagsClass.getField("BUILD").get(null)
                val stateFlagClass = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag")
                wgFlagArray = ReflectArray.newInstance(stateFlagClass, 1).also {
                    ReflectArray.set(it, 0, buildFlag)
                }

                val queryObj = wgCreateQueryMethod!!.invoke(wgRegionContainer)
                val localPlayerClass = Class.forName("com.sk89q.worldguard.LocalPlayer")
                val locClass = Class.forName("com.sk89q.worldedit.util.Location")
                wgTestStateMethod = queryObj.javaClass.getMethod("testState", locClass, localPlayerClass, wgFlagArray!!.javaClass)
                hasWorldGuard = true
            } catch (t: Throwable) {
                hasWorldGuard = false
            }
        }

        // 2. Initialize GriefPrevention cache
        if (Bukkit.getPluginManager().isPluginEnabled("GriefPrevention")) {
            try {
                val gpClass = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention")
                val gpInstance = gpClass.getMethod("instance").invoke(null)
                gpDataStore = gpClass.getField("dataStore").get(gpInstance)
                val claimClass = Class.forName("me.ryanhamshire.GriefPrevention.Claim")
                gpGetClaimAtMethod = gpDataStore!!.javaClass.getMethod(
                    "getClaimAt",
                    Location::class.java,
                    Boolean::class.javaPrimitiveType,
                    claimClass
                )
                hasGriefPrevention = true
            } catch (t: Throwable) {
                hasGriefPrevention = false
            }
        }

        // 3. Initialize Towny cache
        if (Bukkit.getPluginManager().isPluginEnabled("Towny")) {
            try {
                val townyAPIClass = Class.forName("com.palmergames.bukkit.towny.TownyAPI")
                townyInstance = townyAPIClass.getMethod("getInstance").invoke(null)
                townyDestroyMethod = townyAPIClass.getMethod("isDestroyApproved", Player::class.java, Location::class.java)
                hasTowny = true
            } catch (t: Throwable) {
                hasTowny = false
            }
        }

        // 4. Initialize LWC cache
        if (Bukkit.getPluginManager().isPluginEnabled("LWC")) {
            try {
                val lwcClass = Class.forName("com.griefcraft.lwc.LWC")
                lwcInstance = lwcClass.getMethod("getInstance").invoke(null)
                lwcFindProtectionMethod = lwcClass.getMethod("findProtection", Block::class.java)
                val protClass = Class.forName("com.griefcraft.model.Protection")
                lwcCanAccessMethod = lwcClass.getMethod("canAccessProtection", Player::class.java, protClass)
                hasLWC = true
            } catch (t: Throwable) {
                hasLWC = false
            }
        }

        hasAnyProtection = hasWorldGuard || hasGriefPrevention || hasTowny || hasLWC
        initialized = true
    }

    fun canBreak(player: Player, block: Block, simulateEvent: Boolean = false): Boolean {
        if (simulateEvent) {
            simulating.set(true)
            try {
                val event = BlockBreakEvent(block, player)
                Bukkit.getPluginManager().callEvent(event)
                if (event.isCancelled) return false
            } finally {
                simulating.set(false)
            }
        }

        if (!initialized) ensureInitialized()
        if (!hasAnyProtection) return true

        if (hasWorldGuard && !checkWorldGuard(player, block)) return false
        if (hasGriefPrevention && !checkGriefPrevention(player, block)) return false
        if (hasTowny && !checkTowny(player, block)) return false
        if (hasLWC && !checkLWC(player, block)) return false

        return true
    }

    private fun checkWorldGuard(player: Player, block: Block): Boolean {
        return try {
            val query = wgCreateQueryMethod!!.invoke(wgRegionContainer)
            val localLocation = wgAdaptLocMethod!!.invoke(null, block.location)
            val localPlayer = wgWrapPlayerMethod!!.invoke(wgPluginInst, player)
            wgTestStateMethod!!.invoke(query, localLocation, localPlayer, wgFlagArray) as Boolean
        } catch (e: Exception) {
            true
        }
    }

    private fun checkGriefPrevention(player: Player, block: Block): Boolean {
        return try {
            val claim = gpGetClaimAtMethod!!.invoke(gpDataStore, block.location, true, null)
            if (claim != null) {
                val reason = claim.javaClass.getMethod(
                    "allowBreak",
                    Player::class.java,
                    Material::class.java,
                    Location::class.java
                ).invoke(claim, player, block.type, block.location)
                reason == null
            } else {
                true
            }
        } catch (e: Exception) {
            true
        }
    }

    private fun checkTowny(player: Player, block: Block): Boolean {
        return try {
            townyDestroyMethod!!.invoke(townyInstance, player, block.location) as Boolean
        } catch (e: Exception) {
            true
        }
    }

    private fun checkLWC(player: Player, block: Block): Boolean {
        return try {
            val protection = lwcFindProtectionMethod!!.invoke(lwcInstance, block)
            if (protection != null) {
                lwcCanAccessMethod!!.invoke(lwcInstance, player, protection) as Boolean
            } else {
                true
            }
        } catch (e: Exception) {
            true
        }
    }
}