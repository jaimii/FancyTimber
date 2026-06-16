package project.kompass.fancyTimber.animation

import org.bukkit.*
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import project.kompass.fancyTimber.model.BlockDataInfo
import project.kompass.fancyTimber.packet.PacketHelper
import project.kompass.fancyTimber.util.TimberUtils

class TreeFallAnimation(
    private val displaysAndDrops: List<BlockDataInfo>,
    private val hinge: Location,
    private val direction: Vector,
    private val isCreative: Boolean,
    private val player: Player,
    private val axisX: Float,
    private val axisY: Float,
    private val axisZ: Float
) : BukkitRunnable() {

    private var tick = 0
    private val referenceTicks = 45.0f
    private val damageSource = DamageSource.builder(DamageType.FALLING_BLOCK).build()

    private var verticalVelocity = 0.0
    private var verticalDisplacement = 0.0
    private val gravityAcceleration = 0.015

    private val tempRotated = Vector3f()
    private val cachedLocations = Array(displaysAndDrops.size) {
        Location(hinge.world, 0.0, 0.0, 0.0)
    }

    private val stumpFakeEntityId = 999120 + hinge.blockX + hinge.blockZ

    // Dynamic measurements to target only log segments in the upper half of the tree
    private val maxOffsetY = displaysAndDrops.maxOfOrNull { it.offset.y } ?: 0f
    private val upperHalfThreshold = maxOffsetY / 2.0f

    override fun run() {
        try {
            val progress = (tick / referenceTicks).coerceAtMost(1.0f)
            val ease = progress * progress
            val angle = ease * (Math.PI / 2.0).toFloat()

            val quat = Quaternionf().rotationAxis(angle, axisX, axisY, axisZ)
            var collided = false

            if (tick % 15 == 0 && tick > 0) {
                hinge.world.playSound(hinge, Sound.BLOCK_WOOD_STEP, 0.6f, 0.5f)
            }

            val crackStage = (ease * 9).toInt().coerceIn(0, 9)
            sendStumpCracks(crackStage)

            if (progress >= 0.5f) {
                val gravityProgress = (progress - 0.5f) * 2.0f
                val currentGravity = gravityAcceleration * gravityProgress
                verticalVelocity += currentGravity
                verticalDisplacement += verticalVelocity
            }

            // PASS 1: Calculate provisional locations and measure ground-penetration (clipping)
            var maxClipping = 0.0
            val groundCache = HashMap<Long, Double>()

            for (i in displaysAndDrops.indices) {
                val info = displaysAndDrops[i]

                info.offset.rotate(quat, tempRotated)

                val newLoc = cachedLocations[i]
                newLoc.x = hinge.x + tempRotated.x
                newLoc.y = hinge.y + tempRotated.y - verticalDisplacement
                newLoc.z = hinge.z + tempRotated.z

                // Cache column heights using optimized coordinates key
                val chunkKey = (newLoc.blockX.toLong() shl 32) or (newLoc.blockZ.toLong() and 0xFFFFFFFFL)
                val groundY = groundCache.getOrPut(chunkKey) {
                    getGroundYBelow(newLoc.world, newLoc.blockX, newLoc.y, newLoc.blockZ)
                }

                val clipping = groundY - newLoc.y
                if (clipping > maxClipping) {
                    maxClipping = clipping
                }
            }

            // PASS 2: Shift all blocks rigidly by the maximum clipping value and run upper-half log collisions
            for (i in displaysAndDrops.indices) {
                val info = displaysAndDrops[i]
                val newLoc = cachedLocations[i]

                // Adjust height uniformly so the tree remains connected
                newLoc.y = newLoc.y + maxClipping

                // Collision logic strictly for trunk segments in the upper half of the tree
                if (tick > 5 && info.isTrunk && info.offset.y >= upperHalfThreshold) {
                    val blockType = newLoc.block.type

                    val isPropagule = blockType == Material.MANGROVE_PROPAGULE
                    val isMossCarpet = blockType == Material.MOSS_CARPET
                    val isCocoa = blockType == Material.COCOA

                    val shouldIgnore = TimberUtils.isFoliage(blockType) ||
                            TimberUtils.isVine(blockType) ||
                            isPropagule || isMossCarpet || isCocoa

                    if (blockType.isSolid && blockType != Material.AIR && !shouldIgnore) {
                        collided = true
                    }

                    val nearbyEntities = newLoc.world.getNearbyEntities(newLoc, 0.5, 0.5, 0.5)
                    for (entity in nearbyEntities) {
                        if (entity is LivingEntity && entity !is ArmorStand) {
                            if (entity is Player && (entity.gameMode == GameMode.CREATIVE || entity.gameMode == GameMode.SPECTATOR)) {
                                continue
                            }
                            if (entity == player && tick < 12) {
                                continue
                            }
                            entity.damage(10.0, damageSource)
                            collided = true
                        }
                    }
                }
            }

            if (collided || tick > 150) {
                finishFall()
                this.cancel()
                return
            }

            for (i in displaysAndDrops.indices) {
                val info = displaysAndDrops[i]
                info.display.teleport(cachedLocations[i])
                info.display.transformation = Transformation(
                    Vector3f(),
                    quat,
                    Vector3f(1f, 1f, 1f),
                    Quaternionf()
                )
            }
            tick++
        } catch (e: Exception) {
            finishFall()
            this.cancel()
        }
    }

    /**
     * Resolves the exact ground level at the current column (X, Z) while ignoring any overhead
     * blocks (like adjacent leaves, roof structures, or cliffs above) to prevent layout jumping.
     */
    private fun getGroundYBelow(world: World, x: Int, tempY: Double, z: Int): Double {
        val highestY = world.getHighestBlockYAt(x, z).toDouble()
        // If the absolute highest block in the column is below the entity's current height,
        // it is the actual ground. We can use it instantly in O(1) time.
        if (highestY <= tempY + 1.0) {
            return highestY
        }

        // Otherwise, there is an overhead block. We search downwards starting
        // from the block's theoretical position.
        var y = (tempY + 1.0).toInt()
        val minHeight = world.minHeight
        while (y > minHeight) {
            val type = world.getType(x, y, z)
            // Skip non-solid blocks, foliage, and vines so they are ignored during the downward trace
            if (type.isSolid && type != Material.AIR && !TimberUtils.isFoliage(type) && !TimberUtils.isVine(type)) {
                return y.toDouble() + 1.0
            }
            y--
        }
        return minHeight.toDouble()
    }

    private fun sendStumpCracks(stage: Int) {
        val players = hinge.world.getNearbyEntities(hinge, 32.0, 32.0, 32.0)
        for (entity in players) {
            if (entity is Player) {
                PacketHelper.sendBlockCrack(entity, stumpFakeEntityId, hinge, stage)
            }
        }
    }

    private fun finishFall() {
        val world = hinge.world

        sendStumpCracks(-1)

        world.playSound(hinge, Sound.BLOCK_WOOD_BREAK, 1.2f, 0.6f)
        world.playSound(hinge, Sound.BLOCK_GRASS_BREAK, 1.2f, 0.8f)

        val dropVelocity = direction.clone().multiply(0.4).setY(0.25)

        for (info in displaysAndDrops) {
            val loc = info.display.location
            info.display.remove()

            val material = info.blockData.material

            if (TimberUtils.isFoliage(material)) {
                TimberUtils.spawnFoliageParticles(world, loc, info.blockData)
            } else if (TimberUtils.isTrunk(material)) {
                world.spawnParticle(Particle.BLOCK, loc, 5, 0.3, 0.3, 0.3, 0.0, info.blockData)
            }

            if (!isCreative) {
                for (drop in info.drops) {
                    world.dropItem(loc, drop) { item ->
                        item.velocity = dropVelocity
                    }
                }
            }
        }
    }
}