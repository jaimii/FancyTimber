package project.kompass.fancyTimber.model

import org.bukkit.block.Block

data class TreeScanResult(
    val blocks: List<Block>,
    val baseBlock: Block,
    val vines: Set<Block>
)