package project.kompass.fancyTimber.packet

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.wrappers.BlockPosition
import org.bukkit.Location
import org.bukkit.entity.Player

object PacketHelper {

    /**
     * Sends a client-side block break animation (cracking effect) stage to a player.
     * @param fakeEntityId A unique ID to represent the cracking source.
     * @param stage The crack level (0-9, or -1 to clear the cracks).
     */
    fun sendBlockCrack(player: Player, fakeEntityId: Int, loc: Location, stage: Int) {
        val protocolManager = ProtocolLibrary.getProtocolManager()
        val packet = protocolManager.createPacket(PacketType.Play.Server.BLOCK_BREAK_ANIMATION)

        packet.integers.write(0, fakeEntityId)
        packet.blockPositionModifier.write(0, BlockPosition(loc.blockX, loc.blockY, loc.blockZ))
        packet.integers.write(1, stage)

        try {
            protocolManager.sendServerPacket(player, packet)
        } catch (e: Exception) {
            // Ignored defensively to prevent thread crashes during packet transport
        }
    }
}