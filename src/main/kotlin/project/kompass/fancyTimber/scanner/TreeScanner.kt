package project.kompass.fancyTimber.scanner

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.Leaves
import org.bukkit.entity.Player
import project.kompass.fancyTimber.model.TreeScanResult
import project.kompass.fancyTimber.util.TimberUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object TreeScanner {

    private val TRUNK_PRIORITY_DIRS = arrayOf(
        intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
        intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0), intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
        intArrayOf(1, 1, 0), intArrayOf(-1, 1, 0), intArrayOf(1, -1, 0), intArrayOf(-1, -1, 0),
        intArrayOf(1, 0, 1), intArrayOf(-1, 0, 1), intArrayOf(1, 0, -1), intArrayOf(-1, 0, -1),
        intArrayOf(0, 1, 1), intArrayOf(0, 1, -1), intArrayOf(0, -1, 1), intArrayOf(0, -1, -1),
        intArrayOf(1, 1, 1), intArrayOf(-1, 1, 1), intArrayOf(1, -1, 1), intArrayOf(-1, -1, 1),
        intArrayOf(1, 1, -1), intArrayOf(-1, 1, -1), intArrayOf(1, -1, -1), intArrayOf(-1, -1, -1)
    )

    private fun packPos(x: Int, y: Int, z: Int): Long {
        return ((x.toLong() and 0x3FFFFFFL) shl 38) or
                ((z.toLong() and 0x3FFFFFFL) shl 12) or
                (y.toLong() and 0xFFFL)
    }

    fun detectTree(
        startBlock: Block,
        debugReceiver: Player? = null,
        maxLogs: Int = 256,
        maxLeaves: Int = 500
    ): TreeScanResult? {
        val world = startBlock.world
        val startType = startBlock.type
        val startFamily = TimberUtils.getTreeFamily(startType)
        val isBamboo = startType == Material.BAMBOO

        debugReceiver?.sendMessage("§e[FancyTimber Debug] §fStarting tree scan. Family: §6$startFamily")

        val trunkRadius = detectTrunkRadius(world, startBlock.x, startBlock.y, startBlock.z)
        val trunks = LinkedHashSet<Block>()
        val trunkPosSet = HashSet<Long>()
        val trunkQueue = ArrayDeque<Block>()
        val branchQueue = ArrayDeque<Block>()

        trunkQueue.add(startBlock)
        trunks.add(startBlock)
        trunkPosSet.add(packPos(startBlock.x, startBlock.y, startBlock.z))

        var baseBlock = startBlock
        var minY = startBlock.y
        var maxY = startBlock.y

        // 1. Trunk-First Priority BFS
        while ((trunkQueue.isNotEmpty() || branchQueue.isNotEmpty()) && trunks.size < maxLogs) {
            val current = if (trunkQueue.isNotEmpty()) trunkQueue.removeFirst() else branchQueue.removeFirst()
            val curX = current.x
            val curY = current.y
            val curZ = current.z

            for (dir in TRUNK_PRIORITY_DIRS) {
                if (trunks.size >= maxLogs) break
                val adjX = curX + dir[0]
                val adjY = curY + dir[1]
                val adjZ = curZ + dir[2]

                if (!world.isChunkLoaded(adjX shr 4, adjZ shr 4)) continue

                val adjType = world.getType(adjX, adjY, adjZ)

                if (TimberUtils.isExcludedFromScan(adjType)) continue

                // Only abort if the trunk directly touches a man-made structure block (planks, doors, glass, wall torches, etc.)
                if (TimberUtils.isBuildingBlock(adjType)) {
                    debugReceiver?.sendMessage(
                        "§e[FancyTimber Debug] §cScan Aborted! Building block next to trunk: §e$adjType §fat [§a$adjX, $adjY, $adjZ§f]"
                    )
                    return null
                }

                if (TimberUtils.isTrunk(adjType)) {
                    val packed = packPos(adjX, adjY, adjZ)
                    if (packed !in trunkPosSet) {
                        val adjFamily = TimberUtils.getTreeFamily(adjType)

                        if (adjFamily == startFamily || adjFamily == "SHARED") {
                            val hDist = max(abs(adjX - startBlock.x), abs(adjZ - startBlock.z))
                            if (hDist <= 14 && (adjY - startBlock.y) in -20..64) {
                                val adjBlock = world.getBlockAt(adjX, adjY, adjZ)
                                trunks.add(adjBlock)
                                trunkPosSet.add(packed)

                                if (isWithinTrunkAxis(adjX, adjZ, startBlock.x, startBlock.z, trunkRadius)) {
                                    trunkQueue.add(adjBlock)
                                } else {
                                    branchQueue.add(adjBlock)
                                }
                                if (adjY < baseBlock.y) baseBlock = adjBlock
                                minY = min(minY, adjY)
                                maxY = max(maxY, adjY)
                            }
                        }
                    }
                }
            }
        }

        val trunkHeight = max(1, maxY - minY + 1)
        debugReceiver?.sendMessage("§e[FancyTimber Debug] §fTrunk tracing complete. Found §a${trunks.size}§f logs (Height: §a$trunkHeight§f).")

        if (isBamboo) {
            if (trunks.size < 2) return null
            return TreeScanResult(
                blocks = trunks.toList(),
                baseBlock = baseBlock,
                treeFamily = startFamily,
                logs = trunks,
                leaves = emptySet(),
                tallestHeight = trunkHeight,
                naturalScore = 0.9,
                isBamboo = true
            )
        }

        val treeBlocks = ArrayList<Block>(trunks.size + 128)
        treeBlocks.addAll(trunks)

        val foliage = LinkedHashSet<Block>()
        val visitedFoliagePos = HashSet<Long>(trunkPosSet)
        val foliageQueue = ArrayDeque<Block>()
        val distanceQueue = ArrayDeque<Int>()

        for (t in trunks) {
            foliageQueue.add(t)
            distanceQueue.add(0)
        }

        var foliageCount = 0
        var weightedLeafScore = 0
        var evaluatedLeavesCount = 0

        // 2. Canopy & Hanging Attachment Tracing (Leaves, Vines, Propagules, Shelf Mushrooms)
        while (foliageQueue.isNotEmpty() && foliage.size < maxLeaves && treeBlocks.size < 2500) {
            val current = foliageQueue.removeFirst()
            val dist = distanceQueue.removeFirst()
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

                        if (debugReceiver != null && evaluatedLeavesCount < 8 &&
                            (adjType.name.contains("CHERRY") || adjType.name.contains("POPLAR") || adjType.name.contains("LEAVES"))
                        ) {
                            evaluatedLeavesCount++
                            debugReceiver.sendMessage(
                                "§e[FancyTimber Debug] §fNeighbor: §6$adjType§f | " +
                                        "Excl: §a${TimberUtils.isExcludedFromScan(adjType)}§f, " +
                                        "Bldg: §a${TimberUtils.isBuildingBlock(adjType)}§f, " +
                                        "Fol: §a${TimberUtils.isFoliage(adjType)}§f, " +
                                        "Fam: §b${TimberUtils.getTreeFamily(adjType)}"
                            )
                        }

                        if (TimberUtils.isExcludedFromScan(adjType)) continue

                        if (TimberUtils.isBuildingBlock(adjType)) {
                            debugReceiver?.sendMessage(
                                "§e[FancyTimber Debug] §cScan Aborted! Building block next to foliage: §e$adjType §fat [§a$adjX, $adjY, $adjZ§f]"
                            )
                            return null
                        }

                        if (TimberUtils.isFoliage(adjType)) {
                            val packed = packPos(adjX, adjY, adjZ)
                            if (packed !in visitedFoliagePos) {
                                val adjFamily = TimberUtils.getTreeFamily(adjType)

                                if (adjFamily == startFamily || adjFamily == "SHARED") {
                                    val adjBlock = world.getBlockAt(adjX, adjY, adjZ)
                                    val blockData = adjBlock.blockData
                                    var belongsToTree = true

                                    if (blockData is Leaves) {
                                        if (blockData.isPersistent) {
                                            belongsToTree = false
                                        }
                                        if (belongsToTree && blockData.distance < dist) {
                                            belongsToTree = false
                                        }
                                    }

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
                                                        if (packPos(checkX, checkY, checkZ) !in trunkPosSet) {
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
                                        visitedFoliagePos.add(packed)
                                        foliage.add(adjBlock)
                                        foliageQueue.add(adjBlock)
                                        distanceQueue.add(dist + 1)
                                        treeBlocks.add(adjBlock)
                                        foliageCount++
                                        weightedLeafScore += max(1, 6 - (dist + 1))
                                        maxY = max(maxY, adjY)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val naturalScore = calculateNaturalScore(startBlock, trunks, trunkHeight, trunkRadius > 0, foliageCount, weightedLeafScore)
        debugReceiver?.sendMessage(
            "§e[FancyTimber Debug] §fFoliage complete. Found §a$foliageCount§f foliage blocks (Natural Score: §b${"%.2f".format(naturalScore)}§f)."
        )

        if (foliageCount == 0 || naturalScore < 0.35) {
            debugReceiver?.sendMessage("§e[FancyTimber Debug] §cScan Canceled: Insufficient foliage or low natural score.")
            return null
        }

        // 3. Collect ONLY floating snow layers & moss carpets resting directly atop scanned tree blocks
        val attachmentBlocks = ArrayList<Block>()
        for (treeBlock in treeBlocks) {
            val aboveX = treeBlock.x
            val aboveY = treeBlock.y + 1
            val aboveZ = treeBlock.z
            if (aboveY >= world.maxHeight) continue
            val aboveType = world.getType(aboveX, aboveY, aboveZ)
            if (TimberUtils.isTopAttachment(aboveType)) {
                val packed = packPos(aboveX, aboveY, aboveZ)
                if (packed !in trunkPosSet && packed !in visitedFoliagePos) {
                    visitedFoliagePos.add(packed)
                    val aboveBlock = world.getBlockAt(aboveX, aboveY, aboveZ)
                    attachmentBlocks.add(aboveBlock)
                    foliage.add(aboveBlock)
                }
            }
        }
        treeBlocks.addAll(attachmentBlocks)

        val tallestHeight = max(1, maxY - baseBlock.y)
        debugReceiver?.sendMessage("§e[FancyTimber Debug] §aScan successful! Animating §e${treeBlocks.size}§a blocks.")

        return TreeScanResult(
            blocks = treeBlocks,
            baseBlock = baseBlock,
            treeFamily = startFamily,
            logs = trunks,
            leaves = foliage,
            tallestHeight = tallestHeight,
            naturalScore = naturalScore,
            isBamboo = false
        )
    }

    private fun detectTrunkRadius(world: World, x: Int, y: Int, z: Int): Int {
        val offsets = intArrayOf(-1, 0)
        for (ox in offsets) {
            for (oz in offsets) {
                var count = 0
                for (dx in 0..1) {
                    for (dz in 0..1) {
                        if (TimberUtils.isTrunk(world.getType(x + ox + dx, y, z + oz + dz))) {
                            count++
                        }
                    }
                }
                if (count >= 3) return 1
            }
        }
        return 0
    }

    private fun isWithinTrunkAxis(x: Int, z: Int, centerX: Int, centerZ: Int, radius: Int): Boolean {
        return abs(x - centerX) <= radius && abs(z - centerZ) <= radius
    }

    private fun calculateNaturalScore(
        start: Block,
        logs: Set<Block>,
        trunkHeight: Int,
        isThickTrunk: Boolean,
        leafCount: Int,
        leafScore: Int
    ): Double {
        val logCount = logs.size
        if (logCount == 0) return 0.0

        var minX = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var minZ = Int.MAX_VALUE
        var maxZ = Int.MIN_VALUE
        var verticalCount = 0
        var diagonalCount = 0
        val trunkRadius = if (isThickTrunk) 1 else 0

        for (log in logs) {
            minX = min(minX, log.x)
            maxX = max(maxX, log.x)
            minZ = min(minZ, log.z)
            maxZ = max(maxZ, log.z)
            if (log.y != start.y) {
                if (isWithinTrunkAxis(log.x, log.z, start.x, start.z, trunkRadius)) {
                    verticalCount++
                } else if (abs(log.x - start.x) == abs(log.z - start.z)) {
                    diagonalCount++
                }
            }
        }

        val maxSpan = max(maxX - minX + 1, maxZ - minZ + 1)
        val horizontalRatio = if (maxSpan > 0) maxSpan.toDouble() / logCount.toDouble() else 0.0

        var score = 0.0
        val logHeightRatio = trunkHeight.toDouble() / logCount.toDouble()
        if (logHeightRatio >= 0.5) score += 0.3 else if (logHeightRatio >= 0.25) score += 0.15

        val verticalRatio = (diagonalCount + max(1, trunkHeight)).toDouble()
        if (verticalRatio >= logCount * 0.4) score += 0.2 else if (verticalRatio >= logCount * 0.2) score += 0.1

        val leafDensity = leafScore.toDouble() / logCount.toDouble()
        if (leafCount >= 6 && leafDensity >= 1.2) score += 0.35 else if (leafCount >= 1) score += 0.25

        if (horizontalRatio > 3.0) score -= 0.25 else if (horizontalRatio > 2.0) score += 0.05 else score += 0.15

        return score.coerceIn(0.0, 1.0)
    }
}