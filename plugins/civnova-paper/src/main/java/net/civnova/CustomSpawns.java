package net.civnova;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Replaces a share of natural spawns with other mobs (vanilla entity types or MythicMobs).
 * Config "custom-spawns": list of entries, checked top to bottom, first hit wins.
 */
final class CustomSpawns implements Listener {

    private final CivNovaPlugin plugin;

    CustomSpawns(CivNovaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }
        List<Map<?, ?>> list = plugin.getConfig().getMapList("custom-spawns");
        if (list.isEmpty()) {
            return;
        }
        Location loc = e.getLocation();
        String world = loc.getWorld().getName();
        String biome = loc.getBlock().getBiome().getKey().getKey().toUpperCase(Locale.ROOT);
        for (Map<?, ?> m : list) {
            if (!world.equals(String.valueOf(m.get("world")))) {
                continue;
            }
            if (m.get("biomes") instanceof List<?> biomes && !biomes.isEmpty()
                && biomes.stream().noneMatch(b -> String.valueOf(b).equalsIgnoreCase(biome))) {
                continue;
            }
            if (m.get("ground") instanceof List<?> ground && !ground.isEmpty()) {
                String below = loc.getBlock().getRelative(org.bukkit.block.BlockFace.DOWN).getType().name();
                if (ground.stream().noneMatch(g -> String.valueOf(g).equalsIgnoreCase(below))) {
                    continue;
                }
            }
            if (Boolean.TRUE.equals(m.get("thunder")) && !loc.getWorld().isThundering()) {
                continue;
            }
            if (m.get("min-y") instanceof Number n && loc.getBlockY() < n.intValue()) {
                continue;
            }
            if (Boolean.TRUE.equals(m.get("outside")) && loc.getBlock().getLightFromSky() < 10) {
                continue;
            }
            double chance = m.get("chance") instanceof Number n ? n.doubleValue() : 0;
            if (ThreadLocalRandom.current().nextDouble() >= chance) {
                continue;
            }
            e.setCancelled(true);
            Object mythic = m.get("mythic");
            Object entity = m.get("entity");
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (mythic != null) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mm mobs spawn " + mythic + " 1 "
                        + world + "," + loc.getX() + "," + loc.getY() + "," + loc.getZ());
                } else if (entity != null) {
                    try {
                        loc.getWorld().spawnEntity(loc, EntityType.valueOf(String.valueOf(entity).toUpperCase(Locale.ROOT)),
                            CreatureSpawnEvent.SpawnReason.CUSTOM);
                    } catch (IllegalArgumentException ex) {
                        plugin.getLogger().warning("custom-spawns: unknown entity " + entity);
                    }
                }
            });
            return;
        }
    }
}
