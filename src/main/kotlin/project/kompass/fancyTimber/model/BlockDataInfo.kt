package project.kompass.fancyTimber.model

import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.inventory.ItemStack
import org.joml.Vector3f

data class BlockDataInfo(
    val offset: Vector3f,
    val display: BlockDisplay,
    val drops: Collection<ItemStack>,
    val blockData: BlockData,
    val isTrunk: Boolean
)