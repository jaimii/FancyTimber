package project.kompass.fancyTimber.listener

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.Tag
import org.bukkit.entity.BlockDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
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

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val startBlock = event.block
        val player = event.player
        val tool = player.inventory.itemInMainHand

        if (!TimberUtils.isTrunk(startBlock.type)) return

        if (tool.type == Material.AIR || !Tag.ITEMS_AXES.isTagged(tool.type)) return
        if (!player.isSneaking) return

        val scanResult = TreeScanner.detectTree(startBlock) ?: return
        val treeBlocks = scanResult.blocks
        val baseBlock = scanResult.baseBlock
        val vines = scanResult.vines

        event.isDropItems = false
        val isCreative = player.gameMode == GameMode.CREATIVE

        // Track and reuse BlockPos objects instead of generating duplicates later
        val silentBlockList = ArrayList<BlockPos>(treeBlocks.size + vines.size)

        for (vine in vines) {
            val vPos = BlockPos.from(vine)
            silentBlocks.add(vPos)
            silentBlockList.add(vPos)
            vine.breakNaturally(tool)
        }

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

        startBlock.world.playSound(hinge, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.5f, 0.8f)

        TreeFallAnimation(
            displaysAndDrops = displaysAndDrops,
            hinge = hinge,
            direction = direction,
            isCreative = isCreative,
            player = player,
            axisX = axisX,
            axisY = axisY,
            axisZ = axisZ
        ).runTaskTimer(plugin, 1L, 1L)

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            silentBlockList.forEach { silentBlocks.remove(it) }
        }, 10L)
    }
}