package project.kompass.fancyTimber.packet

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.util.Vector3i
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockBreakAnimation
import org.bukkit.Location
import org.bukkit.entity.Player

object PacketHelper {

    /**
     * Sends a client-side block break animation (cracking effect) stage to a player via PacketEvents.
     * @param fakeEntityId A unique ID to represent the cracking source.
     * @param stage The crack level (0-9, or -1 to clear the cracks).
     */
    fun sendBlockCrack(player: Player, fakeEntityId: Int, loc: Location, stage: Int) {
        try {
            val pos = Vector3i(loc.blockX, loc.blockY, loc.blockZ)
            val packet = WrapperPlayServerBlockBreakAnimation(fakeEntityId, pos, stage.toByte())
            PacketEvents.getAPI().playerManager.sendPacket(player, packet)
        } catch (e: Exception) {
            // Ignored defensively to prevent thread crashes during packet transport
        }
    }
}