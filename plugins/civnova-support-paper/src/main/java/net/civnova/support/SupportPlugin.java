package net.civnova.support;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.NotNull;

public final class SupportPlugin extends JavaPlugin implements Listener {

    private static final int MAX_SPEED = 30;
    private static final long COMBAT_MILLIS = 30_000;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastPvp = new HashMap<>();
    private NamespacedKey toolKey;
    private File sessionDir;

    private static final class Session {
        Location returnLocation;
        boolean wasVanished;
        boolean fly = true;
        boolean god = true;
        boolean nightVision = false;
        int speed = 1;
    }

    private static final class Gui implements InventoryHolder {
        final String type;
        final int page;
        Inventory inventory;

        Gui(String type, int page) {
            this.type = type;
            this.page = page;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    @Override
    public void onEnable() {
        toolKey = new NamespacedKey(this, "support_tool");
        sessionDir = new File(getDataFolder(), "sessions");
        sessionDir.mkdirs();
        getServer().getPluginManager().registerEvents(this, this);
        // players that are online after a reload: restore anything left over
        for (Player p : Bukkit.getOnlinePlayers()) {
            restoreFromFile(p);
        }
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (sessions.containsKey(p.getUniqueId())) {
                exit(p, false);
            }
        }
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        Session s = sessions.get(p.getUniqueId());
        if (args.length > 0 && (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("exit"))) {
            if (s == null) {
                msg(p, "You are not in support mode.", NamedTextColor.RED);
            } else {
                exit(p, true);
            }
            return true;
        }
        if (s == null) {
            enter(p);
        } else {
            openMain(p);
        }
        return true;
    }

    // ------------------------------------------------------------------ enter / exit

    private void enter(Player p) {
        Long last = lastPvp.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < COMBAT_MILLIS) {
            msg(p, "You cannot enter support mode during PvP combat.", NamedTextColor.RED);
            return;
        }
        Session s = new Session();
        s.returnLocation = p.getLocation().clone();
        s.wasVanished = isVanished(p);
        if (!saveToFile(p, s)) {
            msg(p, "Could not save your inventory, support mode not started.", NamedTextColor.RED);
            return;
        }
        sessions.put(p.getUniqueId(), s);
        p.getInventory().clear();
        p.getInventory().setItem(0, toolItem());
        p.getInventory().setHeldItemSlot(0);
        if (!s.wasVanished) {
            p.performCommand("sv on");
        }
        apply(p, s);
        p.setFlying(true);
        getLogger().info(p.getName() + " entered support mode at " + fmt(s.returnLocation));
        msg(p, "Support mode ON. Right-click the nether star or use /support for tools, /support off to leave.", NamedTextColor.GREEN);
    }

    private void exit(Player p, boolean teleportBack) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) {
            return;
        }
        p.closeInventory();
        restoreInventory(p);
        clearEffects(p);
        if (teleportBack && s.returnLocation != null && s.returnLocation.getWorld() != null) {
            p.teleport(s.returnLocation);
        }
        if (!s.wasVanished) {
            p.performCommand("sv off");
        }
        getLogger().info(p.getName() + " left support mode.");
        msg(p, "Support mode OFF. Your inventory was restored.", NamedTextColor.YELLOW);
    }

    private void apply(Player p, Session s) {
        boolean creative = p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
        p.setAllowFlight(s.fly || creative);
        if (!s.fly && !creative) {
            p.setFlying(false);
        }
        float fly = Math.min(1f, 0.1f + 0.9f * (s.speed - 1) / (MAX_SPEED - 1f));
        p.setFlySpeed(fly);
        p.removePotionEffect(PotionEffectType.SPEED);
        if (s.speed > 1) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, s.speed - 2, false, false, false));
        }
        if (s.nightVision) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION, 0, false, false, false));
        } else {
            p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
        if (s.god) {
            p.setFoodLevel(20);
            p.setFireTicks(0);
        }
    }

    private void clearEffects(Player p) {
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        p.setFlySpeed(0.1f);
        boolean creative = p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR;
        if (!creative) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        p.setFallDistance(0);
    }

    // ------------------------------------------------------------------ persistence

    private File file(UUID id) {
        return new File(sessionDir, id + ".yml");
    }

    private boolean saveToFile(Player p, Session s) {
        YamlConfiguration y = new YamlConfiguration();
        y.set("inventory", p.getInventory().getContents());
        y.set("location", s.returnLocation);
        y.set("wasVanished", s.wasVanished);
        try {
            y.save(file(p.getUniqueId()));
            return true;
        } catch (IOException e) {
            getLogger().severe("Could not save support session of " + p.getName() + ": " + e.getMessage());
            return false;
        }
    }

    private void restoreInventory(Player p) {
        File f = file(p.getUniqueId());
        p.getInventory().clear();
        if (!f.isFile()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        List<?> list = y.getList("inventory");
        if (list != null) {
            ItemStack[] contents = new ItemStack[p.getInventory().getSize()];
            for (int i = 0; i < list.size() && i < contents.length; i++) {
                if (list.get(i) instanceof ItemStack item) {
                    contents[i] = item;
                }
            }
            p.getInventory().setContents(contents);
        }
        if (!f.delete()) {
            getLogger().warning("Could not delete " + f.getName());
        }
    }

    /** After a crash or restart: give back the saved inventory and put the player back where they started. */
    private void restoreFromFile(Player p) {
        File f = file(p.getUniqueId());
        if (!f.isFile() || sessions.containsKey(p.getUniqueId())) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        Location loc = y.getLocation("location");
        boolean wasVanished = y.getBoolean("wasVanished");
        restoreInventory(p);
        clearEffects(p);
        if (loc != null && loc.getWorld() != null) {
            p.teleport(loc);
        }
        if (!wasVanished) {
            Bukkit.getScheduler().runTaskLater(this, () -> p.performCommand("sv off"), 20L);
        }
        msg(p, "Your last support session was not closed properly. Inventory restored.", NamedTextColor.YELLOW);
    }

    // ------------------------------------------------------------------ GUIs

    private ItemStack toolItem() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Support Tools", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Right-click to open", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isTool(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(toolKey, PersistentDataType.BYTE);
    }

    private static ItemStack button(Material mat, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(name, color).decoration(TextDecoration.ITALIC, false));
        List<Component> l = new ArrayList<>();
        for (String line : lore) {
            l.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(l);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack toggle(Material mat, String name, boolean on, String... lore) {
        ItemStack item = button(mat, name + ": " + (on ? "ON" : "OFF"), on ? NamedTextColor.GREEN : NamedTextColor.RED, lore);
        if (on) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void openMain(Player p) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) {
            return;
        }
        Gui gui = new Gui("main", 0);
        Inventory inv = Bukkit.createInventory(gui, 27, Component.text("Support Tools"));
        gui.inventory = inv;
        inv.setItem(10, toggle(Material.FEATHER, "Fly", s.fly, "Click to toggle"));
        inv.setItem(11, toggle(Material.TOTEM_OF_UNDYING, "God mode", s.god, "No damage, no hunger, mobs ignore you"));
        inv.setItem(12, toggle(Material.ENDER_EYE, "Night vision", s.nightVision, "Click to toggle"));
        ItemStack speed = button(Material.SUGAR, "Speed: " + s.speed + " / " + MAX_SPEED, NamedTextColor.AQUA,
            "Left-click: +1   Right-click: -1", "Shift-click: +/-5", "Affects flying and running");
        speed.setAmount(Math.max(1, Math.min(64, s.speed)));
        inv.setItem(14, speed);
        inv.setItem(15, button(Material.PLAYER_HEAD, "Online players", NamedTextColor.GOLD,
            "Teleport to players or look into", "their inventory / ender chest"));
        inv.setItem(16, button(Material.ENDER_PEARL, "Back to start", NamedTextColor.LIGHT_PURPLE,
            "Teleport to where you entered support mode"));
        inv.setItem(22, button(Material.BARRIER, "Leave support mode", NamedTextColor.RED,
            "Restores your inventory and", "teleports you back to your start point"));
        p.openInventory(inv);
    }

    private void openPlayers(Player p, int page) {
        List<Player> list = new ArrayList<>(Bukkit.getOnlinePlayers());
        list.remove(p);
        list.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        Gui gui = new Gui("players", page);
        Inventory inv = Bukkit.createInventory(gui, 54, Component.text("Players (" + (page + 1) + "/" + pages + ")"));
        gui.inventory = inv;
        for (int i = 0; i < 45 && page * 45 + i < list.size(); i++) {
            Player t = list.get(page * 45 + i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(t);
            meta.displayName(Component.text(t.getName(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            Location l = t.getLocation();
            meta.lore(List.of(
                Component.text(l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Left-click: teleport", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Right-click: inventory", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("Shift-click: ender chest", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
            head.setItemMeta(meta);
            inv.setItem(i, head);
        }
        if (page > 0) {
            inv.setItem(45, button(Material.ARROW, "Previous page", NamedTextColor.WHITE));
        }
        inv.setItem(49, button(Material.OAK_DOOR, "Back", NamedTextColor.WHITE));
        if (page < pages - 1) {
            inv.setItem(53, button(Material.ARROW, "Next page", NamedTextColor.WHITE));
        }
        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        Session s = sessions.get(p.getUniqueId());
        if (event.getInventory().getHolder() instanceof Gui gui) {
            event.setCancelled(true);
            if (s == null || event.getClickedInventory() != event.getView().getTopInventory()) {
                return;
            }
            if (gui.type.equals("main")) {
                handleMain(p, s, event);
            } else {
                handlePlayers(p, gui, event);
            }
            return;
        }
        if (s != null) {
            // support mode is look-only: no taking or moving items anywhere
            event.setCancelled(true);
        }
    }

    private void handleMain(Player p, Session s, InventoryClickEvent e) {
        switch (e.getRawSlot()) {
            case 10 -> s.fly = !s.fly;
            case 11 -> s.god = !s.god;
            case 12 -> s.nightVision = !s.nightVision;
            case 14 -> {
                int step = e.isShiftClick() ? 5 : 1;
                s.speed += e.isRightClick() ? -step : step;
                s.speed = Math.max(1, Math.min(MAX_SPEED, s.speed));
            }
            case 15 -> {
                openPlayers(p, 0);
                return;
            }
            case 16 -> {
                if (s.returnLocation != null) {
                    p.teleport(s.returnLocation);
                }
                p.closeInventory();
                return;
            }
            case 22 -> {
                exit(p, true);
                return;
            }
            default -> {
                return;
            }
        }
        apply(p, s);
        openMain(p);
    }

    private void handlePlayers(Player p, Gui gui, InventoryClickEvent e) {
        int slot = e.getRawSlot();
        if (slot == 45) {
            openPlayers(p, gui.page - 1);
            return;
        }
        if (slot == 53) {
            openPlayers(p, gui.page + 1);
            return;
        }
        if (slot == 49) {
            openMain(p);
            return;
        }
        ItemStack item = e.getCurrentItem();
        if (slot >= 45 || item == null || !(item.getItemMeta() instanceof SkullMeta meta) || meta.getOwningPlayer() == null) {
            return;
        }
        Player target = Bukkit.getPlayer(meta.getOwningPlayer().getUniqueId());
        if (target == null) {
            msg(p, "That player is offline.", NamedTextColor.RED);
            openPlayers(p, gui.page);
            return;
        }
        p.closeInventory();
        if (e.isShiftClick()) {
            p.performCommand("endersee " + target.getName());
        } else if (e.isRightClick()) {
            p.performCommand("invsee " + target.getName());
        } else {
            p.teleport(target.getLocation());
            getLogger().info(p.getName() + " (support) teleported to " + target.getName());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Gui || sessions.containsKey(event.getWhoClicked().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ support mode rules

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!sessions.containsKey(p.getUniqueId())) {
            return;
        }
        if (isTool(event.getItem()) && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            event.setCancelled(true);
            openMain(p);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (sessions.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        Session s = sessions.get(event.getEntity().getUniqueId());
        if (s != null && s.god) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onAttack(EntityDamageByEntityEvent event) {
        Player attacker = null;
        if (event.getDamager() instanceof Player p) {
            attacker = p;
        } else if (event.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            attacker = p;
        }
        if (attacker == null) {
            return;
        }
        if (sessions.containsKey(attacker.getUniqueId())) {
            event.setCancelled(true); // support staff never deal damage
            return;
        }
        if (event.getEntity() instanceof Player victim) {
            long now = System.currentTimeMillis();
            lastPvp.put(attacker.getUniqueId(), now);
            lastPvp.put(victim.getUniqueId(), now);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHunger(FoodLevelChangeEvent event) {
        Session s = sessions.get(event.getEntity().getUniqueId());
        if (s != null && s.god) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() != null) {
            Session s = sessions.get(event.getTarget().getUniqueId());
            if (s != null && s.god) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null) {
            Bukkit.getScheduler().runTask(this, () -> apply(event.getPlayer(), s));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null) {
            Bukkit.getScheduler().runTask(this, () -> apply(event.getPlayer(), s));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        lastPvp.remove(p.getUniqueId());
        if (sessions.containsKey(p.getUniqueId())) {
            exit(p, true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                restoreFromFile(p);
            }
        }, 5L);
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isVanished(Player p) {
        return p.getMetadata("vanished").stream().anyMatch(v -> v.asBoolean());
    }

    private static String fmt(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private static void msg(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text("[Support] ", NamedTextColor.AQUA).append(Component.text(text, color)));
    }
}
