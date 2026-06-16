package project.kompass.fancyTimber.util

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.BlockData

object TimberUtils {

    val leafLitterMaterial: Material? = try {
        Material.valueOf("LEAF_LITTER")
    } catch (e: Exception) {
        null
    }

    val wildflowersMaterial: Material? = try {
        Material.valueOf("WILDFLOWERS")
    } catch (e: Exception) {
        null
    }

    val tintedLeavesParticle: Particle? = try {
        Particle.valueOf("TINTED_LEAVES")
    } catch (e: IllegalArgumentException) {
        null
    }

    val paleOakLeavesParticle: Particle? = try {
        Particle.valueOf("PALE_OAK_LEAVES")
    } catch (e: IllegalArgumentException) {
        null
    }

    fun getTreeFamily(type: Material): String {
        val name = type.name
        return when {
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
            name.contains("MUSHROOM") -> "MUSHROOM"
            else -> "SHARED"
        }
    }

    fun isVine(type: Material): Boolean {
        return type == Material.VINE ||
                type.name.endsWith("VINES") ||
                type.name.endsWith("VINES_PLANT") ||
                type.name.contains("HANGING_MOSS")
    }

    fun isTrunk(type: Material): Boolean {
        val name = type.name
        return Tag.LOGS.isTagged(type) ||
                name.contains("LOG") ||
                name.contains("WOOD") ||
                name.contains("STEM") ||
                type == Material.MANGROVE_ROOTS ||
                type == Material.MUDDY_MANGROVE_ROOTS ||
                type == Material.MUSHROOM_STEM
    }

    fun isFoliage(type: Material): Boolean {
        val name = type.name
        return Tag.LEAVES.isTagged(type) ||
                name.contains("LEAVES") ||
                type == Material.NETHER_WART_BLOCK ||
                type == Material.WARPED_WART_BLOCK ||
                type == Material.SHROOMLIGHT ||
                type == Material.BROWN_MUSHROOM_BLOCK ||
                type == Material.RED_MUSHROOM_BLOCK ||
                type == Material.MANGROVE_PROPAGULE ||
                type == Material.COCOA ||
                type == Material.SNOW ||
                type == Material.MOSS_CARPET ||
                name.contains("PALE_MOSS_CARPET") ||
                isVine(type)
    }

    /**
     * Identifies GROUND foliage and ground cover.
     */
    fun isExcludedFromScan(type: Material): Boolean {
        // FIX: Ensure leaf blocks are never categorized as ground vegetation,
        // even if Minecraft classes them under #flowers (e.g., Cherry and Flowering Azalea leaves)
        if (type.name.contains("LEAVES")) return false

        if (leafLitterMaterial != null && type == leafLitterMaterial) return true
        if (wildflowersMaterial != null && type == wildflowersMaterial) return true

        if (type == Material.TORCH || type == Material.SOUL_TORCH || type == Material.REDSTONE_TORCH) return true

        if (Tag.FLOWERS.isTagged(type)) return true
        if (Tag.SAPLINGS.isTagged(type)) return true

        val name = type.name
        if (name == "PITCHER_PLANT" || name == "SUNFLOWER" || name == "PEONY" || name == "LILAC" || name == "ROSE_BUSH") return true

        return type == Material.SHORT_GRASS ||
                type == Material.TALL_GRASS ||
                type == Material.FERN ||
                type == Material.LARGE_FERN ||
                type == Material.PINK_PETALS ||
                type == Material.DEAD_BUSH ||
                type == Material.SWEET_BERRY_BUSH ||
                type == Material.GLOW_LICHEN ||
                type == Material.HANGING_ROOTS
    }

    fun isNaturalEnvironment(type: Material): Boolean {
        if (type.isAir) return true
        if (isTrunk(type) || isFoliage(type) || isVine(type)) return true

        if (Tag.DIRT.isTagged(type)) return true
        if (Tag.SAND.isTagged(type)) return true
        if (Tag.BASE_STONE_OVERWORLD.isTagged(type)) return true
        if (Tag.BASE_STONE_NETHER.isTagged(type)) return true
        if (Tag.ICE.isTagged(type)) return true
        if (Tag.SNOW.isTagged(type)) return true
        if (Tag.FLOWERS.isTagged(type)) return true
        if (Tag.SAPLINGS.isTagged(type)) return true

        val name = type.name
        if (name.contains("MOSS") || name.contains("ORE") || name.contains("STONE") ||
            name.contains("DIRT") || name.contains("SAND") || name.contains("SNOW") ||
            name.contains("ICE") || name.contains("SLATE") || name.contains("TUFF") ||
            name.contains("TERRACOTTA") || name.contains("CLAY") || name.contains("PATH") ||
            name.contains("GRAVEL") || name.contains("NYLIUM") || name.contains("CALCITE") ||
            name.contains("FARMLAND") || name.contains("BASALT") || name.contains("OBSIDIAN") ||
            name.contains("PRISMARINE") || name.contains("QUARTZ") || name.contains("AMETHYST")) return true

        return when (type) {
            Material.PINK_PETALS, Material.SHORT_GRASS, Material.TALL_GRASS,
            Material.FERN, Material.LARGE_FERN, Material.DEAD_BUSH,
            Material.GLOW_LICHEN, Material.HANGING_ROOTS, Material.SPORE_BLOSSOM,
            Material.AZALEA, Material.FLOWERING_AZALEA, Material.BEE_NEST,
            Material.MUD, Material.COCOA, Material.BAMBOO,
            Material.GRAVEL, Material.CLAY,
            Material.WATER, Material.LAVA, Material.SEAGRASS, Material.KELP, Material.KELP_PLANT,
            Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS,
            Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.NETHER_SPROUTS,
            Material.MAGMA_BLOCK, Material.BONE_BLOCK, Material.COBWEB -> true
            else -> false
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

    private fun getFoliageColor(family: String, material: Material): Color {
        return when (family) {
            "OAK" -> {
                if (material.name.contains("AZALEA")) Color.fromRGB(111, 149, 53)
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