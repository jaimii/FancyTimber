package project.kompass.fancyTimber

import org.bukkit.plugin.java.JavaPlugin
import project.kompass.fancyTimber.listener.BlockBreakListener
import project.kompass.fancyTimber.model.BlockPos
import project.kompass.fancyTimber.packet.ProtocolLibInterceptor
import java.util.concurrent.ConcurrentHashMap

class FancyTimber : JavaPlugin() {

    // This can safely remain private now that we pass it directly
    private val silentBlocks = ConcurrentHashMap.newKeySet<BlockPos>()

    override fun onEnable() {
        // Register Block Break Listener (pass the silent blocks set)
        server.pluginManager.registerEvents(BlockBreakListener(this, silentBlocks), this)

        // Register ProtocolLib Interceptor (pass the silent blocks set)
        ProtocolLibInterceptor(this, silentBlocks).register()
    }
}