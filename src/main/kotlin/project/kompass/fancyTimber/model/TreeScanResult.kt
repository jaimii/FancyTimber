package project.kompass.fancyTimber.model

import org.bukkit.block.Block

data class TreeScanResult(
    val blocks: List<Block>,
    val baseBlock: Block,
    val treeFamily: String,
    val logs: Set<Block> = emptySet(),
    val leaves: Set<Block> = emptySet(),
    val vines: Set<Block> = emptySet(),
    val tallestHeight: Int = 1,
    val naturalScore: Double = 1.0,
    val isBamboo: Boolean = false
)