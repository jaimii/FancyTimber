package project.kompass.fancyTimber.packet

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.plugin.java.JavaPlugin
import project.kompass.fancyTimber.model.BlockPos
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ProtocolLibInterceptor(
    private val plugin: JavaPlugin,
    private val silentBlocks: Set<BlockPos>
) {
    private val delayedAirUpdates = ConcurrentHashMap<UUID, MutableSet<BlockPos>>()

    fun register() {
        if (plugin.server.pluginManager.getPlugin("ProtocolLib") == null) return
        val protocolManager = ProtocolLibrary.getProtocolManager()

        protocolManager.addPacketListener(

            object : PacketAdapter(plugin, ListenerPriority.NORMAL,
                PacketType.Play.Server.WORLD_EVENT,
                PacketType.Play.Server.BLOCK_CHANGE
            ) {
                override fun onPacketSending(event: PacketEvent) {
                    val packet = event.packet
                    val player = event.player
                    val uuid = player.uniqueId

                    when (event.packetType) {
                        PacketType.Play.Server.WORLD_EVENT -> {
                            val effectId = packet.integers.read(0)
                            if (effectId == 2001) {
                                val pos = packet.blockPositionModifier.read(0)
                                val blockPos = BlockPos(player.world.uid, pos.x, pos.y, pos.z)

                                if (silentBlocks.contains(blockPos)) {
                                    event.isCancelled = true
                                }
                            }
                        }

                        PacketType.Play.Server.BLOCK_CHANGE -> {
                            val pos = packet.blockPositionModifier.read(0)
                            val blockPos = BlockPos(player.world.uid, pos.x, pos.y, pos.z)

                            if (silentBlocks.contains(blockPos)) {
                                val playerUpdates = delayedAirUpdates.computeIfAbsent(uuid) { ConcurrentHashMap.newKeySet() }

                                if (!playerUpdates.contains(blockPos)) {
                                    event.isCancelled = true
                                    playerUpdates.add(blockPos)

                                    plugin.server.scheduler.runTaskLater(plugin, Runnable {
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
                                    }, 3L)
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}