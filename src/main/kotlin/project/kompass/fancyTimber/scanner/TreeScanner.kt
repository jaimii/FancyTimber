package project.kompass.fancyTimber.scanner

import org.bukkit.Material
import org.bukkit.Tag
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

    // Strictly 6 cardinal directions (matches Minecraft's Manhattan leaf distance)
    private val CARDINAL_DIRS = arrayOf(
        intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
        intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
        intArrayOf(0, 0, 1), intArrayOf(0, 0, -1)
    )

    // 26 directions with vertical/cardinal prioritized first (used for trunk tracing & non-leaf attachments)
    private val ALL_DIRS = arrayOf(
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

    private fun isPureLeaf(type: Material): Boolean {
        return Tag.LEAVES.isTagged(type) || type.name.endsWith("_LEAVES")
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
        val ownTrunks = IntPointList(32)
        val trunkQueue = ArrayDeque<Block>()
        val branchQueue = ArrayDeque<Block>()

        trunkQueue.add(startBlock)
        trunks.add(startBlock)
        trunkPosSet.add(packPos(startBlock.x, startBlock.y, startBlock.z))
        ownTrunks.add(startBlock.x, startBlock.y, startBlock.z)

        var baseBlock = startBlock
        var minX = startBlock.x
        var maxX = startBlock.x
        var minY = startBlock.y
        var maxY = startBlock.y
        var minZ = startBlock.z
        var maxZ = startBlock.z

        // 1. Trunk-First Priority BFS (Structure/Building checks happen ONLY here on bark/logs)
        while ((trunkQueue.isNotEmpty() || branchQueue.isNotEmpty()) && trunks.size < maxLogs) {
            val current = if (trunkQueue.isNotEmpty()) trunkQueue.removeFirst() else branchQueue.removeFirst()
            val curX = current.x
            val curY = current.y
            val curZ = current.z

            for (dir in ALL_DIRS) {
                if (trunks.size >= maxLogs) break
                val adjX = curX + dir[0]
                val adjY = curY + dir[1]
                val adjZ = curZ + dir[2]

                if (!world.isChunkLoaded(adjX shr 4, adjZ shr 4)) continue

                val adjType = world.getType(adjX, adjY, adjZ)

                if (TimberUtils.isExcludedFromScan(adjType)) continue

                // Structure safety check runs exclusively on trunk/bark neighbors
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
                                ownTrunks.add(adjX, adjY, adjZ)

                                if (isWithinTrunkAxis(adjX, adjZ, startBlock.x, startBlock.z, trunkRadius)) {
                                    trunkQueue.add(adjBlock)
                                } else {
                                    branchQueue.add(adjBlock)
                                }
                                if (adjY < baseBlock.y) baseBlock = adjBlock
                                minX = min(minX, adjX)
                                maxX = max(maxX, adjX)
                                minY = min(minY, adjY)
                                maxY = max(maxY, adjY)
                                minZ = min(minZ, adjZ)
                                maxZ = max(maxZ, adjZ)
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

        // 1.5. Locate nearby foreign trunks of the same family for exact Voronoi leaf ownership
        val foreignTrunks = findForeignTrunks(world, minX, maxX, minY, maxY, minZ, maxZ, startFamily, trunkPosSet)
        if (debugReceiver != null && foreignTrunks.size > 0) {
            debugReceiver.sendMessage("§e[FancyTimber Debug] §fDetected §e${foreignTrunks.size}§f nearby foreign trunk blocks (Voronoi split active).")
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

        // 2. Canopy & Attachment Tracing (NO building-block abort here; leaves touching structures are allowed)
        while (foliageQueue.isNotEmpty() && foliage.size < maxLeaves && treeBlocks.size < 2500) {
            val current = foliageQueue.removeFirst()
            val dist = distanceQueue.removeFirst()
            val curX = current.x
            val curY = current.y
            val curZ = current.z

            if (dist >= 6) continue

            for (dir in ALL_DIRS) {
                val dx = dir[0]
                val dy = dir[1]
                val dz = dir[2]
                val stepManhattan = abs(dx) + abs(dy) + abs(dz)
                val nextDist = dist + stepManhattan
                if (nextDist > 6) continue

                val adjX = curX + dx
                val adjY = curY + dy
                val adjZ = curZ + dz

                if (!world.isChunkLoaded(adjX shr 4, adjZ shr 4)) continue

                val adjType = world.getType(adjX, adjY, adjZ)

                if (TimberUtils.isExcludedFromScan(adjType)) continue
                if (!TimberUtils.isFoliage(adjType)) continue

                // Enforce strict 6-directional cardinal stepping for Leaves blocks
                val pureLeaf = isPureLeaf(adjType)
                if (pureLeaf && stepManhattan > 1) continue

                val packed = packPos(adjX, adjY, adjZ)
                if (packed in visitedFoliagePos) continue

                val adjFamily = TimberUtils.getTreeFamily(adjType)
                if (adjFamily != startFamily && adjFamily != "SHARED") continue

                if (debugReceiver != null && evaluatedLeavesCount < 6 &&
                    (adjType.name.contains("CHERRY") || adjType.name.contains("POPLAR") || pureLeaf)
                ) {
                    evaluatedLeavesCount++
                    debugReceiver.sendMessage(
                        "§e[FancyTimber Debug] §fLeaf: §6$adjType§f | nextDist=§a$nextDist§f | Fam=§b$adjFamily"
                    )
                }

                // Ownership Check 1: Voronoi nearest-trunk arbitration against foreign trunks of the same family
                if (foreignTrunks.size > 0 && !isOwnedByOwnTree(adjX, adjY, adjZ, ownTrunks, foreignTrunks)) {
                    continue
                }

                val adjBlock = world.getBlockAt(adjX, adjY, adjZ)
                val blockData = adjBlock.blockData

                if (blockData is Leaves) {
                    if (blockData.isPersistent) continue
                    // Ownership Check 2: Vanilla Manhattan distance must not be strictly smaller than our cardinal step
                    if (foreignTrunks.size > 0 && blockData.distance < nextDist) continue
                }

                visitedFoliagePos.add(packed)
                foliage.add(adjBlock)
                foliageQueue.add(adjBlock)
                distanceQueue.add(nextDist)
                treeBlocks.add(adjBlock)
                foliageCount++
                weightedLeafScore += max(1, 7 - nextDist)
                maxY = max(maxY, adjY)
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

    /**
     * Scans the bounding volume around the felled tree once using direct palette reads
     * to find any foreign logs belonging to adjacent trees of the same family.
     */
    private fun findForeignTrunks(
        world: World,
        minX: Int,
        maxX: Int,
        minY: Int,
        maxY: Int,
        minZ: Int,
        maxZ: Int,
        startFamily: String,
        ownTrunkPosSet: Set<Long>
    ): IntPointList {
        val foreign = IntPointList(16)
        val margin = 6
        val scanMinX = minX - margin
        val scanMaxX = maxX + margin
        val scanMinZ = minZ - margin
        val scanMaxZ = maxZ + margin
        val scanMinY = max(world.minHeight, minY - 2)
        val scanMaxY = min(world.maxHeight - 1, maxY + margin)

        for (x in scanMinX..scanMaxX) {
            val chunkX = x shr 4
            for (z in scanMinZ..scanMaxZ) {
                if (!world.isChunkLoaded(chunkX, z shr 4)) continue
                for (y in scanMinY..scanMaxY) {
                    val type = world.getType(x, y, z)
                    if (!TimberUtils.isTrunk(type)) continue

                    val packed = packPos(x, y, z)
                    if (packed in ownTrunkPosSet) continue

                    val family = TimberUtils.getTreeFamily(type)
                    if (family == startFamily || family == "SHARED") {
                        foreign.add(x, y, z)
                    }
                }
            }
        }
        return foreign
    }

    /**
     * Determines leaf ownership by comparing 3D Manhattan distance (with Euclidean tie-breaker)
     * against our tree's trunks vs. any nearby foreign trunks.
     */
    private fun isOwnedByOwnTree(
        x: Int,
        y: Int,
        z: Int,
        ownTrunks: IntPointList,
        foreignTrunks: IntPointList
    ): Boolean {
        var minOwnManhattan = Int.MAX_VALUE
        var minOwnDistSq = Int.MAX_VALUE
        for (i in 0 until ownTrunks.size) {
            val dx = abs(x - ownTrunks.xs[i])
            val dy = abs(y - ownTrunks.ys[i])
            val dz = abs(z - ownTrunks.zs[i])
            val manhattan = dx + dy + dz
            val distSq = dx * dx + dy * dy + dz * dz
            if (manhattan < minOwnManhattan) minOwnManhattan = manhattan
            if (distSq < minOwnDistSq) minOwnDistSq = distSq
        }

        var minForeignManhattan = Int.MAX_VALUE
        var minForeignDistSq = Int.MAX_VALUE
        for (i in 0 until foreignTrunks.size) {
            val dx = abs(x - foreignTrunks.xs[i])
            val dy = abs(y - foreignTrunks.ys[i])
            val dz = abs(z - foreignTrunks.zs[i])
            val manhattan = dx + dy + dz
            val distSq = dx * dx + dy * dy + dz * dz
            if (manhattan < minForeignManhattan) minForeignManhattan = manhattan
            if (distSq < minForeignDistSq) minForeignDistSq = distSq
        }

        if (minOwnManhattan != minForeignManhattan) {
            return minOwnManhattan < minForeignManhattan
        }
        // On exact Manhattan ties, require strictly closer Euclidean distance so adjacent trees aren't stripped
        return minOwnDistSq < minForeignDistSq
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

    /**
     * Primitive IntArray-backed coordinate buffer to avoid boxing & Block wrapper allocations.
     */
    private class IntPointList(initialCapacity: Int) {
        var xs = IntArray(initialCapacity)
        var ys = IntArray(initialCapacity)
        var zs = IntArray(initialCapacity)
        var size = 0

        fun add(x: Int, y: Int, z: Int) {
            if (size == xs.size) {
                val newCap = xs.size * 2
                xs = xs.copyOf(newCap)
                ys = ys.copyOf(newCap)
                zs = zs.copyOf(newCap)
            }
            xs[size] = x
            ys[size] = y
            zs[size] = z
            size++
        }
    }
}