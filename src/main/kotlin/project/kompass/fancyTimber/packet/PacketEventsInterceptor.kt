package project.kompass.fancyTimber.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEffect
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import project.kompass.fancyTimber.model.BlockPos
import project.kompass.fancyTimber.util.SchedulerUtil
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PacketEventsInterceptor(
    private val plugin: JavaPlugin,
    private val silentBlocks: Set<BlockPos>
) : PacketListenerAbstract(PacketListenerPriority.NORMAL) {

    private val delayedAirUpdates = ConcurrentHashMap<UUID, MutableSet<BlockPos>>()

    fun register() {
        if (plugin.server.pluginManager.getPlugin("packetevents") == null) return
        PacketEvents.getAPI().eventManager.registerListener(this)
    }

    fun unregister() {
        if (plugin.server.pluginManager.getPlugin("packetevents") == null) return
        PacketEvents.getAPI().eventManager.unregisterListener(this)
        delayedAirUpdates.clear()
    }

    override fun onPacketSend(event: PacketSendEvent) {
        val packetType = event.packetType
        if (packetType != PacketType.Play.Server.EFFECT && packetType != PacketType.Play.Server.BLOCK_CHANGE) {
            return
        }

        val player = event.getPlayer<Player>() ?: return
        val uuid = player.uniqueId

        when (packetType) {
            PacketType.Play.Server.EFFECT -> {
                val wrapper = WrapperPlayServerEffect(event)
                if (wrapper.type == 2001) { // Block break particles and sound effect ID
                    val pos = wrapper.position
                    val blockPos = BlockPos(player.world.uid, pos.x, pos.y, pos.z)

                    if (silentBlocks.contains(blockPos)) {
                        event.isCancelled = true
                    }
                }
            }

            PacketType.Play.Server.BLOCK_CHANGE -> {
                val wrapper = WrapperPlayServerBlockChange(event)
                val pos = wrapper.blockPosition
                val blockPos = BlockPos(player.world.uid, pos.x, pos.y, pos.z)

                if (silentBlocks.contains(blockPos)) {
                    val playerUpdates = delayedAirUpdates.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }

                    if (!playerUpdates.contains(blockPos)) {
                        event.isCancelled = true
                        playerUpdates.add(blockPos)

                        // Delay the AIR block change by 3 ticks so the BlockDisplay renders first without flickering
                        SchedulerUtil.runEntityTaskLater(plugin, player, 3L) {
                            if (player.isOnline) {
                                player.sendBlockChange(
                                    Location(player.world, pos.x.toDouble(), pos.y.toDouble(), pos.z.toDouble()),
                                    Material.AIR.createBlockData()
                                )
                            }

                            val currentUpdates = delayedAirUpdates[uuid]
                            if (currentUpdates != null) {
                                currentUpdates.remove(blockPos)
                                if (currentUpdates.isEmpty()) {
                                    delayedAirUpdates.remove(uuid)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}