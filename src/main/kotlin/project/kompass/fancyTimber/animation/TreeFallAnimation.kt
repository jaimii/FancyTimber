package project.kompass.fancyTimber.animation

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
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

    // TPS OPTIMIZATION: Pre-allocate reusable coordinate vectors and locations
    private val tempRotated = Vector3f()
    private val cachedLocations = Array(displaysAndDrops.size) {
        Location(hinge.world, 0.0, 0.0, 0.0)
    }

    // Stable, position-derived fake entity ID for stump visual cracks
    private val stumpFakeEntityId = 999120 + hinge.blockX + hinge.blockZ

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

            // Progressively apply cracking stages to the base stump based on fall angle
            val crackStage = (ease * 9).toInt().coerceIn(0, 9)
            sendStumpCracks(crackStage)

            if (tick > 10) {
                val currentGravity = gravityAcceleration * ease
                verticalVelocity += currentGravity
                verticalDisplacement += verticalVelocity
            }

            for (i in displaysAndDrops.indices) {
                val info = displaysAndDrops[i]

                info.offset.rotate(quat, tempRotated)

                val newLoc = cachedLocations[i]
                newLoc.x = hinge.x + tempRotated.x
                newLoc.y = hinge.y + tempRotated.y - verticalDisplacement
                newLoc.z = hinge.z + tempRotated.z

                if (tick > 5 && info.offset.lengthSquared() > 0.5f) {
                    if (info.isTrunk) {
                        val fallingType = info.display.block.material
                        val isFallingRoot = fallingType == Material.MANGROVE_ROOTS || fallingType == Material.MUDDY_MANGROVE_ROOTS

                        if (!isFallingRoot && info.offset.y >= 2.0f) {
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
                }

                if (collided) break
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

        // Clean up stump cracks completely upon fall completion
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