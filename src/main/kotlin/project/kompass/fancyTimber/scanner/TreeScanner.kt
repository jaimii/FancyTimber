package project.kompass.fancyTimber.scanner

import org.bukkit.block.Block
import org.bukkit.block.data.type.Leaves
import project.kompass.fancyTimber.model.TreeScanResult
import project.kompass.fancyTimber.util.TimberUtils
import kotlin.math.abs
import kotlin.math.max

object TreeScanner {

    fun detectTree(startBlock: Block): TreeScanResult? {
        val world = startBlock.world
        val startFamily = TimberUtils.getTreeFamily(startBlock.type)

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

                        if (!TimberUtils.isNaturalEnvironment(adjType)) return null

                        // EXCLUSION GUARD: Completely ignore ground cover during adjacent lookups
                        if (TimberUtils.isExcludedFromScan(adjType)) continue

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

        val treeBlocks = mutableListOf<Block>()
        treeBlocks.addAll(trunks)

        val foliage = mutableSetOf<Block>()
        val vines = mutableSetOf<Block>()
        val foliageQueue = ArrayDeque<Pair<Block, Int>>()
        for (t in trunks) {
            foliageQueue.add(Pair(t, 0))
        }

        var foliageCount = 0

        // 2. Trace the foliage and attachments
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

                        if (!TimberUtils.isNaturalEnvironment(adjType)) return null

                        // EXCLUSION GUARD: Completely ignore ground cover during adjacent lookups
                        if (TimberUtils.isExcludedFromScan(adjType)) continue

                        if (TimberUtils.isVine(adjType)) {
                            val adjBlock = world.getBlockAt(adjX, adjY, adjZ)
                            if (adjBlock !in trunks && adjBlock !in foliage && adjBlock !in vines) {
                                vines.add(adjBlock)
                                foliageQueue.add(Pair(adjBlock, dist + 1))
                            }
                        } else if (TimberUtils.isFoliage(adjType)) {
                            val adjBlock = world.getBlockAt(adjX, adjY, adjZ)
                            if (adjBlock !in trunks && adjBlock !in foliage && adjBlock !in vines) {
                                val adjFamily = TimberUtils.getTreeFamily(adjType)

                                if (adjFamily == startFamily || adjFamily == "SHARED") {
                                    val blockData = adjBlock.blockData
                                    var belongsToTree = true

                                    if (blockData is Leaves) {
                                        if (!blockData.isPersistent && blockData.distance < dist + 1) {
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

        if (foliageCount == 0) return null

        return TreeScanResult(treeBlocks, baseBlock, vines)
    }
}