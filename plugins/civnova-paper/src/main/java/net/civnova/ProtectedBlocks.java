package net.civnova;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Natural soul sand (and other configured blocks) cannot be broken.
 * Blocks a player placed are remembered per chunk and can be broken normally.
 */
final class ProtectedBlocks implements Listener {

    private final CivNovaPlugin plugin;
    private final NamespacedKey key;
    private final Set<Material> materials = EnumSet.noneOf(Material.class);

    ProtectedBlocks(CivNovaPlugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "placed_protected");
        reload();
    }

    void reload() {
        materials.clear();
        List<String> names = plugin.getConfig().getStringList("protected-blocks.materials");
        if (names.isEmpty() && !plugin.getConfig().isList("protected-blocks.materials")) {
            names = List.of("SOUL_SAND");
        }
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null && m.isBlock()) {
                materials.add(m);
            }
        }
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("protected-blocks.enabled", true);
    }

    // ---------------------------------------------------------------- placed-block registry (chunk PDC)

    private static int pack(Block b) {
        return (b.getX() & 15) | ((b.getZ() & 15) << 4) | ((b.getY() + 2048) << 8);
    }

    private int[] get(Chunk c) {
        int[] a = c.getPersistentDataContainer().get(key, PersistentDataType.INTEGER_ARRAY);
        return a == null ? new int[0] : a;
    }

    private boolean isPlaced(Block b) {
        int p = pack(b);
        for (int v : get(b.getChunk())) {
            if (v == p) {
                return true;
            }
        }
        return false;
    }

    private void setPlaced(Block b, boolean placed) {
        Chunk c = b.getChunk();
        int p = pack(b);
        int[] a = get(c);
        int idx = -1;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == p) {
                idx = i;
                break;
            }
        }
        PersistentDataContainer pdc = c.getPersistentDataContainer();
        if (placed && idx < 0) {
            int[] n = Arrays.copyOf(a, a.length + 1);
            n[a.length] = p;
            pdc.set(key, PersistentDataType.INTEGER_ARRAY, n);
        } else if (!placed && idx >= 0) {
            int[] n = new int[a.length - 1];
            System.arraycopy(a, 0, n, 0, idx);
            System.arraycopy(a, idx + 1, n, idx, a.length - idx - 1);
            if (n.length == 0) {
                pdc.remove(key);
            } else {
                pdc.set(key, PersistentDataType.INTEGER_ARRAY, n);
            }
        }
    }

    private boolean isNaturalProtected(Block b) {
        return enabled() && materials.contains(b.getType()) && !isPlaced(b);
    }

    // ---------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (materials.contains(e.getBlockPlaced().getType())) {
            setPlaced(e.getBlockPlaced(), true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!materials.contains(b.getType())) {
            return;
        }
        if (isNaturalProtected(b) && !e.getPlayer().hasPermission("civnova.protected.bypass")) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("Natural " + b.getType().name().toLowerCase().replace('_', ' ')
                + " cannot be mined. Only placed blocks can.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent e) {
        if (materials.contains(e.getBlock().getType())) {
            setPlaced(e.getBlock(), false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        filter(e.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        filter(e.blockList().iterator());
    }

    private void filter(Iterator<Block> it) {
        while (it.hasNext()) {
            Block b = it.next();
            if (materials.contains(b.getType())) {
                if (isNaturalProtected(b)) {
                    it.remove();
                } else {
                    setPlaced(b, false);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (materials.contains(b.getType())) {
                e.setCancelled(true); // keep it simple: protected materials never move
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (materials.contains(b.getType())) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (isNaturalProtected(e.getBlock()) && e.getTo() != e.getBlock().getType()) {
            e.setCancelled(true);
        }
    }
}
