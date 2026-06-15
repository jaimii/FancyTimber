package project.kompass.fancyTimber.util

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.BlockData

object TimberUtils {

    // Dynamically resolved particles to prevent compatibility crashes on older 1.21 builds
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
            else -> "SHARED" // Vine, Shroomlight, Moss Carpet, etc.
        }
    }

    fun isVine(type: Material): Boolean {
        return type == Material.VINE || type.name.endsWith("VINES") || type.name.endsWith("VINES_PLANT")
    }

    fun isTrunk(type: Material): Boolean {
        return Tag.LOGS.isTagged(type) ||
                type == Material.MANGROVE_ROOTS ||
                type == Material.MUDDY_MANGROVE_ROOTS ||
                type == Material.MUSHROOM_STEM
    }

    fun isFoliage(type: Material): Boolean {
        return Tag.LEAVES.isTagged(type) ||
                type == Material.NETHER_WART_BLOCK ||
                type == Material.WARPED_WART_BLOCK ||
                type == Material.SHROOMLIGHT ||
                type == Material.BROWN_MUSHROOM_BLOCK ||
                type == Material.RED_MUSHROOM_BLOCK ||
                type == Material.MANGROVE_PROPAGULE ||
                type == Material.MOSS_CARPET ||
                type == Material.COCOA
    }

    fun isNaturalEnvironment(type: Material): Boolean {
        if (type.isAir) return true
        if (isTrunk(type) || isFoliage(type) || isVine(type)) return true
        if (Tag.DIRT.isTagged(type) || Tag.SAND.isTagged(type)) return true
        if (Tag.FLOWERS.isTagged(type) || Tag.BASE_STONE_OVERWORLD.isTagged(type)) return true
        if (Tag.ICE.isTagged(type) || Tag.SNOW.isTagged(type)) return true

        return when (type) {
            Material.MOSS_BLOCK, Material.MOSS_CARPET, Material.PINK_PETALS,
            Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN,
            Material.DEAD_BUSH, Material.GLOW_LICHEN, Material.HANGING_ROOTS,
            Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM,
            Material.MUD, Material.MUDDY_MANGROVE_ROOTS, Material.MANGROVE_ROOTS,
            Material.MANGROVE_PROPAGULE, Material.COCOA, Material.BAMBOO,
            Material.SNOW, Material.SNOW_BLOCK, Material.POWDER_SNOW,
            Material.WATER, Material.LAVA, Material.SEAGRASS, Material.KELP, Material.KELP_PLANT,
            Material.BROWN_MUSHROOM, Material.RED_MUSHROOM, Material.CRIMSON_FUNGUS, Material.WARPED_FUNGUS,
            Material.CRIMSON_ROOTS, Material.WARPED_ROOTS, Material.NETHER_SPROUTS,
            Material.NETHERRACK, Material.SOUL_SAND, Material.SOUL_SOIL, Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM,
            Material.MAGMA_BLOCK, Material.BONE_BLOCK -> true
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

        // Fallback: Use raw block breaking particles
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