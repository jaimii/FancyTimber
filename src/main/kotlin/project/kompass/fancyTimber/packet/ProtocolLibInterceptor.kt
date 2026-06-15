package project.kompass.fancyTimber.packet

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Registry
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
                PacketType.Play.Server.BLOCK_CHANGE,
                PacketType.Play.Server.NAMED_SOUND_EFFECT
            ) {
                override fun onPacketSending(event: PacketEvent) {
                    val packet = event.packet
                    val player = event.player
                    val uuid = player.uniqueId

                    when (event.packetType) {
                        PacketType.Play.Server.WORLD_EVENT -> {
                            val effectId = packet.integers.read(0)
                            if (effectId == 2001) { // Block Break particles and sound
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

                        PacketType.Play.Server.NAMED_SOUND_EFFECT -> {
                            // Extract the sound key safely and cleanly without using deprecated .key getters
                            val soundName = try {
                                val sound = packet.soundEffects.readSafely(0)
                                val namespacedKey = if (sound != null) Registry.SOUNDS.getKey(sound) else null

                                namespacedKey?.key
                                    ?: packet.getMinecraftKeys().readSafely(0)?.toString()
                                    ?: ""
                            } catch (e: Exception) {
                                ""
                            }

                            if (soundName.contains("wood") && (soundName.contains("break") || soundName.contains("step"))) {
                                val xRaw = packet.integers.readSafely(0) ?: return
                                val yRaw = packet.integers.readSafely(1) ?: return
                                val zRaw = packet.integers.readSafely(2) ?: return

                                val x = xRaw / 8.0
                                val y = yRaw / 8.0
                                val z = zRaw / 8.0

                                val soundBlockX = kotlin.math.floor(x).toInt()
                                val soundBlockY = kotlin.math.floor(y).toInt()
                                val soundBlockZ = kotlin.math.floor(z).toInt()

                                val blockPos = BlockPos(player.world.uid, soundBlockX, soundBlockY, soundBlockZ)
                                if (silentBlocks.contains(blockPos)) {
                                    event.isCancelled = true
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}