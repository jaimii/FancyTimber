package project.kompass.fancyTimber.model

import org.bukkit.block.Block
import java.util.UUID

data class BlockPos(val worldId: UUID, val x: Int, val y: Int, val z: Int) {
    companion object {
        fun from(block: Block): BlockPos = BlockPos(block.world.uid, block.x, block.y, block.z)
    }
}