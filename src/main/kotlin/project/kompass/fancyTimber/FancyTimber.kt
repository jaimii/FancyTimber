package project.kompass.fancyTimber

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.BlockDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import project.kompass.fancyTimber.listener.BlockBreakListener
import project.kompass.fancyTimber.listener.LaydownInteractionListener
import project.kompass.fancyTimber.model.AnimationConfig
import project.kompass.fancyTimber.model.BlockPos
import project.kompass.fancyTimber.model.TreeScanResult
import project.kompass.fancyTimber.packet.PacketEventsInterceptor
import project.kompass.fancyTimber.util.ProtectionManager
import project.kompass.fancyTimber.util.SchedulerUtil
import project.kompass.fancyTimber.util.TimberUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class LaydownBlockData(
    val drops: List<ItemStack>,
    val isTrunk: Boolean
)

class FancyTimber : JavaPlugin(), CommandExecutor {

    val silentBlocks: MutableSet<BlockPos> = ConcurrentHashMap.newKeySet()
    val scanCache: ConcurrentHashMap<UUID, TreeScanResult> = ConcurrentHashMap()
    val activeDisplays: MutableSet<BlockDisplay> = ConcurrentHashMap.newKeySet()

    // Replaces deprecated FixedMetadataValue for resting Laydown BlockDisplays
    val restingLaydownBlocks: ConcurrentHashMap<UUID, LaydownBlockData> = ConcurrentHashMap()

    @Volatile
    var debugMode: Boolean = false

    @Volatile
    lateinit var animationConfig: AnimationConfig
        private set

    var maxActiveDisplays: Int = 1200
        private set

    private var packetInterceptor: PacketEventsInterceptor? = null
    private var blockBreakListener: BlockBreakListener? = null

    override fun onEnable() {
        instance = this
        saveDefaultConfig()
        reloadPluginConfig()

        TimberUtils.warmup()
        ProtectionManager.warmup()

        logger.info("Server: ${server.name} ${server.version} (Scheduler: ${SchedulerUtil.schedulerName})")

        val breakListener = BlockBreakListener(this, silentBlocks)
        blockBreakListener = breakListener
        server.pluginManager.registerEvents(breakListener, this)
        server.pluginManager.registerEvents(LaydownInteractionListener(this), this)

        packetInterceptor = PacketEventsInterceptor(this, silentBlocks).also { it.register() }

        val command = getCommand("ftdebug")
        if (command != null) {
            command.setExecutor(this)
        } else {
            logger.warning("Could not register command /ftdebug. Ensure it is defined in your plugin.yml!")
        }
    }

    override fun onDisable() {
        packetInterceptor?.unregister()
        packetInterceptor = null

        blockBreakListener?.cleanupAllFatigue()
        blockBreakListener = null

        for (display in activeDisplays) {
            if (display.isValid) {
                display.remove()
            }
        }
        activeDisplays.clear()
        restingLaydownBlocks.clear()
        scanCache.clear()
        silentBlocks.clear()
    }

    private fun reloadPluginConfig() {
        reloadConfig()
        animationConfig = AnimationConfig(config)
        maxActiveDisplays = config.getInt("limits.max-active-displays", 1200).coerceAtLeast(100)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name.equals("ftdebug", ignoreCase = true)) {
            if (!sender.isOp) {
                sender.sendMessage("§cYou do not have permission to execute this command.")
                return true
            }

            if (args.isNotEmpty() && args[0].equals("reload", ignoreCase = true)) {
                reloadPluginConfig()
                sender.sendMessage("§e[FancyTimber] §aConfiguration reloaded!")
                return true
            }

            debugMode = !debugMode
            val status = if (debugMode) "§aENABLED" else "§cDISABLED"
            sender.sendMessage("§e[FancyTimber] §fDebug mode has been $status§f.")
            return true
        }
        return false
    }

    companion object {
        lateinit var instance: FancyTimber
            private set
    }
}