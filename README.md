# FancyTimber

## Introduction

**FancyTimber** is a treefeller plugin for Project: Kompass brings realistic, physics-inspired tree felling animations to Minecraft **26.3** (Paper, Purpur, and Folia). Instead of trees vanishing instantly or floating in mid-air, sneaking while chopping a tree with an axe causes the entire trunk, branches, and canopy to crack, sway, and topple over as a single solid structure—complete with dynamic audio, trailing leaves, terrain-aware impact effects, and built-in anti-grief protections. 

*Requires [PacketEvents](https://modrinth.com/plugin/packetevents) installed on your server.*

---

## Permissions Table

| Permission | Description | Default |
| :--- | :--- | :--- |
| `fancytimber.debug` | Allows access to `/ftdebug` to toggle diagnostic logs and reload the configuration. *(Also requires Server Operator status.)* | `op` |

---

## Commands

* **`/ftdebug`**  
  Toggles real-time in-game diagnostic chat logs when chopping trees, helpful for troubleshooting tree detection.  
  *Permission:* `fancytimber.debug` (OP only)

* **`/ftdebug reload`**  
  Reloads all animation, physics, cooldown, and balancing settings from the configuration without requiring a server restart.  
  *Permission:* `fancytimber.debug` (OP only)

---

## Features

### Controls & Compatibility
* **Sneak-to-Fell Toggle:** Sneak while breaking any log with an axe to fell the whole tree. Chopping while standing behaves like vanilla Minecraft so you can still edit or build wooden structures freely.
* **Broad Axe Support:** Compatible with all vanilla axes—including Copper Axes—as well as custom and modded axes.
* **Clean Cancellation:** Switching hotbar slots or stopping mid-chop immediately clears pending tree-chopping fatigue.

### Supported Trees & World Awareness
* **All Vanilla & 26.3 Trees:** Works on Oak, Azalea, Spruce, Birch, Jungle (including cocoa pods), Acacia, Dark Oak, Mangrove (including mangrove roots), Cherry, Crimson and Warped Nether fungi, Giant Mushrooms, Bamboo, and **26.3 Poplar trees** (including all three autumn leaf variants: red, orange, and yellow, plus attached shelf mushrooms).
* **Falling Canopy Attachments:** Vines, hanging moss, mangrove propagules, cocoa pods, shelf mushrooms, and floating snow layers or moss carpets sitting on top of the canopy fall alongside the tree instead of hovering in the air.
* **Ground Cover Protection:** Flowers, wildflowers, leaf litter, red shrubs, bushes, firefly bushes, dry grass, ferns, pink petals, saplings, and standing torches around a tree's base are left untouched and never interfere with tree detection.
* **No Leaf Stealing:** When trees of the same species grow next to each other with touching canopies, the plugin isolates only the felled tree’s leaves and keeps adjacent trees intact.
* **House & Cabin Protection:** Prevents players from accidentally felling wooden buildings by ignoring player-placed leaves, evaluating natural tree proportions, and cancelling if logs connect to building blocks (such as planks, slabs, stairs, doors, glass, fences, chests, beds, or wall-mounted torches).
* **Claim & Region Support:** Respects **WorldGuard**, **GriefPrevention**, **Towny**, and **LWC** protections so trees cannot be felled across protected boundaries.

### Realistic Physics & Felling Modes
* **Unified Rigid-Body Topple:** Trees pivot cleanly from their lowest base block as one connected unit with flicker-free block detachment.
* **Configurable Fall Directions:**
  * **Natural Lean:** Automatically tips toward the heaviest side of the tree’s branches and canopy.
  * **Player Direction:** Falls relative to the direction the woodcutter is facing.
  * **Random:** Falls in a randomized horizontal direction.
* **Weight, Wind Sway & Gravity:**
  * Fall duration scales smoothly with tree height and total block mass.
  * Features gradual tipping acceleration, gentle mid-air wind sway, branch flex, canopy sag, and slight drag on outer leaves.
  * Halfway through the topple, gravity pulls the tree downward—allowing trees cut near cliffs or lakes to tumble down slopes while keeping lower stems from clipping into the terrain and preventing trees from snapping onto overhead roofs or taller trees.
* **Two Felling Modes:**
  * **Instant Mode:** Breaks the tree into particles and item drops on impact, scattering loot slightly forward along the fall direction.
  * **Laydown Mode:** Lets the felled tree slide slightly and come to rest horizontally on the ground, allowing players to walk up and chop individual fallen logs and leaves.

### Audio, Particles & Player Feedback
* **Dynamic Stump Cracks:** Progressive fracture cracks appear directly on the stump as the tree tilts under stress.
* **Particles & Shockwaves:** Emits sawdust sparks at the start of the chop, species-accurate mid-air leaf trails (cherry petals, Nether spores, pale oak leaves, autumn poplar leaves), and an expanding dust and cloud shockwave ring on impact.
* **Species & Terrain-Adaptive Sounds:** Plays native wood-cracking audio tailored to the tree species on break, creaking wood during the fall, and adaptive impact sounds centered on the fallen canopy—including water splashes (with splash particles) in water, stony crashes on rock, muffled thuds on snow or sand, or grassy impacts on soil.
* **Action Bar HUD:** Displays live notifications above the hotbar, including chopping status, a visual progress bar, completion confirmation, cooldown timers, and protected-area alerts.

### Balancing & Server Safeguards
* **Tree Weight Resistance:** Applies subtle Mining Fatigue I on trees larger than 15 blocks so bigger trees take slightly longer to chop without becoming unmineable.
* **Durability & Hunger:** Applies axe durability damage per log felled (respecting the Unbreaking enchantment) and optional hunger exhaustion per block.
* **Falling Tree Hazard:** Heavy upper logs can crush and damage nearby mobs and non-Creative/Spectator players caught in the path (giving the woodcutter a brief grace window to step back).
* **Configurable Limits & Performance Guards:** Customize animation speeds, tilt angles, particle density, cooldowns, and maximum block limits. Includes chunk-border safety and a global active animation cap that gracefully falls back to natural breaking under heavy simultaneous load.
