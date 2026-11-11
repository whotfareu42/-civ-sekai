package net.civnova;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

/**
 * Banned items (cannot be crafted, placed, used or kept) and banned enchanted books
 * (the enchantments are stripped from books; enchanted gear stays allowed).
 */
final class Bans implements Listener {

    private static final String BYPASS = "civnova.banneditems.bypass";

    private final CivNovaPlugin plugin;
    private final Set<Material> items = new HashSet<>();
    private final Set<Enchantment> bookEnchants = new HashSet<>();

    Bans(CivNovaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        items.clear();
        bookEnchants.clear();
        for (String s : plugin.getConfig().getStringList("banned-items")) {
            if (s.equalsIgnoreCase("SHULKER_BOXES")) {
                items.addAll(Tag.SHULKER_BOXES.getValues());
                continue;
            }
            Material m = Material.matchMaterial(s);
            if (m != null) {
                items.add(m);
            } else {
                plugin.getLogger().warning("banned-items: unknown material " + s);
            }
        }
        for (String s : plugin.getConfig().getStringList("banned-book-enchants")) {
            Enchantment e = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(s.toLowerCase(Locale.ROOT)));
            if (e != null) {
                bookEnchants.add(e);
            } else {
                plugin.getLogger().warning("banned-book-enchants: unknown enchantment " + s);
            }
        }
    }

    private boolean bypass(HumanEntity p) {
        return p != null && p.hasPermission(BYPASS);
    }

    boolean isBanned(ItemStack it) {
        return it != null && items.contains(it.getType());
    }

    /** Returns the cleaned item (null = remove). */
    ItemStack clean(ItemStack it) {
        if (it == null || it.getType().isAir()) {
            return it;
        }
        if (items.contains(it.getType())) {
            return null;
        }
        if (it.getType() == Material.ENCHANTED_BOOK && it.getItemMeta() instanceof EnchantmentStorageMeta meta) {
            boolean changed = false;
            for (Enchantment e : bookEnchants) {
                if (meta.hasStoredEnchant(e)) {
                    meta.removeStoredEnchant(e);
                    changed = true;
                }
            }
            if (changed) {
                if (!meta.hasStoredEnchants()) {
                    return new ItemStack(Material.BOOK, it.getAmount());
                }
                ItemStack copy = it.clone();
                copy.setItemMeta(meta);
                return copy;
            }
        }
        return it;
    }

    private int cleanInventory(Inventory inv) {
        int removed = 0;
        ItemStack[] c = inv.getContents();
        for (int i = 0; i < c.length; i++) {
            ItemStack n = clean(c[i]);
            if (n != c[i]) {
                inv.setItem(i, n);
                removed++;
            }
        }
        return removed;
    }

    private void notify(HumanEntity p) {
        p.sendActionBar(Component.text("Banned items or enchantments were removed.", NamedTextColor.RED));
    }

    // ---------------------------------------------------------------- crafting / placing / using

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepare(PrepareItemCraftEvent e) {
        if (e.getRecipe() != null && isBanned(e.getRecipe().getResult())
            && e.getViewers().stream().noneMatch(this::bypass)) {
            e.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent e) {
        if (isBanned(e.getRecipe().getResult()) && !bypass(e.getWhoClicked())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (items.contains(e.getBlockPlaced().getType()) && !bypass(e.getPlayer())) {
            e.setCancelled(true);
            e.getPlayer().sendActionBar(Component.text("This item is banned on Civitas Nova.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if ((e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_AIR)
            && isBanned(e.getItem()) && !bypass(e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent e) {
        if (e.isGliding() && items.contains(Material.ELYTRA) && e.getEntity() instanceof Player p && !bypass(p)) {
            e.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- removal from inventories

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p && bypass(p)) {
            return;
        }
        ItemStack it = e.getItem().getItemStack();
        ItemStack n = clean(it);
        if (n == null) {
            e.setCancelled(true);
            e.getItem().remove();
        } else if (n != it) {
            e.getItem().setItemStack(n);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) {
        if (bypass(e.getPlayer())) {
            return;
        }
        if (cleanInventory(e.getInventory()) + cleanInventory(e.getPlayer().getInventory()) > 0) {
            notify(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (bypass(e.getWhoClicked())) {
            return;
        }
        ItemStack cur = e.getCurrentItem();
        ItemStack n = clean(cur);
        if (n != cur) {
            e.setCurrentItem(n);
            e.setCancelled(true);
            notify(e.getWhoClicked());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!bypass(e.getPlayer()) && cleanInventory(e.getPlayer().getInventory()) > 0) {
            notify(e.getPlayer());
        }
    }

    // ---------------------------------------------------------------- sources of banned books

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent e) {
        if (e.getItem().getType() == Material.BOOK || e.getItem().getType() == Material.ENCHANTED_BOOK) {
            e.getEnchantsToAdd().keySet().removeIf(bookEnchants::contains);
            if (e.getEnchantsToAdd().isEmpty()) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack r = e.getResult();
        ItemStack n = clean(r);
        if (n != r) {
            e.setResult(n == null || n.getType() == Material.BOOK ? null : n);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getCaught() instanceof org.bukkit.entity.Item item) {
            ItemStack it = item.getItemStack();
            ItemStack n = clean(it);
            if (n == null) {
                item.setItemStack(new ItemStack(Material.COD));
            } else if (n != it) {
                item.setItemStack(n);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLoot(LootGenerateEvent e) {
        e.getLoot().replaceAll(this::clean);
        e.getLoot().removeIf(i -> i == null);
    }
}
