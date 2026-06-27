package project.kompass.fancyTimber.animation

import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import project.kompass.fancyTimber.model.BlockDataInfo
import project.kompass.fancyTimber.packet.PacketHelper
import project.kompass.fancyTimber.util.TimberUtils

data class AnimationFrame(
    val tick: Int,
    val angle: Float,
    val quat: Quaternionf,
    val translations: List<Vector3f>
)

class TreeFallAnimation(
    private val displaysAndDrops: List<BlockDataInfo>,
    private val hinge: Location,
    private val direction: Vector,
    private val isCreative: Boolean,
    private val player: Player,
    private val axisX: Float,
    private val axisY: Float,
    private val axisZ: Float,
    private val frames: List<AnimationFrame>
) : BukkitRunnable() {

    private var tick = 0
    private val damageSource = DamageSource.builder(DamageType.FALLING_BLOCK).build()

    private val cachedLocations = Array(displaysAndDrops.size) {
        Location(hinge.world, 0.0, 0.0, 0.0)
    }

    private val stumpFakeEntityId = 999120 + hinge.blockX + hinge.blockZ

    private val maxOffsetY = displaysAndDrops.maxOfOrNull { it.offset.y } ?: 0f
    private val upperHalfThreshold = maxOffsetY / 2.0f

    private val searchRadius = calculateSearchRadius()

    override fun run() {
        try {
            if (tick >= frames.size) {
                finishFall()
                this.cancel()
                return
            }

            val frame = frames[tick]
            var collided = false

            if (tick % 15 == 0 && tick > 0) {
                hinge.world.playSound(hinge, Sound.BLOCK_WOOD_STEP, 0.6f, 0.5f)
            }

            val crackStage = ((tick / 45.0f).coerceAtMost(1.0f) * 9).toInt().coerceIn(0, 9)
            sendStumpCracks(crackStage)

            val entities = hinge.world.getNearbyLivingEntities(hinge, searchRadius, searchRadius, searchRadius) { entity ->
                entity !is ArmorStand
            }

            var maxClipping = 0.0
            val groundCache = HashMap<Long, Double>()

            for (i in displaysAndDrops.indices) {
                val translation = frame.translations[i]

                val newLoc = cachedLocations[i]
                newLoc.x = hinge.x + translation.x
                newLoc.y = hinge.y + translation.y
                newLoc.z = hinge.z + translation.z

                val chunkKey = (newLoc.blockX.toLong() shl 32) or (newLoc.blockZ.toLong() and 0xFFFFFFFFL)
                val groundY = groundCache.getOrPut(chunkKey) {
                    getGroundYBelow(newLoc.world, newLoc.blockX, newLoc.y, newLoc.blockZ)
                }

                val clipping = groundY - newLoc.y
                if (clipping > maxClipping) {
                    maxClipping = clipping
                }
            }

            for (i in displaysAndDrops.indices) {
                val info = displaysAndDrops[i]
                val newLoc = cachedLocations[i]

                newLoc.y = newLoc.y + maxClipping

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

                    val blockX = newLoc.x
                    val blockY = newLoc.y
                    val blockZ = newLoc.z

                    for (entity in entities) {
                        if (entity is Player && (entity.gameMode == GameMode.CREATIVE || entity.gameMode == GameMode.SPECTATOR)) {
                            continue
                        }
                        if (entity == player && tick < 12) {
                            continue
                        }

                        val hitBox = entity.boundingBox.expand(0.5)
                        if (hitBox.contains(blockX, blockY, blockZ)) {
                            entity.damage(10.0, damageSource)
                            collided = true
                        }
                    }
                }
            }

            if (collided) {
                finishFall()
                this.cancel()
                return
            }

            for (i in displaysAndDrops.indices) {
                val info = displaysAndDrops[i]
                info.display.teleport(cachedLocations[i])
                info.display.transformation = Transformation(
                    Vector3f(),
                    frame.quat,
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

    private fun calculateSearchRadius(): Double {
        var maxDistSq = 1.0
        for (info in displaysAndDrops) {
            val distSq = info.offset.lengthSquared().toDouble()
            if (distSq > maxDistSq) {
                maxDistSq = distSq
            }
        }
        return Math.sqrt(maxDistSq) + 2.0
    }

    private fun getGroundYBelow(world: World, x: Int, tempY: Double, z: Int): Double {
        val highestY = world.getHighestBlockYAt(x, z).toDouble()
        if (highestY <= tempY + 1.0) {
            return highestY
        }

        var y = (tempY + 1.0).toInt()
        val minHeight = world.minHeight
        while (y > minHeight) {
            val type = world.getType(x, y, z)
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

        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        var count = 0
        for (info in displaysAndDrops) {
            val loc = info.display.location
            sumX += loc.x
            sumY += loc.y
            sumZ += loc.z
            count++
        }
        val impactLoc = if (count > 0) Location(world, sumX / count, sumY / count, sumZ / count) else hinge

        val family = TimberUtils.getTreeFamily(displaysAndDrops.firstOrNull()?.blockData?.material ?: Material.OAK_LOG)
        playLandingEffects(world, impactLoc, family)

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

    private fun playLandingEffects(world: World, impactLoc: Location, family: String) {
        val blockType = impactLoc.block.type

        // Play the wooden impact sound relative to the species
        val woodBreakSound = TimberUtils.getWoodBreakSound(family)
        world.playSound(impactLoc, woodBreakSound, 1.2f, 0.6f)

        // Play the ground-type surface response
        val name = blockType.name
        when {
            name.contains("WATER") || name.contains("LAVA") -> {
                world.playSound(impactLoc, Sound.ENTITY_GENERIC_SPLASH, 1.0f, 0.8f)
                world.spawnParticle(Particle.SPLASH, impactLoc.add(0.0, 0.5, 0.0), 20, 0.5, 0.2, 0.5, 0.1)
            }
            name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("TUFF") -> {
                world.playSound(impactLoc, Sound.BLOCK_STONE_BREAK, 1.0f, 0.7f)
            }
            name.contains("SAND") -> {
                world.playSound(impactLoc, Sound.BLOCK_SAND_BREAK, 1.1f, 0.8f)
            }
            name.contains("SNOW") -> {
                world.playSound(impactLoc, Sound.BLOCK_SNOW_BREAK, 1.1f, 0.8f)
            }
            else -> { // Default to standard grassy soil
                world.playSound(impactLoc, Sound.BLOCK_GRASS_BREAK, 1.0f, 0.8f)
            }
        }
    }

    companion object {
        fun precalculate(
            displays: List<BlockDataInfo>,
            axisX: Float,
            axisY: Float,
            axisZ: Float,
            referenceTicks: Float,
            totalTicks: Int,
            gravityAcceleration: Double
        ): List<AnimationFrame> {
            val frames = ArrayList<AnimationFrame>(totalTicks)
            var verticalVelocity = 0.0
            var verticalDisplacement = 0.0

            for (tick in 0..totalTicks) {
                val progress = (tick / referenceTicks).coerceAtMost(1.0f)
                val eased = progress * progress * progress * (progress * (progress * 6.0f - 15.0f) + 10.0f)
                val angle = eased * (Math.PI / 2.0).toFloat()

                val quat = Quaternionf().rotationAxis(angle, axisX, axisY, axisZ)

                if (progress >= 0.5f) {
                    val gravityProgress = (progress - 0.5f) * 2.0f
                    val currentGravity = gravityAcceleration * gravityProgress
                    verticalVelocity += currentGravity
                    verticalDisplacement += verticalVelocity
                }

                val translations = ArrayList<Vector3f>(displays.size)
                for (info in displays) {
                    val rotatedOffset = Vector3f(info.offset).rotate(quat)
                    rotatedOffset.y -= verticalDisplacement.toFloat()
                    translations.add(rotatedOffset)
                }

                frames.add(AnimationFrame(tick, angle, quat, translations))
            }
            return frames
        }
    }
}