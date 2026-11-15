package project.kompass.fancyTimber.model

import org.bukkit.configuration.file.FileConfiguration

class AnimationConfig(config: FileConfiguration) {

    val enabled: Boolean = config.getBoolean("animation.enabled", true)

    val fellMode: FellMode = try {
        FellMode.valueOf(config.getString("animation.fell-mode", "INSTANT")!!.uppercase())
    } catch (e: IllegalArgumentException) {
        FellMode.INSTANT
    }

    val directionMode: DirectionMode = try {
        DirectionMode.valueOf(config.getString("animation.direction-mode", "LEAN")!!.uppercase())
    } catch (e: IllegalArgumentException) {
        DirectionMode.LEAN
    }

    val maxAnimatedLogs: Int = config.getInt("animation.max-animated-logs", 256)
    val maxAnimatedLeaves: Int = config.getInt("animation.max-animated-leaves", 500)
    val fallSpeed: Double = config.getDouble("animation.fall-speed", 1.0).coerceAtLeast(0.15)
    val smoothness: Int = config.getInt("animation.smoothness", 2).coerceIn(1, 5)
    val gravityCurve: Double = config.getDouble("animation.gravity-curve", 1.35).coerceAtLeast(1.0)
    val maxTiltDegrees: Double = config.getDouble("animation.max-tilt-degrees", 92.0).coerceIn(75.0, 115.0)

    val windSway: Boolean = config.getBoolean("animation.wind-sway", true)
    val windSwayStrength: Double = config.getDouble("animation.wind-sway-strength", 0.065).coerceAtLeast(0.0)
    val leafDragTicks: Int = config.getInt("animation.leaf-drag-ticks", 4).coerceAtLeast(0)

    val leafParticles: Boolean = config.getBoolean("animation.leaf-particles", true)
    val trunkDustTrails: Boolean = config.getBoolean("animation.trunk-dust-trails", true)
    val particleDensity: Double = config.getDouble("animation.particle-density", 0.45).coerceIn(0.0, 1.0)
    val trunkCrackSounds: Boolean = config.getBoolean("animation.trunk-crack-sounds", true)
    val impactShockwave: Boolean = config.getBoolean("animation.impact-shockwave", true)

    val showParticles: Boolean = config.getBoolean("effects.show-particles", true)
    val playSound: Boolean = config.getBoolean("effects.play-sound", true)
    val showActionBar: Boolean = config.getBoolean("feedback.show-actionbar", true)

    val laydown: LaydownConfig = LaydownConfig(config)

    class LaydownConfig(config: FileConfiguration) {
        val fallSpeed: Double = config.getDouble("animation.laydown.fall-speed", 1.0).coerceAtLeast(0.15)
        val slideDistance: Double = config.getDouble("animation.laydown.slide-distance", 1.5).coerceAtLeast(0.0)
        val breakIntoGround: Boolean = config.getBoolean("animation.laydown.break-into-ground", true)
        val requireClearPath: Boolean = config.getBoolean("animation.laydown.require-clear-path", true)
    }

    enum class FellMode {
        INSTANT,
        LAYDOWN
    }

    enum class DirectionMode {
        PLAYER,
        LEAN,
        NATURAL,
        RANDOM
    }
}