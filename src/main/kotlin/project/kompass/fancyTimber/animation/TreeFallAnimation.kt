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
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import project.kompass.fancyTimber.FancyTimber
import project.kompass.fancyTimber.LaydownBlockData
import project.kompass.fancyTimber.model.AnimationConfig
import project.kompass.fancyTimber.model.AnimationConfig.FellMode
import project.kompass.fancyTimber.model.BlockDataInfo
import project.kompass.fancyTimber.packet.PacketHelper
import project.kompass.fancyTimber.util.AnimationFeedback
import project.kompass.fancyTimber.util.SchedulerTask
import project.kompass.fancyTimber.util.SchedulerUtil
import project.kompass.fancyTimber.util.TimberUtils
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class AnimationFrame(
    val tick: Int,
    val progress: Float,
    val rotations: List<Quaternionf>,
    val translations: List<Vector3f>
)

class TreeFallAnimation(
    private val plugin: FancyTimber,
    private val displaysAndDrops: List<BlockDataInfo>,
    private val hinge: Location,
    private val direction: Vector,
    private val isCreative: Boolean,
    private val player: Player,
    private val treeFamily: String,
    private val config: AnimationConfig,
    private val baseDuration: Int,
    private val frames: List<AnimationFrame>
) {

    private var tick = 0
    private var lastReportedPercent = -1
    private var lastCrackStage = -2
    private var scheduledTask: SchedulerTask? = null
    private val damageSource = DamageSource.builder(DamageType.FALLING_BLOCK).build()
    private val damagedEntities = HashSet<UUID>()

    private val cachedLocations = Array(displaysAndDrops.size) {
        Location(hinge.world, 0.0, 0.0, 0.0)
    }

    private val stumpFakeEntityId = 999120 + hinge.blockX + hinge.blockZ
    private val maxOffsetY = displaysAndDrops.maxOfOrNull { it.offset.y } ?: 0f
    private val upperHalfThreshold = maxOffsetY / 2.0f
    private val searchRadius = calculateSearchRadius()
    private val unitScale = Vector3f(1f, 1f, 1f)
    private val zeroVector = Vector3f()
    private val identityQuat = Quaternionf()

    fun start() {
        playStartEffects()
        scheduledTask = SchedulerUtil.runRegionTaskTimer(plugin, hinge, 1L, 1L) {
            runTick()
        }
    }

    private fun runTick() {
        try {
            if (tick >= frames.size) {
                finishFall()
                scheduledTask?.cancel()
                return
            }

            val frame = frames[tick]
            var collided = false

            if (config.playSound && tick % 15 == 0 && tick > 0) {
                hinge.world.playSound(hinge, Sound.BLOCK_WOOD_STEP, 0.6f, 0.5f)
            }

            val crackStage = (frame.progress * 9f).toInt().coerceIn(0, 9)
            if (crackStage != lastCrackStage) {
                lastCrackStage = crackStage
                sendStumpCracks(crackStage)
            }

            val percent = (frame.progress * 100f).toInt().coerceIn(0, 100)
            if (percent != lastReportedPercent && tick % 4 == 0) {
                lastReportedPercent = percent
                AnimationFeedback.onProgress(player, percent, config)
            }

            val candidateTargets = ArrayList<Pair<LivingEntity, BoundingBox>>()
            if (tick > 5) {
                val rawEntities = hinge.world.getNearbyLivingEntities(hinge, searchRadius, searchRadius, searchRadius) { entity ->
                    entity !is ArmorStand && entity.uniqueId !in damagedEntities
                }
                for (entity in rawEntities) {
                    if (entity is Player && (entity.gameMode == GameMode.CREATIVE || entity.gameMode == GameMode.SPECTATOR)) {
                        continue
                    }
                    if (entity == player && tick < 12) continue
                    candidateTargets.add(Pair(entity, entity.boundingBox.expand(0.5)))
                }
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

            val world = hinge.world
            val rng = ThreadLocalRandom.current()

            for (i in displaysAndDrops.indices) {
                val info = displaysAndDrops[i]
                val newLoc = cachedLocations[i]

                newLoc.y = newLoc.y + maxClipping

                if (config.showParticles) {
                    if (!info.isTrunk && config.leafParticles && tick % 3 == 0 && rng.nextDouble() <= config.particleDensity) {
                        TimberUtils.spawnLeafTrailParticle(world, newLoc, info.blockData)
                    } else if (info.isTrunk && config.trunkDustTrails && frame.progress >= 0.22f && tick % 5 == 0 && rng.nextDouble() <= config.particleDensity * 0.45) {
                        world.spawnParticle(Particle.BLOCK, newLoc, 1, 0.16, 0.16, 0.16, 0.03, info.blockData)
                    }
                }

                if (tick > 5 && info.isTrunk && info.offset.y >= upperHalfThreshold) {
                    val blockType = world.getType(newLoc.blockX, newLoc.blockY, newLoc.blockZ)

                    val shouldIgnore = TimberUtils.isFoliage(blockType) ||
                            TimberUtils.isVine(blockType) ||
                            TimberUtils.isTopAttachment(blockType) ||
                            TimberUtils.isExcludedFromScan(blockType)

                    if (blockType.isSolid && blockType != Material.AIR && !shouldIgnore) {
                        collided = true
                    }

                    if (candidateTargets.isNotEmpty()) {
                        val blockX = newLoc.x
                        val blockY = newLoc.y
                        val blockZ = newLoc.z

                        for ((entity, hitBox) in candidateTargets) {
                            if (entity.uniqueId in damagedEntities) continue
                            if (hitBox.contains(blockX, blockY, blockZ)) {
                                damagedEntities.add(entity.uniqueId)
                                entity.damage(10.0, damageSource)
                                collided = true
                            }
                        }
                    }
                }
            }

            if (collided || (config.fellMode == FellMode.LAYDOWN && tick >= baseDuration)) {
                finishFall()
                scheduledTask?.cancel()
                return
            }

            for (i in displaysAndDrops.indices) {
                val info = displaysAndDrops[i]
                if (!info.display.isValid) continue
                info.display.teleport(cachedLocations[i])
                info.display.transformation = Transformation(
                    zeroVector,
                    frame.rotations[i],
                    unitScale,
                    identityQuat
                )
            }
            tick++
        } catch (_: IllegalStateException) {
            finishFall()
            scheduledTask?.cancel()
        } catch (_: IllegalArgumentException) {
            finishFall()
            scheduledTask?.cancel()
        }
    }

    private fun playStartEffects() {
        val world = hinge.world
        val baseData = displaysAndDrops.firstOrNull()?.blockData ?: Material.OAK_LOG.createBlockData()

        if (config.showParticles) {
            val dustCount = min(30, 8 + displaysAndDrops.size / 5)
            world.spawnParticle(Particle.BLOCK, hinge.clone().add(0.0, 0.25, 0.0), dustCount, 0.35, 0.18, 0.35, 0.08, baseData)
            world.spawnParticle(Particle.CRIT, hinge.clone().add(0.0, 0.55, 0.0), 5, 0.25, 0.2, 0.25, 0.02)
        }

        if (config.playSound && config.trunkCrackSounds) {
            val interval = max(3L, (baseDuration / 5).toLong())
            var pulse = 0
            var crackTask: SchedulerTask? = null
            crackTask = SchedulerUtil.runRegionTaskTimer(plugin, hinge, 1L, interval) {
                if (pulse >= 3) {
                    crackTask?.cancel()
                    return@runRegionTaskTimer
                }
                val pitch = 0.72f + pulse * 0.08f
                world.playSound(hinge, Sound.BLOCK_WOOD_HIT, 0.55f, pitch)
                if (pulse == 2) {
                    world.playSound(hinge, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.35f, 0.75f)
                }
                pulse++
            }
        }
    }

    private fun finishFall() {
        val world = hinge.world
        sendStumpCracks(-1)
        AnimationFeedback.onComplete(player, config)

        var sumX = 0.0
        var sumY = 0.0
        var sumZ = 0.0
        var count = 0
        for (info in displaysAndDrops) {
            if (!info.display.isValid) continue
            val loc = info.display.location
            sumX += loc.x
            sumY += loc.y
            sumZ += loc.z
            count++
        }
        val impactLoc = if (count > 0) Location(world, sumX / count, sumY / count, sumZ / count) else hinge

        val primaryData = displaysAndDrops.firstOrNull()?.blockData ?: Material.OAK_LOG.createBlockData()
        playLandingEffects(world, impactLoc, treeFamily, primaryData)

        if (config.fellMode == FellMode.LAYDOWN) {
            for (info in displaysAndDrops) {
                val bd = info.display
                if (!bd.isValid) continue
                plugin.restingLaydownBlocks[bd.uniqueId] = LaydownBlockData(
                    drops = info.drops.toList(),
                    isTrunk = info.isTrunk
                )
            }
            return
        }

        val dropVelocity = direction.clone().multiply(0.4).setY(0.25)

        for (info in displaysAndDrops) {
            val loc = info.display.location
            plugin.activeDisplays.remove(info.display)
            info.display.remove()

            val material = info.blockData.material

            if (config.showParticles) {
                if (TimberUtils.isFoliage(material) || TimberUtils.isTopAttachment(material)) {
                    TimberUtils.spawnFoliageParticles(world, loc, info.blockData)
                } else if (TimberUtils.isTrunk(material)) {
                    world.spawnParticle(Particle.BLOCK, loc, 5, 0.3, 0.3, 0.3, 0.0, info.blockData)
                }
            }

            if (!isCreative) {
                for (drop in info.drops) {
                    if (drop.type != Material.AIR) {
                        world.dropItem(loc, drop) { item ->
                            item.velocity = dropVelocity
                        }
                    }
                }
            }
        }
    }

    private fun playLandingEffects(world: World, impactLoc: Location, family: String, primaryData: org.bukkit.block.data.BlockData) {
        if (config.playSound) {
            val blockType = world.getType(impactLoc.blockX, impactLoc.blockY, impactLoc.blockZ)
            val woodBreakSound = TimberUtils.getWoodBreakSound(family)
            world.playSound(impactLoc, woodBreakSound, 1.2f, 0.6f)
            world.playSound(impactLoc, Sound.BLOCK_WOOD_FALL, 0.95f, 0.62f)

            val name = blockType.name
            when {
                name.contains("WATER") || name.contains("LAVA") -> {
                    world.playSound(impactLoc, Sound.ENTITY_GENERIC_SPLASH, 1.0f, 0.8f)
                    if (config.showParticles) {
                        world.spawnParticle(Particle.SPLASH, impactLoc.clone().add(0.0, 0.5, 0.0), 20, 0.5, 0.2, 0.5, 0.1)
                    }
                }
                name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("TUFF") ->
                    world.playSound(impactLoc, Sound.BLOCK_STONE_BREAK, 1.0f, 0.7f)
                name.contains("SAND") ->
                    world.playSound(impactLoc, Sound.BLOCK_SAND_BREAK, 1.1f, 0.8f)
                name.contains("SNOW") ->
                    world.playSound(impactLoc, Sound.BLOCK_SNOW_BREAK, 1.1f, 0.8f)
                else ->
                    world.playSound(impactLoc, Sound.BLOCK_GRASS_BREAK, 1.0f, 0.8f)
            }
        }

        if (config.showParticles && config.impactShockwave) {
            val pulses = min(5, max(2, displaysAndDrops.size / 35 + 2))
            var pulse = 0
            var ringTask: SchedulerTask? = null
            ringTask = SchedulerUtil.runRegionTaskTimer(plugin, impactLoc, 1L, 2L) {
                if (pulse >= pulses) {
                    ringTask?.cancel()
                    return@runRegionTaskTimer
                }
                val radius = 0.75 + pulse * 0.55
                val points = 10 + pulse * 5
                for (i in 0 until points) {
                    val angle = (Math.PI * 2.0) * i.toDouble() / points.toDouble()
                    val ringLoc = impactLoc.clone().add(cos(angle) * radius + 0.5, 0.12, sin(angle) * radius + 0.5)
                    world.spawnParticle(Particle.BLOCK, ringLoc, 1, 0.04, 0.02, 0.04, 0.02, primaryData)
                }
                world.spawnParticle(Particle.CLOUD, impactLoc.clone().add(0.5, 0.18, 0.5), 2, radius * 0.15, 0.03, radius * 0.15, 0.01)
                pulse++
            }
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
        return sqrt(maxDistSq) + 2.0
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
            if (type.isSolid && type != Material.AIR &&
                !TimberUtils.isFoliage(type) && !TimberUtils.isVine(type) && !TimberUtils.isTopAttachment(type)
            ) {
                return y.toDouble() + 1.0
            }
            y--
        }
        return minHeight.toDouble()
    }

    private fun sendStumpCracks(stage: Int) {
        val world = hinge.world
        for (p in world.players) {
            if (p.location.distanceSquared(hinge) <= 1024.0) {
                PacketHelper.sendBlockCrack(p, stumpFakeEntityId, hinge, stage)
            }
        }
    }

    companion object {
        fun precalculate(
            displays: List<BlockDataInfo>,
            fallDirection: Vector,
            axisX: Float,
            axisY: Float,
            axisZ: Float,
            baseDuration: Int,
            totalTicks: Int,
            config: AnimationConfig,
            gravityAcceleration: Double = 0.015
        ): List<AnimationFrame> {
            val frames = ArrayList<AnimationFrame>(totalTicks + 1)
            val maxTiltRadians = Math.toRadians(config.maxTiltDegrees).toFloat()
            val fallDirX = fallDirection.x.toFloat()
            val fallDirZ = fallDirection.z.toFloat()
            val slideDist = if (config.fellMode == FellMode.LAYDOWN) config.laydown.slideDistance else 0.0

            var verticalVelocity = 0.0
            var verticalDisplacement = 0.0

            for (tick in 0..totalTicks) {
                val baseProgress = (tick.toFloat() / baseDuration.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)

                if (baseProgress >= 0.5f && config.fellMode != FellMode.LAYDOWN) {
                    val gravityProgress = (baseProgress - 0.5f) * 2.0f
                    val currentGravity = gravityAcceleration * gravityProgress
                    verticalVelocity += currentGravity
                    verticalDisplacement += verticalVelocity
                }

                val baseSmooth = (baseProgress * baseProgress * baseProgress * (baseProgress * (baseProgress * 6.0 - 15.0) + 10.0)).pow(config.gravityCurve).toFloat()
                val sharedBaseQuat = Quaternionf().rotationAxis(maxTiltRadians * baseSmooth, axisX, axisY, axisZ)

                val rotations = ArrayList<Quaternionf>(displays.size)
                val translations = ArrayList<Vector3f>(displays.size)

                for (info in displays) {
                    val activeTicks = max(0, tick - info.delayTicks)
                    val activeDuration = max(1, baseDuration - info.delayTicks)
                    val blockProgress = (activeTicks.toDouble() / activeDuration.toDouble()).coerceIn(0.0, 1.0)

                    val quat = if (info.delayTicks == 0 && info.swayStrength <= 0f) {
                        sharedBaseQuat
                    } else {
                        val smootherstep = blockProgress * blockProgress * blockProgress * (blockProgress * (blockProgress * 6.0 - 15.0) + 10.0)
                        val eased = smootherstep.pow(config.gravityCurve).toFloat()
                        val theta = maxTiltRadians * eased
                        val q = Quaternionf().rotationAxis(theta, axisX, axisY, axisZ)

                        if (info.swayStrength > 0f && blockProgress > 0.0 && blockProgress < 1.0) {
                            val envelope = sin(blockProgress * Math.PI)
                            val sway = (sin(tick * 0.32 + info.swayPhase) * info.swayStrength * envelope).toFloat()
                            q.rotateAxis(sway, 0f, 1f, 0f)
                            if (fallDirX * fallDirX + fallDirZ * fallDirZ > 0.001f) {
                                val flex = (sway * 0.45f * (0.4 + info.heightFactor)).toFloat()
                                q.rotateAxis(flex, fallDirX, 0f, fallDirZ)
                            }
                        }
                        q
                    }

                    val rotatedOffset = Vector3f(info.offset).rotate(quat)

                    val sag = if (blockProgress > 0.0) {
                        (-sin(blockProgress * (Math.PI / 2.0)) * info.heightFactor * (if (info.isTrunk) 0.035 else 0.08)).toFloat()
                    } else 0f

                    rotatedOffset.y += sag - verticalDisplacement.toFloat()

                    if (slideDist > 0.0) {
                        val slideProgress = min(1.0, blockProgress * 1.2)
                        rotatedOffset.x += (fallDirX * slideDist * slideProgress).toFloat()
                        rotatedOffset.z += (fallDirZ * slideDist * slideProgress).toFloat()
                    }

                    rotations.add(quat)
                    translations.add(rotatedOffset)
                }

                frames.add(AnimationFrame(tick, baseProgress, rotations, translations))
            }
            return frames
        }
    }
}