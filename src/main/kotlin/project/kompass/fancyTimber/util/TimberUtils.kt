package project.kompass.fancyTimber.util

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import project.kompass.fancyTimber.model.AnimationConfig
import project.kompass.fancyTimber.model.AnimationConfig.DirectionMode
import java.util.EnumMap
import java.util.EnumSet
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object TimberUtils {

    private val tintedLeavesParticle: Particle? = try { Particle.valueOf("TINTED_LEAVES") } catch (e: Exception) { null }
    private val paleOakLeavesParticle: Particle? = try { Particle.valueOf("PALE_OAK_LEAVES") } catch (e: Exception) { null }
    private val crimsonSporeParticle: Particle? = try { Particle.valueOf("CRIMSON_SPORE") } catch (e: Exception) { null }
    private val warpedSporeParticle: Particle? = try { Particle.valueOf("WARPED_SPORE") } catch (e: Exception) { null }
    private val fallingNectarParticle: Particle? = try { Particle.valueOf("FALLING_NECTAR") } catch (e: Exception) { null }

    // O(1) Bitwise EnumSet & EnumMap caches initialized once at startup
    private val TRUNK_MATERIALS: EnumSet<Material> = EnumSet.noneOf(Material::class.java)
    private val FOLIAGE_MATERIALS: EnumSet<Material> = EnumSet.noneOf(Material::class.java)
    private val TOP_ATTACHMENT_MATERIALS: EnumSet<Material> = EnumSet.noneOf(Material::class.java)
    private val VINE_MATERIALS: EnumSet<Material> = EnumSet.noneOf(Material::class.java)
    private val EXCLUDED_MATERIALS: EnumSet<Material> = EnumSet.noneOf(Material::class.java)
    private val BUILDING_MATERIALS: EnumSet<Material> = EnumSet.noneOf(Material::class.java)
    private val NATURAL_MATERIALS: EnumSet<Material> = EnumSet.noneOf(Material::class.java)
    private val AXE_MATERIALS: EnumSet<Material> = EnumSet.noneOf(Material::class.java)
    private val TREE_FAMILY_MAP: EnumMap<Material, String> = EnumMap(Material::class.java)

    init {
        for (type in Material.entries) {
            val name = type.name

            // 1. Axes (includes items)
            if (Tag.ITEMS_AXES.isTagged(type) || name.endsWith("_AXE")) {
                AXE_MATERIALS.add(type)
            }

            if (!type.isBlock) continue

            // 2. Tree Family
            val family = when {
                type == Material.BAMBOO -> "BAMBOO"
                name.contains("POPLAR") -> "POPLAR"
                name.contains("DARK_OAK") -> "DARK_OAK"
                name.contains("PALE_OAK") -> "PALE_OAK"
                name.contains("OAK") || name.contains("AZALEA") -> "OAK"
                name.contains("SPRUCE") -> "SPRUCE"
                name.contains("BIRCH") -> "BIRCH"
                name.contains("JUNGLE") || type == Material.COCOA -> "JUNGLE"
                name.contains("ACACIA") -> "ACACIA"
                name.contains("MANGROVE") -> "MANGROVE"
                name.contains("CHERRY") -> "CHERRY"
                name.contains("CRIMSON") || type == Material.NETHER_WART_BLOCK -> "CRIMSON"
                name.contains("WARPED") -> "WARPED"
                name.contains("MUSHROOM") && !name.contains("SHELF_MUSHROOM") -> "MUSHROOM"
                else -> "SHARED"
            }
            TREE_FAMILY_MAP[type] = family

            // 3. Vines
            val isVineType = type == Material.VINE ||
                    name.endsWith("VINES") ||
                    name.endsWith("VINES_PLANT") ||
                    name.contains("HANGING_MOSS")
            if (isVineType) {
                VINE_MATERIALS.add(type)
            }

            // 4. Trunks
            val isTrunkType = Tag.LOGS.isTagged(type) ||
                    type == Material.BAMBOO ||
                    name.endsWith("_LOG") ||
                    name.endsWith("_WOOD") ||
                    name.endsWith("_STEM") ||
                    name.endsWith("_HYPHAE") ||
                    type == Material.MANGROVE_ROOTS ||
                    type == Material.MUDDY_MANGROVE_ROOTS ||
                    type == Material.MUSHROOM_STEM
            if (isTrunkType && !name.contains("PETRIFIED")) {
                TRUNK_MATERIALS.add(type)
            }

            // 5. Canopy Foliage & Hanging Attachments (Does NOT include ground SNOW / MOSS_CARPET)
            val isFoliageType = Tag.LEAVES.isTagged(type) ||
                    name.contains("LEAVES") ||
                    name.contains("SHELF_MUSHROOM") ||
                    type == Material.NETHER_WART_BLOCK ||
                    type == Material.WARPED_WART_BLOCK ||
                    type == Material.SHROOMLIGHT ||
                    type == Material.BROWN_MUSHROOM_BLOCK ||
                    type == Material.RED_MUSHROOM_BLOCK ||
                    type == Material.MANGROVE_PROPAGULE ||
                    type == Material.COCOA ||
                    isVineType
            if (isFoliageType) {
                FOLIAGE_MATERIALS.add(type)
            }

            // 6. Top-only Attachments (Collected only when resting directly on top of scanned tree blocks)
            if (type == Material.SNOW || type == Material.MOSS_CARPET || name == "PALE_MOSS_CARPET") {
                TOP_ATTACHMENT_MATERIALS.add(type)
            }

            // 7. Ground Exclusions (Never exclude leaves even if tagged under #flowers)
            val isExcludedType = !name.contains("LEAVES") && (
                    name == "LEAF_LITTER" ||
                            name == "WILDFLOWERS" ||
                            name == "RED_SHRUB" ||
                            name == "BUSH" ||
                            name == "FIREFLY_BUSH" ||
                            name == "SHORT_DRY_GRASS" ||
                            name == "TALL_DRY_GRASS" ||
                            name == "CACTUS_FLOWER" ||
                            name == "GOLDEN_DANDELION" ||
                            name.contains("EYEBLOSSOM") ||
                            type == Material.TORCH ||
                            type == Material.SOUL_TORCH ||
                            type == Material.REDSTONE_TORCH ||
                            name == "COPPER_TORCH" ||
                            Tag.FLOWERS.isTagged(type) ||
                            Tag.SAPLINGS.isTagged(type) ||
                            name == "PITCHER_PLANT" ||
                            name == "SUNFLOWER" ||
                            name == "PEONY" ||
                            name == "LILAC" ||
                            name == "ROSE_BUSH" ||
                            type == Material.SHORT_GRASS ||
                            type == Material.TALL_GRASS ||
                            type == Material.FERN ||
                            type == Material.LARGE_FERN ||
                            type == Material.PINK_PETALS ||
                            type == Material.DEAD_BUSH ||
                            type == Material.SWEET_BERRY_BUSH ||
                            type == Material.GLOW_LICHEN ||
                            type == Material.HANGING_ROOTS
                    )
            if (isExcludedType) {
                EXCLUDED_MATERIALS.add(type)
            }

            // 8. Explicit Man-Made Structure Blacklist (Protects houses/cabins without blocking natural trees)
            val isExcludedFromBuilding = TRUNK_MATERIALS.contains(type) ||
                    FOLIAGE_MATERIALS.contains(type) ||
                    TOP_ATTACHMENT_MATERIALS.contains(type) ||
                    EXCLUDED_MATERIALS.contains(type) ||
                    type.isAir

            if (!isExcludedFromBuilding) {
                val isStructure = Tag.PLANKS.isTagged(type) ||
                        Tag.DOORS.isTagged(type) ||
                        Tag.TRAPDOORS.isTagged(type) ||
                        Tag.FENCES.isTagged(type) ||
                        Tag.FENCE_GATES.isTagged(type) ||
                        Tag.STAIRS.isTagged(type) ||
                        Tag.SLABS.isTagged(type) ||
                        Tag.WALLS.isTagged(type) ||
                        Tag.BEDS.isTagged(type) ||
                        Tag.WOOL.isTagged(type) ||
                        Tag.WOOL_CARPETS.isTagged(type) ||
                        Tag.SHULKER_BOXES.isTagged(type) ||
                        Tag.SIGNS.isTagged(type) ||
                        Tag.BANNERS.isTagged(type) ||
                        Tag.RAILS.isTagged(type) ||
                        Tag.ANVIL.isTagged(type) ||
                        Tag.CAULDRONS.isTagged(type) ||
                        name.contains("PLANKS") ||
                        name.contains("GLASS") ||
                        name.contains("BRICK") ||
                        name.contains("CONCRETE") ||
                        name.contains("GLAZED_TERRACOTTA") ||
                        name.contains("POLISHED_") ||
                        name.contains("CHISELED_") ||
                        name.contains("CUT_") ||
                        name.contains("WALL_TORCH") ||
                        name.contains("HANGING_SIGN") ||
                        (name.endsWith("LANTERN") && name != "JACK_O_LANTERN") ||
                        name.contains("CHEST") ||
                        name.contains("FURNACE") ||
                        name.contains("BOOKSHELF") ||
                        name == "COBBLESTONE" ||
                        name == "COBBLED_DEEPSLATE" ||
                        name == "SMOOTH_STONE" ||
                        name == "BARREL" ||
                        name == "SMOKER" ||
                        name == "CRAFTING_TABLE" ||
                        name == "LECTERN" ||
                        name == "LOOM" ||
                        name == "STONECUTTER" ||
                        name == "CARTOGRAPHY_TABLE" ||
                        name == "FLETCHING_TABLE" ||
                        name == "SMITHING_TABLE" ||
                        name == "GRINDSTONE" ||
                        name == "ENCHANTING_TABLE" ||
                        name == "BREWING_STAND" ||
                        name == "JUKEBOX" ||
                        name == "NOTE_BLOCK" ||
                        name == "LADDER" ||
                        name == "SCAFFOLDING" ||
                        name == "IRON_BARS" ||
                        name == "BEACON" ||
                        name == "CONDUIT" ||
                        name.contains("PISTON") ||
                        name == "OBSERVER" ||
                        name == "REPEATER" ||
                        name == "COMPARATOR" ||
                        name == "HOPPER" ||
                        name == "DISPENSER" ||
                        name == "DROPPER"
                if (isStructure) {
                    BUILDING_MATERIALS.add(type)
                }
            }

            // 9. Natural Environment
            val isNaturalType = !BUILDING_MATERIALS.contains(type) && (
                    type.isAir ||
                            TRUNK_MATERIALS.contains(type) ||
                            FOLIAGE_MATERIALS.contains(type) ||
                            TOP_ATTACHMENT_MATERIALS.contains(type) ||
                            VINE_MATERIALS.contains(type) ||
                            EXCLUDED_MATERIALS.contains(type) ||
                            Tag.DIRT.isTagged(type) ||
                            Tag.SAND.isTagged(type) ||
                            Tag.BASE_STONE_OVERWORLD.isTagged(type) ||
                            Tag.BASE_STONE_NETHER.isTagged(type) ||
                            Tag.ICE.isTagged(type) ||
                            Tag.SNOW.isTagged(type) ||
                            Tag.FLOWERS.isTagged(type) ||
                            Tag.SAPLINGS.isTagged(type) ||
                            name.contains("MOSS") || name.contains("ORE") || name.contains("STONE") ||
                            name.contains("DIRT") || name.contains("SAND") || name.contains("SNOW") ||
                            name.contains("ICE") || name.contains("SLATE") || name.contains("TUFF") ||
                            name.contains("TERRACOTTA") || name.contains("CLAY") || name.contains("PATH") ||
                            name.contains("GRAVEL") || name.contains("NYLIUM") || name.contains("CALCITE") ||
                            name.contains("FARMLAND") || name.contains("BASALT") || name.contains("OBSIDIAN") ||
                            name.contains("PRISMARINE") || name.contains("QUARTZ") || name.contains("AMETHYST") ||
                            name.contains("BUSH") || name.contains("DRY_GRASS") || name.contains("SULFUR") ||
                            name.contains("CINNABAR") ||
                            when (type) {
                                Material.SPORE_BLOSSOM, Material.AZALEA, Material.FLOWERING_AZALEA,
                                Material.BEE_NEST, Material.MUD, Material.COCOA, Material.BAMBOO,
                                Material.GRAVEL, Material.CLAY, Material.WATER, Material.LAVA,
                                Material.SEAGRASS, Material.KELP, Material.KELP_PLANT,
                                Material.BROWN_MUSHROOM, Material.RED_MUSHROOM,
                                Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS,
                                Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.NETHER_SPROUTS,
                                Material.MAGMA_BLOCK, Material.BONE_BLOCK, Material.COBWEB -> true
                                else -> false
                            }
                    )
            if (isNaturalType) {
                NATURAL_MATERIALS.add(type)
            }
        }
    }

    fun warmup() {
        // Triggers object initialization on plugin startup
    }

    fun isAxe(type: Material): Boolean = AXE_MATERIALS.contains(type)
    fun isTrunk(type: Material): Boolean = TRUNK_MATERIALS.contains(type)
    fun isFoliage(type: Material): Boolean = FOLIAGE_MATERIALS.contains(type)
    fun isTopAttachment(type: Material): Boolean = TOP_ATTACHMENT_MATERIALS.contains(type)
    fun isVine(type: Material): Boolean = VINE_MATERIALS.contains(type)
    fun isExcludedFromScan(type: Material): Boolean = EXCLUDED_MATERIALS.contains(type)
    fun isBuildingBlock(type: Material): Boolean = BUILDING_MATERIALS.contains(type)
    fun isNaturalEnvironment(type: Material): Boolean = NATURAL_MATERIALS.contains(type)
    fun getTreeFamily(type: Material): String = TREE_FAMILY_MAP[type] ?: "SHARED"

    fun getWoodBreakSound(family: String): Sound {
        return when (family) {
            "POPLAR" -> Registry.SOUNDS.get(NamespacedKey.minecraft("block.poplar_wood.break"))
                ?: Sound.BLOCK_WOOD_BREAK
            "CHERRY" -> Registry.SOUNDS.get(NamespacedKey.minecraft("block.cherry_wood.break"))
                ?: Sound.BLOCK_WOOD_BREAK
            "BAMBOO" -> Registry.SOUNDS.get(NamespacedKey.minecraft("block.bamboo_wood.break"))
                ?: Sound.BLOCK_WOOD_BREAK
            "CRIMSON", "WARPED" -> Registry.SOUNDS.get(NamespacedKey.minecraft("block.nether_wood.break"))
                ?: Sound.BLOCK_WOOD_BREAK
            else -> Sound.BLOCK_WOOD_BREAK
        }
    }

    fun resolveFallDirection(
        player: Player,
        baseBlock: Block,
        allBlocks: List<Block>,
        config: AnimationConfig
    ): Vector {
        return when (config.directionMode) {
            DirectionMode.LEAN, DirectionMode.NATURAL -> {
                val lean = calculateTreeLean(baseBlock, allBlocks)
                if (lean.lengthSquared() > 0.05) {
                    lean.normalize()
                } else {
                    getPlayerHorizontalDirection(player)
                }
            }
            DirectionMode.PLAYER -> getPlayerHorizontalDirection(player)
            DirectionMode.RANDOM -> {
                val angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0)
                Vector(cos(angle), 0.0, sin(angle)).normalize()
            }
        }
    }

    private fun getPlayerHorizontalDirection(player: Player): Vector {
        val dir = player.location.direction.setY(0.0)
        return if (dir.lengthSquared() < 1e-5) Vector(1.0, 0.0, 0.0) else dir.normalize()
    }

    private fun calculateTreeLean(baseBlock: Block, allBlocks: List<Block>): Vector {
        var weightedX = 0.0
        var weightedZ = 0.0
        var totalWeight = 0.0
        val baseCenterX = baseBlock.x + 0.5
        val baseCenterZ = baseBlock.z + 0.5

        for (block in allBlocks) {
            val heightWeight = max(0.75, (block.y - baseBlock.y).toDouble() + 1.0)
            val leafWeight = if (isFoliage(block.type)) 1.35 else 1.0
            val weight = heightWeight * leafWeight
            weightedX += (block.x + 0.5 - baseCenterX) * weight
            weightedZ += (block.z + 0.5 - baseCenterZ) * weight
            totalWeight += weight
        }

        return if (totalWeight <= 0.0) {
            Vector(0.0, 0.0, 0.0)
        } else {
            Vector(weightedX / totalWeight, 0.0, weightedZ / totalWeight)
        }
    }

    fun calculateFallDuration(tallestHeight: Int, blockCount: Int, speedFactor: Double): Int {
        val massBonus = sqrt(max(1, blockCount).toDouble()) * 0.45
        val duration = ((28.0 + tallestHeight * 1.45 + massBonus) / speedFactor.coerceAtLeast(0.15)).roundToInt()
        return duration.coerceIn(18, 90)
    }

    fun calculateUnbreakingDamage(uses: Int, tool: ItemStack): Int {
        if (uses <= 0) return 0
        val level = tool.getEnchantmentLevel(Enchantment.UNBREAKING)
        return if (level > 0) {
            ceil(uses.toDouble() / (level + 1).toDouble()).toInt()
        } else {
            uses
        }
    }

    fun spawnFoliageParticles(world: World, loc: Location, blockData: BlockData) {
        val material = blockData.material
        val family = getTreeFamily(material)

        if (family == "CHERRY") {
            world.spawnParticle(Particle.CHERRY_LEAVES, loc, 8, 0.4, 0.4, 0.4, 0.0)
            return
        }

        if (family == "PALE_OAK" && paleOakLeavesParticle != null) {
            world.spawnParticle(paleOakLeavesParticle, loc, 8, 0.4, 0.4, 0.4, 0.0)
            return
        }

        if (tintedLeavesParticle != null) {
            val color = getFoliageColor(family, material)
            world.spawnParticle(tintedLeavesParticle, loc, 8, 0.4, 0.4, 0.4, 0.0, color)
            return
        }

        world.spawnParticle(Particle.BLOCK, loc, 8, 0.4, 0.4, 0.4, 0.0, blockData)
    }

    fun spawnLeafTrailParticle(world: World, loc: Location, blockData: BlockData) {
        val material = blockData.material
        val family = getTreeFamily(material)

        when {
            family == "CHERRY" -> world.spawnParticle(Particle.CHERRY_LEAVES, loc, 1, 0.22, 0.22, 0.22, 0.0)
            family == "PALE_OAK" && paleOakLeavesParticle != null ->
                world.spawnParticle(paleOakLeavesParticle, loc, 1, 0.22, 0.22, 0.22, 0.0)
            material == Material.NETHER_WART_BLOCK && crimsonSporeParticle != null ->
                world.spawnParticle(crimsonSporeParticle, loc, 1, 0.22, 0.22, 0.22, 0.0)
            material == Material.WARPED_WART_BLOCK && warpedSporeParticle != null ->
                world.spawnParticle(warpedSporeParticle, loc, 1, 0.22, 0.22, 0.22, 0.0)
            tintedLeavesParticle != null -> {
                val color = getFoliageColor(family, material)
                world.spawnParticle(tintedLeavesParticle, loc, 1, 0.22, 0.22, 0.22, 0.0, color)
            }
            fallingNectarParticle != null ->
                world.spawnParticle(fallingNectarParticle, loc, 1, 0.22, 0.22, 0.22, 0.0)
            else ->
                world.spawnParticle(Particle.BLOCK, loc, 1, 0.22, 0.22, 0.22, 0.0, blockData)
        }
    }

    private fun getFoliageColor(family: String, material: Material): Color {
        val name = material.name
        return when (family) {
            "POPLAR" -> when {
                name.contains("RED") -> Color.fromRGB(205, 58, 45)
                name.contains("ORANGE") -> Color.fromRGB(228, 122, 38)
                else -> Color.fromRGB(238, 196, 48)
            }
            "OAK" -> {
                if (name.contains("AZALEA")) Color.fromRGB(111, 149, 53)
                else Color.fromRGB(60, 110, 40)
            }
            "SPRUCE" -> Color.fromRGB(97, 153, 114)
            "BIRCH" -> Color.fromRGB(128, 164, 114)
            "JUNGLE" -> Color.fromRGB(48, 114, 21)
            "ACACIA" -> Color.fromRGB(141, 178, 122)
            "DARK_OAK" -> Color.fromRGB(42, 68, 20)
            "PALE_OAK" -> Color.fromRGB(180, 195, 175)
            "CHERRY" -> Color.fromRGB(242, 182, 203)
            "MANGROVE" -> Color.fromRGB(74, 107, 44)
            "CRIMSON" -> Color.fromRGB(152, 16, 16)
            "WARPED" -> Color.fromRGB(21, 124, 124)
            "MUSHROOM" -> {
                if (material == Material.RED_MUSHROOM_BLOCK) Color.fromRGB(195, 42, 42)
                else Color.fromRGB(142, 105, 85)
            }
            else -> Color.fromRGB(60, 110, 40)
        }
    }
}