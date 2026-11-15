package project.kompass.fancyTimber.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import project.kompass.fancyTimber.model.AnimationConfig
import java.util.Locale

object AnimationFeedback {

    fun onTreeNotNatural(player: Player, config: AnimationConfig) {
        if (config.showActionBar) {
            player.sendActionBar(Component.text("Not a natural tree", NamedTextColor.RED))
        }
    }

    fun onProtected(player: Player, config: AnimationConfig) {
        if (config.showActionBar) {
            player.sendActionBar(Component.text("Cannot fell tree here (protected area)", NamedTextColor.RED))
        }
    }

    fun onStartChopping(player: Player, config: AnimationConfig) {
        if (config.showActionBar) {
            player.sendActionBar(Component.text("Chopping tree...", NamedTextColor.GOLD))
        }
    }

    fun onProgress(player: Player, percent: Int, config: AnimationConfig) {
        if (config.showActionBar) {
            val clamped = percent.coerceIn(0, 100)
            val bar = buildProgressBar(clamped)
            player.sendActionBar(
                Component.text("Felling tree: ", NamedTextColor.GREEN)
                    .append(Component.text("$bar ", NamedTextColor.GREEN))
                    .append(Component.text("$clamped%", NamedTextColor.YELLOW))
            )
        }
    }

    fun onComplete(player: Player, config: AnimationConfig) {
        if (config.showActionBar) {
            player.sendActionBar(Component.text("Tree felled completely!", NamedTextColor.GREEN))
        }
    }

    fun onTooLarge(player: Player, maxLogs: Int, config: AnimationConfig) {
        if (config.showActionBar) {
            player.sendActionBar(Component.text("Tree too large (max $maxLogs logs)", NamedTextColor.RED))
        }
    }

    fun onCooldown(player: Player, remainingMs: Long, config: AnimationConfig) {
        if (config.showActionBar) {
            val seconds = String.format(Locale.US, "%.1f", remainingMs / 1000.0)
            player.sendActionBar(
                Component.text("Tool on cooldown: ", NamedTextColor.RED)
                    .append(Component.text("${seconds}s", NamedTextColor.YELLOW))
            )
        }
    }

    private fun buildProgressBar(percent: Int): String {
        val filled = (percent / 10).coerceIn(0, 10)
        val empty = 10 - filled
        return "█".repeat(filled) + "░".repeat(empty)
    }
}