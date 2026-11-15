package project.kompass.fancyTimber.listener

import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageAbortEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector
import org.joml.Vector3f
import project.kompass.fancyTimber.FancyTimber
import project.kompass.fancyTimber.animation.TreeFallAnimation
import project.kompass.fancyTimber.model.BlockDataInfo
import project.kompass.fancyTimber.model.BlockPos
import project.kompass.fancyTimber.scanner.TreeScanner
import project.kompass.fancyTimber.util.AnimationFeedback
import project.kompass.fancyTimber.util.ProtectionManager
import project.kompass.fancyTimber.util.SchedulerUtil
import project.kompass.fancyTimber.util.TimberUtils
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.max
import kotlin.math.roundToInt

class BlockBreakListener(
    private val plugin: FancyTimber,
    private val silentBlocks: MutableSet<BlockPos>
) : Listener {

    private val cooldowns = ConcurrentHashMap<UUID, Long>()
    private val lastDamageScanMs = ConcurrentHashMap<UUID, Long>()
    private val fatiguedPlayers = ConcurrentHashMap.newKeySet<UUID>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockDamage(event: BlockDamageEvent) {
        if (ProtectionManager.isSimulating()) return
        val player = event.player
        val block = event.block
        val tool = player.inventory.itemInMainHand

        if (!TimberUtils.isTrunk(block.type)) return
        if (tool.type == Material.AIR || !TimberUtils.isAxe(tool.type)) return
        if (!player.isSneaking) return

        val uuid = player.uniqueId

        // 1. If the clicked block is already part of the player's cached tree, skip re-scanning
        val existingCache = plugin.scanCache[uuid]
        if (existingCache != null && block in existingCache.logs) {
            return
        }

        // 2. Debounce rapid clicks (250ms cooldown between damage scans)
        val now = System.currentTimeMillis()
        val lastScan = lastDamageScanMs.getOrDefault(uuid, 0L)
        if (now - lastScan < 250L) return
        lastDamageScanMs[uuid] = now

        val config = plugin.animationConfig

        // Run scan safely on the region thread owning the block (BlockDamageEvent is already on this thread)
        val scanResult = TreeScanner.detectTree(
            startBlock = block,
            debugReceiver = null,
            maxLogs = config.maxAnimatedLogs,
            maxLeaves = config.maxAnimatedLeaves
        ) ?: return

        plugin.scanCache[uuid] = scanResult
        removeMiningFatigue(player)

        if (scanResult.blocks.size > 15) {
            player.addPotionEffect(
                PotionEffect(PotionEffectType.MINING_FATIGUE, 200, 0, true, false, false)
            )
            fatiguedPlayers.add(uuid)
        }
    }

    @EventHandler
    fun onBlockDamageAbort(event: BlockDamageAbortEvent) {
        val player = event.player
        plugin.scanCache.remove(player.uniqueId)
        removeMiningFatigue(player)
    }

    @EventHandler
    fun onPlayerItemHeld(event: PlayerItemHeldEvent) {
        val player = event.player
        plugin.scanCache.remove(player.uniqueId)
        removeMiningFatigue(player)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        plugin.scanCache.remove(uuid)
        cooldowns.remove(uuid)
        lastDamageScanMs.remove(uuid)
        removeMiningFatigue(event.player)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (ProtectionManager.isSimulating()) return

        val startBlock = event.block
        val player = event.player
        val tool = player.inventory.itemInMainHand
        val animConfig = plugin.animationConfig

        if (!TimberUtils.isTrunk(startBlock.type)) return
        if (tool.type == Material.AIR || !TimberUtils.isAxe(tool.type)) return

        removeMiningFatigue(player)

        if (!player.isSneaking) {
            plugin.scanCache.remove(player.uniqueId)
            return
        }

        val now = System.currentTimeMillis()
        val cooldownEnd = cooldowns.getOrDefault(player.uniqueId, 0L)
        if (cooldownEnd > now) {
            AnimationFeedback.onCooldown(player, cooldownEnd - now, animConfig)
            event.isCancelled = true
            return
        }

        val debugReceiver = if (plugin.debugMode) player else null
        val cached = plugin.scanCache.remove(player.uniqueId)

        // Validate that the cached scan actually belongs to the broken block
        val scanResult = if (cached != null && startBlock in cached.logs && ! plugin.debugMode) {
            cached
        } else {
            TreeScanner.detectTree(
                startBlock = startBlock,
                debugReceiver = debugReceiver,
                maxLogs = animConfig.maxAnimatedLogs,
                maxLeaves = animConfig.maxAnimatedLeaves
            )
        } ?: return

        // Deduplicate protection checks by (X, Z) column to avoid hundreds of redundant claim queries
        val columnProtectionCache = HashMap<Long, Boolean>()
        val allowedBlocks = ArrayList<Block>(scanResult.blocks.size)

        for (b in scanResult.blocks) {
            val colKey = (b.x.toLong() shl 32) or (b.z.toLong() and 0xFFFFFFFFL)
            val allowed = columnProtectionCache.getOrPut(colKey) {
                ProtectionManager.canBreak(player, b, simulateEvent = false)
            }
            if (allowed) {
                allowedBlocks.add(b)
            }
        }

        if (allowedBlocks.isEmpty()) {
            AnimationFeedback.onProtected(player, animConfig)
            return
        }

        val baseBlock = scanResult.baseBlock
        val treeFamily = scanResult.treeFamily
        val tallestHeight = scanResult.tallestHeight

        event.isCancelled = true
        val isCreative = player.gameMode == GameMode.CREATIVE

        AnimationFeedback.onStartChopping(player, animConfig)

        val cooldownMs = plugin.config.getLong("cooldown.ms", 0L)
        if (cooldownMs > 0L) {
            cooldowns[player.uniqueId] = now + cooldownMs
        }

        // Global entity cap protection: fallback to instant break if active displays exceed server cap
        if (!animConfig.enabled || plugin.activeDisplays.size + allowedBlocks.size > plugin.maxActiveDisplays) {
            var logsBroken = 0
            for (b in allowedBlocks) {
                if (TimberUtils.isTrunk(b.type)) logsBroken++
                if (!isCreative) {
                    b.breakNaturally(tool)
                } else {
                    b.setType(Material.AIR, false)
                }
            }
            applyDurabilityAndExhaustion(player, tool, logsBroken, allowedBlocks.size, isCreative)
            AnimationFeedback.onComplete(player, animConfig)
            return
        }

        val silentBlockList = ArrayList<BlockPos>(allowedBlocks.size)
        val hinge = baseBlock.location.add(0.5, 0.0, 0.5)

        val fallDirection = TimberUtils.resolveFallDirection(player, baseBlock, allowedBlocks, animConfig)
        var axisDir = fallDirection.clone().crossProduct(Vector(0.0, 1.0, 0.0))
        if (axisDir.lengthSquared() < 1e-5) {
            axisDir = Vector(0.0, 0.0, -1.0)
        } else {
            axisDir.normalize()
        }

        val axisX = axisDir.x.toFloat()
        val axisY = axisDir.y.toFloat()
        val axisZ = axisDir.z.toFloat()

        val baseDuration = TimberUtils.calculateFallDuration(tallestHeight, allowedBlocks.size, animConfig.fallSpeed)
        val displaysAndDrops = ArrayList<BlockDataInfo>(allowedBlocks.size)
        val rng = ThreadLocalRandom.current()
        var logsBroken = 0

        for (b in allowedBlocks) {
            val bPos = BlockPos.from(b)
            silentBlocks.add(bPos)
            silentBlockList.add(bPos)

            val isTrunk = TimberUtils.isTrunk(b.type)
            if (isTrunk) {
                logsBroken++
            }

            val relHeight = max(0, b.y - baseBlock.y)
            val heightFactor = (relHeight.toDouble() / tallestHeight.coerceAtLeast(1).toDouble()).coerceIn(0.0, 1.0)
            val delayTicks = if (!isTrunk && animConfig.leafDragTicks > 0) {
                1 + (animConfig.leafDragTicks * heightFactor * rng.nextDouble()).roundToInt()
            } else 0

            val swayPhase = (rng.nextDouble() * Math.PI * 2.0).toFloat()
            val leafMult = if (isTrunk) 0.35 else 1.45
            val swayStrength = if (animConfig.windSway) {
                (animConfig.windSwayStrength * leafMult * (0.35 + heightFactor)).toFloat()
            } else 0f

            val offset = Vector3f(
                (b.x + 0.5 - hinge.x).toFloat(),
                (b.y - hinge.y).toFloat(),
                (b.z + 0.5 - hinge.z).toFloat()
            )

            val display = b.world.spawn(b.location.add(0.5, 0.0, 0.5), BlockDisplay::class.java) { entity ->
                entity.block = b.blockData
                entity.teleportDuration = animConfig.smoothness
                entity.interpolationDelay = 0
                entity.interpolationDuration = animConfig.smoothness
                entity.shadowRadius = 0f
                entity.shadowStrength = 0f
                entity.isPersistent = false
                entity.isInvulnerable = true
                entity.isSilent = true
            }
            plugin.activeDisplays.add(display)

            displaysAndDrops.add(
                BlockDataInfo(
                    offset = offset,
                    display = display,
                    drops = b.getDrops(tool, player),
                    blockData = b.blockData,
                    isTrunk = isTrunk,
                    heightFactor = heightFactor,
                    delayTicks = delayTicks,
                    swayPhase = swayPhase,
                    swayStrength = swayStrength
                )
            )
            b.setType(Material.AIR, false)
        }

        applyDurabilityAndExhaustion(player, tool, logsBroken, allowedBlocks.size, isCreative)

        if (animConfig.playSound) {
            val nativeWoodSound = TimberUtils.getWoodBreakSound(treeFamily)
            startBlock.world.playSound(hinge, nativeWoodSound, 1.0f, 0.5f)
            startBlock.world.playSound(hinge, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.3f, 0.7f)
        }

        // Exact frame count tailored to the tree's duration + leaf drag
        val totalFrames = baseDuration + animConfig.leafDragTicks + 15
        SchedulerUtil.runAsync(plugin) {
            val precalculatedFrames = TreeFallAnimation.precalculate(
                displays = displaysAndDrops,
                fallDirection = fallDirection,
                axisX = axisX,
                axisY = axisY,
                axisZ = axisZ,
                baseDuration = baseDuration,
                totalTicks = totalFrames,
                config = animConfig
            )

            SchedulerUtil.runRegionTask(plugin, hinge) {
                TreeFallAnimation(
                    plugin = plugin,
                    displaysAndDrops = displaysAndDrops,
                    hinge = hinge,
                    direction = fallDirection,
                    isCreative = isCreative,
                    player = player,
                    treeFamily = treeFamily,
                    config = animConfig,
                    baseDuration = baseDuration,
                    frames = precalculatedFrames
                ).start()
            }
        }

        SchedulerUtil.runRegionTaskLater(plugin, hinge, 10L) {
            silentBlockList.forEach { silentBlocks.remove(it) }
        }
    }

    private fun applyDurabilityAndExhaustion(
        player: Player,
        tool: org.bukkit.inventory.ItemStack,
        logsBroken: Int,
        totalBlocks: Int,
        isCreative: Boolean
    ) {
        if (isCreative) return
        if (logsBroken > 0) {
            val durabilityDamage = TimberUtils.calculateUnbreakingDamage(logsBroken, tool)
            if (durabilityDamage > 0) {
                player.damageItemStack(EquipmentSlot.HAND, durabilityDamage)
            }
        }
        val exhaustionPerBlock = plugin.config.getDouble("balancing.exhaustion-per-block", 0.005)
        if (exhaustionPerBlock > 0.0) {
            player.exhaustion += (exhaustionPerBlock * totalBlocks).toFloat()
        }
    }

    private fun removeMiningFatigue(player: Player) {
        if (fatiguedPlayers.remove(player.uniqueId)) {
            player.removePotionEffect(PotionEffectType.MINING_FATIGUE)
        }
    }

    fun cleanupAllFatigue() {
        for (uuid in fatiguedPlayers) {
            val p = Bukkit.getPlayer(uuid)
            p?.removePotionEffect(PotionEffectType.MINING_FATIGUE)
        }
        fatiguedPlayers.clear()
    }
}