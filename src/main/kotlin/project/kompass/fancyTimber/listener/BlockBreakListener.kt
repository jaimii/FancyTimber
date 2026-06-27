package project.kompass.fancyTimber.listener

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.Tag
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.entity.BlockDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageAbortEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector
import org.joml.Vector3f
import project.kompass.fancyTimber.FancyTimber
import project.kompass.fancyTimber.animation.TreeFallAnimation
import project.kompass.fancyTimber.model.BlockDataInfo
import project.kompass.fancyTimber.model.BlockPos
import project.kompass.fancyTimber.scanner.TreeScanner
import project.kompass.fancyTimber.util.TimberUtils

class BlockBreakListener(
    private val plugin: FancyTimber,
    private val silentBlocks: MutableSet<BlockPos>
) : Listener {

    private val fatigueKey = org.bukkit.NamespacedKey(plugin, "timber_fatigue")

    @EventHandler(ignoreCancelled = true)
    fun onBlockDamage(event: BlockDamageEvent) {
        val player = event.player
        val block = event.block
        val tool = player.inventory.itemInMainHand

        if (!TimberUtils.isTrunk(block.type)) return

        val isAxe = Tag.ITEMS_AXES.isTagged(tool.type) || tool.type.name.endsWith("_AXE")
        if (tool.type == Material.AIR || !isAxe) return
        if (!player.isSneaking) return

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val scanResult = TreeScanner.detectTree(block, null) ?: return@Runnable

            plugin.server.scheduler.runTask(plugin, Runnable {
                plugin.scanCache[player.uniqueId] = scanResult

                val breakSpeed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED) ?: return@Runnable
                removeMiningFatigue(player)

                val size = scanResult.blocks.size
                val fatiguePercent = (size / 1000.0).coerceAtMost(0.8)

                val modifier = AttributeModifier(
                    fatigueKey,
                    -fatiguePercent,
                    AttributeModifier.Operation.ADD_SCALAR
                )
                breakSpeed.addModifier(modifier)
            })
        })
    }

    @EventHandler
    fun onBlockDamageAbort(event: BlockDamageAbortEvent) {
        val player = event.player
        plugin.scanCache.remove(player.uniqueId)
        removeMiningFatigue(player)
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val startBlock = event.block
        val player = event.player
        val tool = player.inventory.itemInMainHand

        if (!TimberUtils.isTrunk(startBlock.type)) return

        val isAxe = Tag.ITEMS_AXES.isTagged(tool.type) || tool.type.name.endsWith("_AXE")
        if (tool.type == Material.AIR || !isAxe) return

        removeMiningFatigue(player)

        if (!player.isSneaking) {
            plugin.scanCache.remove(player.uniqueId)
            return
        }

        val debugMode = plugin.debugMode
        val debugReceiver = if (debugMode) player else null
        val scanResult = plugin.scanCache.remove(player.uniqueId)
            ?: TreeScanner.detectTree(startBlock, debugReceiver)
            ?: return

        val treeBlocks = scanResult.blocks
        val baseBlock = scanResult.baseBlock

        event.isCancelled = true
        val isCreative = player.gameMode == GameMode.CREATIVE

        val silentBlockList = ArrayList<BlockPos>(treeBlocks.size)
        val hinge = baseBlock.location.add(0.5, 0.0, 0.5)

        var direction = player.location.direction.setY(0.0)
        if (direction.lengthSquared() < 1e-5) {
            direction = Vector(1.0, 0.0, 0.0)
        } else {
            direction.normalize()
        }

        val axisDir = direction.clone().crossProduct(Vector(0.0, 1.0, 0.0)).normalize()
        val axisX = axisDir.x.toFloat()
        val axisY = axisDir.y.toFloat()
        val axisZ = axisDir.z.toFloat()

        val displaysAndDrops = mutableListOf<BlockDataInfo>()
        var logsBroken = 0

        for (b in treeBlocks) {
            val bPos = BlockPos.from(b)
            silentBlocks.add(bPos)
            silentBlockList.add(bPos)

            if (TimberUtils.isTrunk(b.type)) {
                logsBroken++
            }

            val offset = Vector3f(
                (b.x + 0.5 - hinge.x).toFloat(),
                (b.y - hinge.y).toFloat(),
                (b.z + 0.5 - hinge.z).toFloat()
            )

            val display = b.world.spawn(b.location.add(0.5, 0.0, 0.5), BlockDisplay::class.java) { entity ->
                entity.block = b.blockData
                entity.teleportDuration = 2
                entity.isPersistent = false
            }

            displaysAndDrops.add(
                BlockDataInfo(
                    offset,
                    display,
                    b.getDrops(tool, player),
                    b.blockData,
                    TimberUtils.isTrunk(b.type)
                )
            )
            b.setType(Material.AIR, false)
        }

        if (logsBroken > 1 && !isCreative) {
            player.damageItemStack(EquipmentSlot.HAND, logsBroken - 1)
        }

        val family = TimberUtils.getTreeFamily(startBlock.type)
        val nativeWoodSound = TimberUtils.getWoodBreakSound(family)
        startBlock.world.playSound(hinge, nativeWoodSound, 1.0f, 0.5f) // Deep wooden fracture
        startBlock.world.playSound(hinge, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.3f, 0.7f) // Splintering creak under load

        plugin.server.scheduler.runTaskAsynchronously(plugin, Runnable {
            val precalculatedFrames = TreeFallAnimation.precalculate(
                displaysAndDrops,
                axisX, axisY, axisZ,
                45.0f,
                150,
                0.015
            )

            plugin.server.scheduler.runTask(plugin, Runnable {
                TreeFallAnimation(
                    displaysAndDrops = displaysAndDrops,
                    hinge = hinge,
                    direction = direction,
                    isCreative = isCreative,
                    player = player,
                    axisX = axisX,
                    axisY = axisY,
                    axisZ = axisZ,
                    frames = precalculatedFrames
                ).runTaskTimer(plugin, 1L, 1L)
            })
        })

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            silentBlockList.forEach { silentBlocks.remove(it) }
        }, 10L)
    }

    private fun removeMiningFatigue(player: Player) {
        val breakSpeed = player.getAttribute(Attribute.BLOCK_BREAK_SPEED) ?: return
        for (modifier in breakSpeed.modifiers) {
            if (modifier.key == fatigueKey) {
                breakSpeed.removeModifier(modifier)
            }
        }
    }
}