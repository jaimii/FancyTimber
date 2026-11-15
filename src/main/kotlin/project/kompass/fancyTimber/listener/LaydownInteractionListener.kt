package project.kompass.fancyTimber.listener

import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import project.kompass.fancyTimber.FancyTimber

class LaydownInteractionListener(private val plugin: FancyTimber) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlockDisplayHit(event: EntityDamageByEntityEvent) {
        val bd = event.entity as? BlockDisplay ?: return
        val player = event.damager as? Player ?: return
        if (plugin.restingLaydownBlocks.containsKey(bd.uniqueId)) {
            event.isCancelled = true
            harvestRestingDisplay(player, bd)
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    fun onBlockDisplayInteract(event: PlayerInteractAtEntityEvent) {
        val bd = event.rightClicked as? BlockDisplay ?: return
        if (plugin.restingLaydownBlocks.containsKey(bd.uniqueId)) {
            event.isCancelled = true
            harvestRestingDisplay(event.player, bd)
        }
    }

    private fun harvestRestingDisplay(player: Player, bd: BlockDisplay) {
        if (!bd.isValid) return
        // Atomically remove the entry so double-clicks cannot duplicate drops
        val laydownData = plugin.restingLaydownBlocks.remove(bd.uniqueId) ?: return

        val dropLoc = bd.location.clone().add(0.5, 0.5, 0.5)
        val world = dropLoc.world ?: return
        val blockData = bd.block
        val isTrunk = laydownData.isTrunk

        if (player.gameMode != GameMode.CREATIVE) {
            for (item in laydownData.drops) {
                if (item.type != Material.AIR) {
                    world.dropItemNaturally(dropLoc, item)
                }
            }
        }

        world.spawnParticle(Particle.BLOCK, dropLoc, if (isTrunk) 7 else 4, 0.18, 0.13, 0.18, 0.05, blockData)
        world.playSound(dropLoc, if (isTrunk) Sound.BLOCK_WOOD_BREAK else Sound.BLOCK_GRASS_BREAK, 0.7f, if (isTrunk) 0.85f else 1.0f)

        plugin.activeDisplays.remove(bd)
        bd.remove()
    }
}