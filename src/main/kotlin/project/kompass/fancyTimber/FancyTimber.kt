package project.kompass.fancyTimber

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import project.kompass.fancyTimber.listener.BlockBreakListener
import project.kompass.fancyTimber.model.BlockPos
import project.kompass.fancyTimber.model.TreeScanResult
import project.kompass.fancyTimber.packet.ProtocolLibInterceptor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class FancyTimber : JavaPlugin(), CommandExecutor {

    val silentBlocks = ConcurrentHashMap.newKeySet<BlockPos>()

    val scanCache = ConcurrentHashMap<UUID, TreeScanResult>()

    var debugMode: Boolean = false

    override fun onEnable() {
        server.pluginManager.registerEvents(BlockBreakListener(this, silentBlocks), this)

        ProtocolLibInterceptor(this, silentBlocks).register()

        val command = getCommand("ftdebug")
        if (command != null) {
            command.setExecutor(this)
        } else {
            logger.warning("Could not register command /ftdebug. Ensure it is defined in your plugin.yml!")
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (command.name.equals("ftdebug", ignoreCase = true)) {
            if (!sender.isOp) {
                sender.sendMessage("§cYou do not have permission to execute this command.")
                return true
            }

            debugMode = !debugMode
            val status = if (debugMode) "§aENABLED" else "§cDISABLED"
            sender.sendMessage("§e[FancyTimber] §fDebug mode has been $status§f.")
            return true
        }
        return false
    }
}