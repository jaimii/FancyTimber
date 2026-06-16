package project.kompass.fancyTimber.scanner

import org.bukkit.block.Block
import org.bukkit.block.data.type.Leaves
import org.bukkit.entity.Player
import project.kompass.fancyTimber.model.TreeScanResult
import project.kompass.fancyTimber.util.TimberUtils
import kotlin.math.abs
import kotlin.math.max

object TreeScanner {

    fun detectTree(startBlock: Block, debugReceiver: Player? = null): TreeScanResult? {
        val world = startBlock.world
        val startFamily = TimberUtils.getTreeFamily(startBlock.type)

        debugReceiver?.sendMessage("§e[FancyTimber Debug] §fStarting tree scan. Family: §6$startFamily")

        val trunks = mutableSetOf<Block>()
        val trunkQueue = ArrayDeque<Block>()
        trunkQueue.add(startBlock)
        trunks.add(startBlock)

        var baseBlock = startBlock

        // 1. Trace the trunk
        while (trunkQueue.isNotEmpty()) {
            val current = trunkQueue.removeFirst()
            val curX = current.x
            val curY = current.y
            val curZ = current.z

            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        val adjX = curX + dx
                        val adjY = curY + dy
                        val adjZ = curZ + dz

                        if (!world.isChunkLoaded(adjX shr 4, adjZ shr 4)) continue

                        val adjType = world.getType(adjX, adjY, adjZ)

                        if (TimberUtils.isExcludedFromScan(adjType)) continue

                        if (!TimberUtils.isNaturalEnvironment(adjType)) {
                            debugReceiver?.sendMessage(
                                "§e[FancyTimber Debug] §cScan Aborted! Unnatural block next to trunk: §e$adjType §fat [§a$adjX, $adjY, $adjZ§f]"
                            )
                            return null
                        }

                        if (TimberUtils.isTrunk(adjType)) {
                            val adjBlock = world.getBlockAt(adjX, adjY, adjZ)
                            if (adjBlock !in trunks) {
                                val adjFamily = TimberUtils.getTreeFamily(adjType)

                                if (adjFamily == startFamily || adjFamily == "SHARED") {
                                    val hDist = max(abs(adjX - startBlock.x), abs(adjZ - startBlock.z))
                                    if (hDist <= 12 && (adjY - startBlock.y) in -12..50) {
                                        trunks.add(adjBlock)
                                        trunkQueue.add(adjBlock)
                                        if (adjY < baseBlock.y) {
                                            baseBlock = adjBlock
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        debugReceiver?.sendMessage("§e[FancyTimber Debug] §fTrunk tracing complete. Found §a${trunks.size}§f trunk blocks.")

        val treeBlocks = mutableListOf<Block>()
        treeBlocks.addAll(trunks)

        val foliage = mutableSetOf<Block>()
        val foliageQueue = ArrayDeque<Pair<Block, Int>>()
        for (t in trunks) {
            foliageQueue.add(Pair(t, 0))
        }

        var foliageCount = 0
        var evaluatedLeavesCount = 0

        // 2. Trace the foliage
        while (foliageQueue.isNotEmpty() && treeBlocks.size < 2500) {
            val (current, dist) = foliageQueue.removeFirst()
            val curX = current.x
            val curY = current.y
            val curZ = current.z

            if (dist >= 6) continue

            for (dx in -1..1) {
                for (dy in -1..1) {
                    for (dz in -1..1) {
                        if (dx == 0 && dy == 0 && dz == 0) continue
                        val adjX = curX + dx
                        val adjY = curY + dy
                        val adjZ = curZ + dz

                        if (!world.isChunkLoaded(adjX shr 4, adjZ shr 4)) continue

                        val adjType = world.getType(adjX, adjY, adjZ)

                        val isTargetBlock = adjType.name.contains("CHERRY") || adjType.name.contains("LEAVES")
                        if (debugReceiver != null && isTargetBlock && evaluatedLeavesCount < 10) {
                            evaluatedLeavesCount++
                            debugReceiver.sendMessage(
                                "§e[FancyTimber Debug] §fFound leaves/cherry neighbor: §6$adjType§f.\n" +
                                        "§e - isExcluded: §a${TimberUtils.isExcludedFromScan(adjType)}§f, " +
                                        "isNatural: §a${TimberUtils.isNaturalEnvironment(adjType)}§f, " +
                                        "isFoliage: §a${TimberUtils.isFoliage(adjType)}§f\n" +
                                        "§e - Family: §b${TimberUtils.getTreeFamily(adjType)}§f (Target: §b$startFamily§f)"
                            )
                        }

                        if (TimberUtils.isExcludedFromScan(adjType)) continue

                        if (!TimberUtils.isNaturalEnvironment(adjType)) {
                            debugReceiver?.sendMessage(
                                "§e[FancyTimber Debug] §cScan Aborted! Unnatural block next to foliage: §e$adjType §fat [§a$adjX, $adjY, $adjZ§f]"
                            )
                            return null
                        }

                        if (TimberUtils.isFoliage(adjType)) {
                            val adjBlock = world.getBlockAt(adjX, adjY, adjZ)
                            if (adjBlock !in trunks && adjBlock !in foliage) {
                                val adjFamily = TimberUtils.getTreeFamily(adjType)

                                if (adjFamily == startFamily || adjFamily == "SHARED") {
                                    val blockData = adjBlock.blockData
                                    var belongsToTree = true

                                    if (blockData is Leaves) {
                                        // Skip player-placed leaves
                                        if (blockData.isPersistent) {
                                            belongsToTree = false
                                        }

                                        // PROTECTION 1: If the leaf block's internal distance is smaller than our path distance,
                                        // it means it is closer to a different tree's trunk. We safely skip it.
                                        if (belongsToTree && blockData.distance < dist) {
                                            belongsToTree = false
                                        }
                                    }

                                    // PROTECTION 2: Check if this leaf block directly touches any untracked trunk block.
                                    // If it does, we flag it as belonging to the adjacent tree and skip it.
                                    if (belongsToTree) {
                                        var touchesForeignTrunk = false
                                        for (nx in -1..1) {
                                            for (ny in -1..1) {
                                                for (nz in -1..1) {
                                                    if (nx == 0 && ny == 0 && nz == 0) continue
                                                    val checkX = adjX + nx
                                                    val checkY = adjY + ny
                                                    val checkZ = adjZ + nz
                                                    val checkType = world.getType(checkX, checkY, checkZ)

                                                    if (TimberUtils.isTrunk(checkType)) {
                                                        val checkBlock = world.getBlockAt(checkX, checkY, checkZ)
                                                        if (checkBlock !in trunks) {
                                                            touchesForeignTrunk = true
                                                            break
                                                        }
                                                    }
                                                }
                                                if (touchesForeignTrunk) break
                                            }
                                            if (touchesForeignTrunk) break
                                        }
                                        if (touchesForeignTrunk) {
                                            belongsToTree = false
                                        }
                                    }

                                    if (belongsToTree) {
                                        foliage.add(adjBlock)
                                        foliageQueue.add(Pair(adjBlock, dist + 1))
                                        treeBlocks.add(adjBlock)
                                        foliageCount++
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        debugReceiver?.sendMessage("§e[FancyTimber Debug] §fFoliage tracing complete. Found §a$foliageCount§f foliage blocks.")

        if (foliageCount == 0) {
            debugReceiver?.sendMessage("§e[FancyTimber Debug] §cScan Canceled: No leaves or foliage blocks were successfully mapped.")
            return null
        }

        debugReceiver?.sendMessage("§e[FancyTimber Debug] §aScan successful! Spawning animation for §e${treeBlocks.size}§a blocks.")
        return TreeScanResult(treeBlocks, baseBlock, emptySet())
    }
}