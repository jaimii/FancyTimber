package project.kompass.fancyTimber

import com.comphenix.protocol.PacketType
import com.comphenix.protocol.ProtocolLibrary
import com.comphenix.protocol.events.ListenerPriority
import com.comphenix.protocol.events.PacketAdapter
import com.comphenix.protocol.events.PacketEvent
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.data.type.Leaves
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Transformation
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

class FancyTimber : JavaPlugin(), Listener {

    // ProtocolLib Improvement: Uses custom lightweight BlockPos instead of heavy Bukkit Location
    private data class BlockPos(val worldId: UUID, val x: Int, val y: Int, val z: Int)

    private val silentBlocks = ConcurrentHashMap.newKeySet<BlockPos>()

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)
        registerProtocolLibInterceptor()
    }

    private fun registerProtocolLibInterceptor() {
        if (server.pluginManager.getPlugin("ProtocolLib") == null) return
        val protocolManager = ProtocolLibrary.getProtocolManager()

        protocolManager.addPacketListener(
            object : PacketAdapter(this, ListenerPriority.NORMAL, PacketType.Play.Server.WORLD_EVENT) {
                override fun onPacketSending(event: PacketEvent) {
                    val packet = event.packet
                    val effectId = packet.integers.read(0)

                    if (effectId == 2001) { // Block Break particles and sound
                        val pos = packet.blockPositionModifier.read(0)
                        val blockPos = BlockPos(event.player.world.uid, pos.x, pos.y, pos.z)

                        if (silentBlocks.contains(blockPos)) {
                            event.isCancelled = true
                        }
                    }
                }
            }
        )
    }

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val startBlock = event.block
        val player = event.player
        val tool = player.inventory.itemInMainHand

        if (!isTrunk(startBlock.type)) return

        // 1.21.11 Mounts Of Mayhem update: Uses registry tags instead of string checking.
        // Also naturally rejects 1.21.11 Spears since they are not formally tagged as axes.
        if (tool.type == Material.AIR || !Tag.ITEMS_AXES.isTagged(tool.type)) return
        if (!player.isSneaking) return

        val scanResult = detectTree(startBlock) ?: return
        val treeBlocks = scanResult.blocks
        val baseBlock = scanResult.baseBlock
        val vines = scanResult.vines

        event.isDropItems = false
        val isCreative = player.gameMode == GameMode.CREATIVE

        // Break attached vines directly. They will naturally drop items and disappear without spawning displays.
        for (vine in vines) {
            val vPos = BlockPos(vine.world.uid, vine.x, vine.y, vine.z)
            silentBlocks.add(vPos)
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
            val bPos = BlockPos(b.world.uid, b.x, b.y, b.z)
            silentBlocks.add(bPos)

            if (isTrunk(b.type)) {
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
                // Improvement: Avoid saving temporary visual entities in case the server shuts down or restarts
                entity.isPersistent = false
            }

            displaysAndDrops.add(BlockDataInfo(offset, display, b.getDrops(tool, player), isTrunk(b.type)))
            b.setType(Material.AIR, false)
        }

        // Apply corresponding durability damage relative to the amount of broken logs
        if (logsBroken > 1 && !isCreative) {
            // Subtract 1 because the natively fired BlockBreakEvent handles the durability damage for the initial block broken
            player.damageItemStack(org.bukkit.inventory.EquipmentSlot.HAND, logsBroken - 1)
        }

        startBlock.world.playSound(hinge, Sound.ENTITY_ZOMBIE_BREAK_WOODEN_DOOR, 0.5f, 0.8f)

        object : BukkitRunnable() {
            var tick = 0
            val referenceTicks = 45.0f
            val damageSource = DamageSource.builder(DamageType.FALLING_BLOCK).build()

            override fun run() {
                try {
                    val progress = tick / referenceTicks
                    val ease = progress * progress
                    val angle = ease * (Math.PI / 2.0).toFloat()

                    val quat = Quaternionf().rotationAxis(angle, axisX, axisY, axisZ)
                    var collided = false

                    if (tick % 15 == 0 && tick > 0) {
                        hinge.world.playSound(hinge, Sound.BLOCK_WOOD_STEP, 0.6f, 0.5f)
                    }

                    val newLocations = ArrayList<Location>(displaysAndDrops.size)

                    for (info in displaysAndDrops) {
                        val rotatedOffset = Vector3f(info.offset).rotate(quat)
                        val newLoc = Location(
                            hinge.world,
                            hinge.x + rotatedOffset.x,
                            hinge.y + rotatedOffset.y,
                            hinge.z + rotatedOffset.z
                        )
                        newLocations.add(newLoc)

                        if (tick > 5 && info.offset.lengthSquared() > 0.5f) {
                            if (info.isTrunk) {
                                val fallingType = info.display.block.material
                                val isFallingRoot = fallingType == Material.MANGROVE_ROOTS || fallingType == Material.MUDDY_MANGROVE_ROOTS

                                // FIX: Bypass collision for roots and the bottom 2 layers of the trunk.
                                // This prevents 2x2 trees (Jungle/Dark Oak) and wide Mangrove roots from instantly
                                // breaking the animation when their off-center base blocks rotate down into the dirt.
                                if (!isFallingRoot && info.offset.y >= 2.0f) {
                                    val blockType = newLoc.block.type

                                    val isVine = blockType == Material.VINE || blockType.name.endsWith("VINES") || blockType.name.endsWith("VINES_PLANT")
                                    val isPropagule = blockType == Material.MANGROVE_PROPAGULE
                                    val isMossCarpet = blockType == Material.MOSS_CARPET
                                    val isCocoa = blockType == Material.COCOA // Add Cocoa to prevent collision with hanging beans

                                    val shouldIgnore = isFoliage(blockType) || isVine || isPropagule || isMossCarpet || isCocoa

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

                    if (collided || tick > 100) {
                        finishFall(displaysAndDrops, hinge, direction, isCreative)
                        this.cancel()
                        return
                    }

                    for (i in displaysAndDrops.indices) {
                        val info = displaysAndDrops[i]
                        info.display.teleport(newLocations[i])
                        info.display.transformation = Transformation(
                            Vector3f(),
                            quat,
                            Vector3f(1f, 1f, 1f),
                            Quaternionf()
                        )
                    }
                    tick++
                } catch (e: Exception) {
                    finishFall(displaysAndDrops, hinge, direction, isCreative)
                    this.cancel()
                }
            }
        }.runTaskTimer(this, 1L, 1L)

        server.scheduler.runTaskLater(this, Runnable {
            treeBlocks.forEach { silentBlocks.remove(BlockPos(it.world.uid, it.x, it.y, it.z)) }
            vines.forEach { silentBlocks.remove(BlockPos(it.world.uid, it.x, it.y, it.z)) }
        }, 10L)
    }

    private fun finishFall(
        infos: List<BlockDataInfo>,
        hinge: Location,
        direction: Vector,
        isCreative: Boolean
    ) {
        val world = hinge.world

        world.playSound(hinge, Sound.BLOCK_WOOD_BREAK, 1.2f, 0.6f)
        world.playSound(hinge, Sound.BLOCK_GRASS_BREAK, 1.2f, 0.8f)

        for (info in infos) {
            val loc = info.display.location
            info.display.remove()

            if (!isCreative) {
                for (drop in info.drops) {
                    world.dropItem(loc, drop) { item ->
                        item.velocity = direction.clone().multiply(0.4).setY(0.25)
                    }
                }
            }
        }
    }

    private fun detectTree(startBlock: Block): TreeScanResult? {
        val trunks = mutableSetOf<Block>()
        val trunkQueue = ArrayDeque<Block>()
        trunkQueue.add(startBlock)
        trunks.add(startBlock)

        var baseBlock = startBlock

        while (trunkQueue.isNotEmpty()) {
            val current = trunkQueue.removeFirst()

            for (x in -1..1) {
                for (y in -1..1) {
                    for (z in -1..1) {
                        if (x == 0 && y == 0 && z == 0) continue
                        val adj = current.getRelative(x, y, z)

                        if (!isNaturalEnvironment(adj.type)) return null

                        if (adj !in trunks && isTrunk(adj.type)) {
                            val hDist = max(abs(adj.x - startBlock.x), abs(adj.z - startBlock.z))
                            if (hDist <= 12 && (adj.y - startBlock.y) in -12..50) {
                                trunks.add(adj)
                                trunkQueue.add(adj)
                                if (adj.y < baseBlock.y) {
                                    baseBlock = adj
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

        while (foliageQueue.isNotEmpty() && treeBlocks.size < 2500) {
            val (current, dist) = foliageQueue.removeFirst()

            if (dist >= 6) continue

            for (x in -1..1) {
                for (y in -1..1) {
                    for (z in -1..1) {
                        if (x == 0 && y == 0 && z == 0) continue
                        val adj = current.getRelative(x, y, z)

                        if (!isNaturalEnvironment(adj.type)) return null

                        if (adj !in trunks && adj !in foliage && adj !in vines) {
                            val type = adj.type

                            // Handle vines separating from falling animation
                            if (type == Material.VINE) {
                                vines.add(adj)
                                foliageQueue.add(Pair(adj, dist + 1))
                            } else if (isFoliage(type)) {
                                val blockData = adj.blockData
                                var belongsToTree = true

                                if (blockData is Leaves) {
                                    if (!blockData.isPersistent && blockData.distance < dist + 1) {
                                        belongsToTree = false
                                    }
                                }

                                if (belongsToTree) {
                                    foliage.add(adj)
                                    foliageQueue.add(Pair(adj, dist + 1))
                                    treeBlocks.add(adj)
                                    foliageCount++
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

    private fun isTrunk(type: Material): Boolean {
        return Tag.LOGS.isTagged(type) ||
                type == Material.MANGROVE_ROOTS ||
                type == Material.MUDDY_MANGROVE_ROOTS ||
                type == Material.MUSHROOM_STEM
    }

    private fun isFoliage(type: Material): Boolean {
        return Tag.LEAVES.isTagged(type) ||
                type == Material.NETHER_WART_BLOCK ||
                type == Material.WARPED_WART_BLOCK ||
                type == Material.SHROOMLIGHT ||
                type == Material.BROWN_MUSHROOM_BLOCK ||
                type == Material.RED_MUSHROOM_BLOCK ||
                type == Material.MANGROVE_PROPAGULE || // Includes Propagule to be processed as falling foliage
                type == Material.MOSS_CARPET ||        // Includes Moss Carpet to be processed as falling foliage
                type == Material.COCOA                 // Cocoa beans perfectly drop from jungle trees now
    }

    private fun isNaturalEnvironment(type: Material): Boolean {
        if (type.isAir) return true
        if (isTrunk(type) || isFoliage(type)) return true
        if (Tag.DIRT.isTagged(type) || Tag.SAND.isTagged(type)) return true
        if (Tag.FLOWERS.isTagged(type) || Tag.BASE_STONE_OVERWORLD.isTagged(type)) return true
        if (Tag.ICE.isTagged(type) || Tag.SNOW.isTagged(type)) return true

        return when (type) {
            Material.MOSS_BLOCK, Material.MOSS_CARPET, Material.PINK_PETALS,

            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
            Material.DEAD_BUSH, Material.VINE, Material.GLOW_LICHEN, Material.HANGING_ROOTS,

            Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM,
            Material.MUD, Material.MUDDY_MANGROVE_ROOTS, Material.MANGROVE_ROOTS,
            Material.MANGROVE_PROPAGULE, Material.COCOA, Material.BAMBOO,

            Material.SNOW, Material.SNOW_BLOCK, Material.POWDER_SNOW,
            Material.WATER, Material.LAVA, Material.SEAGRASS, Material.KELP, Material.KELP_PLANT,

            Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS,
            Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.NETHER_SPROUTS, Material.WEEPING_VINES,
            Material.WEEPING_VINES_PLANT, Material.TWISTING_VINES, Material.TWISTING_VINES_PLANT,

            Material.NETHERRACK, Material.SOUL_SAND, Material.SOUL_SOIL, Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM,
            Material.MAGMA_BLOCK, Material.BONE_BLOCK -> true

            else -> false
        }
    }

    private data class BlockDataInfo(
        val offset: Vector3f,
        val display: BlockDisplay,
        val drops: Collection<ItemStack>,
        val isTrunk: Boolean
    )

    private data class TreeScanResult(
        val blocks: List<Block>,
        val baseBlock: Block,
        val vines: Set<Block>
    )
}