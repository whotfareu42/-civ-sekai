package net.civnova.support;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.server.ServerCommandEvent;
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
    private static final int TOOL_SLOT = 4; // hotbar slot 5
    private static final long COMBAT_MILLIS = 30_000;
    private static final Set<String> GIVE_COMMANDS = Set.of("give", "minecraft:give", "i", "item", "essentials:give", "customitem", "cmc:customitem");

    enum Mode { SUPPORT, ADMIN }

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> lastPvp = new HashMap<>();
    private final Set<UUID> awaitingReason = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> lastCreative = new HashMap<>();
    private final Set<UUID> internalAction = new HashSet<>();
    private NamespacedKey toolKey;
    private NamespacedKey smiteKey;
    private final Map<UUID, String> pendingBan = new ConcurrentHashMap<>();
    private File sessionDir;
    private Tracker tracker;

    private static final class Session {
        Mode mode;
        String dutyGroup;
        long started = System.currentTimeMillis();
        Location returnLocation;
        GameMode originalGameMode = GameMode.SURVIVAL;
        boolean wasVanished;
        boolean fly = true;
        boolean god = true;
        boolean nightVision = false;
        boolean build = false;
        int speed = 1;
        final List<String> teleports = new ArrayList<>();
        int gamemodeChanges;
        int creativeItems;
        int giveCommands;
        int blocksBroken;
        int blocksPlaced;
        final List<String> kills = new ArrayList<>();
        final List<String> bans = new ArrayList<>();

        boolean needsReason() {
            return !teleports.isEmpty() || gamemodeChanges > 0 || creativeItems > 0 || giveCommands > 0;
        }

        String label() {
            return mode == Mode.ADMIN ? "admin mode" : "support mode";
        }
    }

    private Events events;

    void trackerLog(String actor, String text) {
        tracker.log(actor, text);
    }

    private static final class Gui implements InventoryHolder {
        final String type;
        final int page;
        String actor;
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
        saveDefaultConfig();
        toolKey = new NamespacedKey(this, "support_tool");
        smiteKey = new NamespacedKey(this, "smite");
        sessionDir = new File(getDataFolder(), "sessions");
        sessionDir.mkdirs();
        tracker = new Tracker(this);
        events = new Events(this);
        getServer().getPluginManager().registerEvents(events, this);
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::enforceTool, 20L, 20L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            restoreFromFile(p);
        }
    }

    @Override
    public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (sessions.containsKey(p.getUniqueId())) {
                exit(p, "(server stopped)", false);
            }
        }
    }

    // ------------------------------------------------------------------ commands

    /**
     * Another plugin also registers /am, so the alias from plugin.yml loses. Rewrite our short
     * aliases to the full command before Bukkit looks them up.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onAliasRewrite(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        int space = msg.indexOf(' ');
        String label = (space < 0 ? msg.substring(1) : msg.substring(1, space)).toLowerCase(Locale.ROOT);
        String rest = space < 0 ? "" : msg.substring(space);
        switch (label) {
            case "am", "adminmode" -> event.setMessage("/civnovasupport:admin" + rest);
            case "sm" -> event.setMessage("/civnovasupport:support" + rest);
            default -> { }
        }
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        if (command.getName().equalsIgnoreCase("tracker")) {
            return trackerCommand(sender, args);
        }
        if (command.getName().equalsIgnoreCase("events")) {
            events.listFor(sender);
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }
        Mode wanted = command.getName().equalsIgnoreCase("admin") ? Mode.ADMIN : Mode.SUPPORT;
        Session s = sessions.get(p.getUniqueId());
        if (args.length > 0 && (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("exit"))) {
            if (s == null) {
                msg(p, "You are not on duty.", NamedTextColor.RED);
            } else {
                requestExit(p, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim());
            }
            return true;
        }
        if (s == null) {
            enter(p, wanted);
        } else if (s.mode != wanted) {
            msg(p, "You are already in " + s.label() + ". Leave it first.", NamedTextColor.RED);
        } else {
            openMain(p);
        }
        return true;
    }

    private boolean trackerCommand(CommandSender sender, String[] args) {
        if (sender instanceof Player p) {
            if (args.length > 0) {
                openTrackerEntries(p, args[0], 0);
            } else {
                openTrackerOverview(p, 0);
            }
            return true;
        }
        String who = null;
        int page = 1;
        for (String a : args) {
            try {
                page = Math.max(1, Integer.parseInt(a));
            } catch (NumberFormatException e) {
                who = a;
            }
        }
        List<Tracker.Entry> list = tracker.find(who, (page - 1) * 10, 10);
        sender.sendMessage(Component.text("--- Staff tracker" + (who == null ? "" : " for " + who) + " (page " + page + ") ---", NamedTextColor.GOLD));
        if (list.isEmpty()) {
            sender.sendMessage(Component.text("No entries. Older entries are in plugins/CivNovaSupport/tracker/", NamedTextColor.GRAY));
        }
        for (Tracker.Entry e : list) {
            sender.sendMessage(Component.text(e.format(), NamedTextColor.GRAY));
        }
        return true;
    }

    // ------------------------------------------------------------------ enter / exit

    private String dutyGroupFor(Player p, Mode mode) {
        if (mode == Mode.ADMIN) {
            return getConfig().getString("duty-groups.admin", "admin-duty");
        }
        if (p.hasPermission("civnova.duty.moderator")) {
            return getConfig().getString("duty-groups.moderator", "moderator-duty");
        }
        return getConfig().getString("duty-groups.helper", "helper-duty");
    }

    private void enter(Player p, Mode mode) {
        Long last = lastPvp.get(p.getUniqueId());
        if (last != null && System.currentTimeMillis() - last < COMBAT_MILLIS) {
            msg(p, "You cannot go on duty during PvP combat.", NamedTextColor.RED);
            return;
        }
        Session s = new Session();
        s.mode = mode;
        s.dutyGroup = dutyGroupFor(p, mode);
        s.returnLocation = p.getLocation().clone();
        s.originalGameMode = p.getGameMode();
        s.wasVanished = isVanished(p);
        if (!saveToFile(p, s)) {
            msg(p, "Could not save your inventory, duty mode not started.", NamedTextColor.RED);
            return;
        }
        sessions.put(p.getUniqueId(), s);
        // duty permissions are temporary (12h) so they expire even if the server crashes
        console("lp user " + p.getUniqueId() + " parent addtemp " + s.dutyGroup + " 12h");
        p.getInventory().clear();
        p.getInventory().setItem(TOOL_SLOT, toolItem(s));
        p.getInventory().setHeldItemSlot(TOOL_SLOT);
        apply(p, s);
        p.setFlying(true);
        if (!s.wasVanished) {
            // wait until LuckPerms applied the duty group (vanish permission)
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && sessions.get(p.getUniqueId()) == s && !isVanished(p)) {
                    p.performCommand("sv on");
                }
            }, 20L);
        }
        tracker.log(p.getName(), "ENTER " + s.label() + " at " + fmt(s.returnLocation) + " (group " + s.dutyGroup + ")");
        msg(p, (mode == Mode.ADMIN ? "Admin" : "Support") + " mode ON. Right-click the nether star or use /" + (mode == Mode.ADMIN ? "admin" : "support")
            + " for tools, /" + (mode == Mode.ADMIN ? "admin" : "support") + " off to leave. Everything you do is logged.", NamedTextColor.GREEN);
    }

    private void requestExit(Player p, String reason) {
        Session s = sessions.get(p.getUniqueId());
        if (s == null) {
            return;
        }
        if ((reason == null || reason.isBlank()) && getConfig().getBoolean("require-reason", true)) {
            awaitingReason.add(p.getUniqueId());
            p.closeInventory();
            String did = summaryShort(s);
            msg(p, (did.isEmpty() ? "" : "You " + did + ". ") + "Why were you on duty? Type the reason in chat now (only the staff log sees it), or use /"
                + (s.mode == Mode.ADMIN ? "admin" : "support") + " off <reason>.", NamedTextColor.GOLD);
            return;
        }
        exit(p, reason == null || reason.isBlank() ? "-" : reason, true);
    }

    private void exit(Player p, String reason, boolean teleportBack) {
        Session s = sessions.remove(p.getUniqueId());
        awaitingReason.remove(p.getUniqueId());
        if (s == null) {
            return;
        }
        internalAction.add(p.getUniqueId());
        try {
            p.closeInventory();
            if (p.getGameMode() != s.originalGameMode) {
                p.setGameMode(s.originalGameMode);
            }
            restoreInventory(p);
            clearEffects(p);
            if (teleportBack && s.returnLocation != null && s.returnLocation.getWorld() != null) {
                p.teleport(s.returnLocation);
            }
            if (!s.wasVanished && isVanished(p)) {
                p.performCommand("sv off");
            }
        } finally {
            internalAction.remove(p.getUniqueId());
        }
        String group = s.dutyGroup;
        Bukkit.getScheduler().runTaskLater(this, () -> console("lp user " + p.getUniqueId() + " parent removetemp " + group), 5L);
        long minutes = (System.currentTimeMillis() - s.started) / 60000;
        tracker.log(p.getName(), "EXIT " + s.label() + " after " + minutes + " min | " + summaryLong(s) + " | reason: " + reason);
        msg(p, s.label().substring(0, 1).toUpperCase(Locale.ROOT) + s.label().substring(1) + " OFF. Your inventory was restored.", NamedTextColor.YELLOW);
    }

    private static String summaryShort(Session s) {
        List<String> parts = new ArrayList<>();
        if (!s.teleports.isEmpty()) {
            parts.add("teleported " + s.teleports.size() + "x");
        }
        if (s.gamemodeChanges > 0) {
            parts.add("changed gamemode " + s.gamemodeChanges + "x");
        }
        if (s.creativeItems > 0 || s.giveCommands > 0) {
            parts.add("created items");
        }
        if (!s.kills.isEmpty()) {
            parts.add("killed " + s.kills.size() + " player(s)");
        }
        if (!s.bans.isEmpty()) {
            parts.add("banned " + s.bans.size() + " player(s)");
        }
        return String.join(", ", parts);
    }

    private static String summaryLong(Session s) {
        return "teleports: " + (s.teleports.isEmpty() ? "0" : String.join(", ", s.teleports))
            + " | gamemode changes: " + s.gamemodeChanges
            + " | creative items: " + s.creativeItems
            + " | give cmds: " + s.giveCommands
            + " | blocks broken/placed: " + s.blocksBroken + "/" + s.blocksPlaced
            + " | kills: " + (s.kills.isEmpty() ? "0" : String.join(", ", s.kills))
            + " | bans: " + (s.bans.isEmpty() ? "0" : String.join(", ", s.bans));
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

    private void console(String cmd) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
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
        y.set("mode", s.mode.name());
        y.set("dutyGroup", s.dutyGroup);
        y.set("gamemode", p.getGameMode().name());
        try {
            y.save(file(p.getUniqueId()));
            return true;
        } catch (IOException e) {
            getLogger().severe("Could not save duty session of " + p.getName() + ": " + e.getMessage());
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
        String group = y.getString("dutyGroup");
        GameMode original;
        try {
            original = GameMode.valueOf(y.getString("gamemode", "SURVIVAL"));
        } catch (IllegalArgumentException e) {
            original = GameMode.SURVIVAL;
        }
        internalAction.add(p.getUniqueId());
        try {
            if (p.getGameMode() != original) {
                p.setGameMode(original);
            }
            restoreInventory(p);
            clearEffects(p);
            if (loc != null && loc.getWorld() != null) {
                p.teleport(loc);
            }
        } finally {
            internalAction.remove(p.getUniqueId());
        }
        if (!wasVanished) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && isVanished(p)) {
                    p.performCommand("sv off");
                }
                if (group != null) {
                    console("lp user " + p.getUniqueId() + " parent removetemp " + group);
                }
            }, 20L);
        } else if (group != null) {
            console("lp user " + p.getUniqueId() + " parent removetemp " + group);
        }
        tracker.log(p.getName(), "EXIT (restored after crash/disconnect, no reason given)");
        msg(p, "Your last duty session was not closed properly. Inventory restored.", NamedTextColor.YELLOW);
    }

    // ------------------------------------------------------------------ GUIs

    private ItemStack toolItem(Session s) {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(s.mode == Mode.ADMIN ? "Admin Tools" : "Support Tools", NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
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
        Inventory inv = Bukkit.createInventory(gui, 27, Component.text(s.mode == Mode.ADMIN ? "Admin Tools" : "Support Tools"));
        gui.inventory = inv;
        inv.setItem(10, toggle(Material.FEATHER, "Fly", s.fly, "Click to toggle"));
        inv.setItem(11, toggle(Material.TOTEM_OF_UNDYING, "God mode", s.god, "No damage, no hunger, mobs ignore you"));
        inv.setItem(12, toggle(Material.ENDER_EYE, "Night vision", s.nightVision, "Click to toggle"));
        if (s.mode == Mode.ADMIN) {
            boolean creative = p.getGameMode() == GameMode.CREATIVE;
            inv.setItem(13, toggle(Material.GRASS_BLOCK, "Creative", creative, "Click to switch creative/survival", "(logged)"));
            inv.setItem(20, toggle(Material.DIAMOND_PICKAXE, "Build (break/place blocks)", s.build, "Off: you cannot break or place blocks", "Every change is logged"));
            inv.setItem(24, button(Material.FIREWORK_ROCKET, "Events", NamedTextColor.LIGHT_PURPLE,
                "Start server events like x2 essence,", "ore rush or XP boost"));
        }
        ItemStack speed = button(Material.SUGAR, "Speed: " + s.speed + " / " + MAX_SPEED, NamedTextColor.AQUA,
            "Left-click: +1   Right-click: -1", "Shift-click: +/-5", "Affects flying and running");
        speed.setAmount(Math.max(1, Math.min(64, s.speed)));
        inv.setItem(14, speed);
        inv.setItem(15, button(Material.PLAYER_HEAD, "Online players", NamedTextColor.GOLD,
            "Teleport to players or look into", "their inventory / ender chest"));
        inv.setItem(16, button(Material.ENDER_PEARL, "Back to start", NamedTextColor.LIGHT_PURPLE,
            "Teleport to where you went on duty"));
        if (canModerate(s)) {
            inv.setItem(18, button(Material.ANVIL, "Ban a player", NamedTextColor.DARK_RED,
                "Pick a player, then type the reason in chat"));
            inv.setItem(26, button(Material.BLAZE_ROD, "Smite", NamedTextColor.DARK_RED,
                "DOUBLE-CLICK to take the smite rod.", "One hit kills any player or mob.", "Every kill is logged."));
        }
        inv.setItem(22, button(Material.BARRIER, "Leave " + s.label(), NamedTextColor.RED,
            "Restores your inventory and", "teleports you back to your start point"));
        p.openInventory(inv);
    }

    private void openPlayers(Player p, int page) {
        openPlayers(p, page, false);
    }

    private void openPlayers(Player p, int page, boolean ban) {
        List<Player> list = new ArrayList<>(Bukkit.getOnlinePlayers());
        list.remove(p);
        list.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (list.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        Gui gui = new Gui(ban ? "ban" : "players", page);
        Inventory inv = Bukkit.createInventory(gui, 54, Component.text((ban ? "Ban a player (" : "Players (") + (page + 1) + "/" + pages + ")"));
        gui.inventory = inv;
        for (int i = 0; i < 45 && page * 45 + i < list.size(); i++) {
            Player t = list.get(page * 45 + i);
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setOwningPlayer(t);
            meta.displayName(Component.text(t.getName(), NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            Location l = t.getLocation();
            meta.lore(ban ? List.of(Component.text("Click to ban " + t.getName(), NamedTextColor.RED).decoration(TextDecoration.ITALIC, false)) : List.of(
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

    // ------------------------------------------------------------------ tracker GUI (for everyone)

    private void openTrackerOverview(Player p, int page) {
        List<String> actors = tracker.actors();
        int pages = Math.max(1, (actors.size() + 44) / 45);
        page = Math.max(0, Math.min(page, pages - 1));
        Gui gui = new Gui("tracker", page);
        Inventory inv = Bukkit.createInventory(gui, 54, Component.text("Staff actions - who"));
        gui.inventory = inv;
        for (int i = 0; i < 45 && page * 45 + i < actors.size(); i++) {
            String name = actors.get(page * 45 + i);
            ItemStack icon;
            if (name.equalsIgnoreCase("CONSOLE") || name.equalsIgnoreCase("Server")) {
                icon = button(Material.COMMAND_BLOCK, name, NamedTextColor.GOLD, "Commands run from the server console", "Click to see what was done");
            } else {
                icon = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta meta = (SkullMeta) icon.getItemMeta();
                meta.setOwningPlayer(Bukkit.getOfflinePlayer(name));
                meta.displayName(Component.text(name, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
                meta.lore(List.of(Component.text("Click to see what " + name + " did", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
                icon.setItemMeta(meta);
            }
            inv.setItem(i, icon);
        }
        if (page > 0) {
            inv.setItem(45, button(Material.ARROW, "Previous page", NamedTextColor.WHITE));
        }
        inv.setItem(49, button(Material.BOOK, "All staff actions", NamedTextColor.AQUA, "Newest first, everyone together",
            "Everything staff does is logged here", "so the server stays transparent."));
        if (page < pages - 1) {
            inv.setItem(53, button(Material.ARROW, "Next page", NamedTextColor.WHITE));
        }
        p.openInventory(inv);
    }

    private void openTrackerEntries(Player p, String actor, int page) {
        page = Math.max(0, page);
        List<Tracker.Entry> list = tracker.find(actor, page * 45, 46);
        boolean more = list.size() > 45;
        Gui gui = new Gui("tracker-entries", page);
        gui.actor = actor;
        Inventory inv = Bukkit.createInventory(gui, 54, Component.text((actor == null ? "All staff" : actor) + " - page " + (page + 1)));
        gui.inventory = inv;
        boolean coords = p.hasPermission("civnova.tracker.coords");
        for (int i = 0; i < 45 && i < list.size(); i++) {
            Tracker.Entry e = list.get(i);
            String text = coords ? e.action() : e.publicAction();
            String type = text.split(" ", 2)[0];
            ItemStack item = new ItemStack(iconFor(type));
            ItemMeta meta = item.getItemMeta();
            meta.displayName(Component.text(e.actor() + " - " + type, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(e.timeText(), NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            for (String line : wrap(text, 40)) {
                lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }
        if (page > 0) {
            inv.setItem(45, button(Material.ARROW, "Newer", NamedTextColor.WHITE));
        }
        inv.setItem(49, button(Material.OAK_DOOR, "Back", NamedTextColor.WHITE));
        if (more) {
            inv.setItem(53, button(Material.ARROW, "Older", NamedTextColor.WHITE));
        }
        p.openInventory(inv);
    }

    private static Material iconFor(String type) {
        return switch (type) {
            case "CMD" -> Material.PAPER;
            case "TELEPORT" -> Material.ENDER_PEARL;
            case "GAMEMODE" -> Material.GRASS_BLOCK;
            case "CREATIVE" -> Material.CHEST;
            case "ENTER" -> Material.LIME_DYE;
            case "EXIT" -> Material.RED_DYE;
            case "BREAK" -> Material.IRON_PICKAXE;
            case "PLACE" -> Material.BRICKS;
            case "DROP" -> Material.DROPPER;
            case "BUILD" -> Material.DIAMOND_PICKAXE;
            default -> Material.MAP;
        };
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width && !line.isEmpty()) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (!line.isEmpty()) {
                line.append(' ');
            }
            line.append(word);
        }
        if (!line.isEmpty()) {
            out.add(line.toString());
        }
        return out.size() > 12 ? out.subList(0, 12) : out;
    }

    private void handleTracker(Player p, Gui gui, InventoryClickEvent e) {
        int slot = e.getRawSlot();
        if (gui.type.equals("tracker")) {
            if (slot == 45) {
                openTrackerOverview(p, gui.page - 1);
            } else if (slot == 53) {
                openTrackerOverview(p, gui.page + 1);
            } else if (slot == 49) {
                openTrackerEntries(p, null, 0);
            } else if (slot < 45 && e.getCurrentItem() != null) {
                List<String> actors = tracker.actors();
                int idx = gui.page * 45 + slot;
                if (idx < actors.size()) {
                    openTrackerEntries(p, actors.get(idx), 0);
                }
            }
            return;
        }
        if (slot == 45) {
            openTrackerEntries(p, gui.actor, gui.page - 1);
        } else if (slot == 53) {
            openTrackerEntries(p, gui.actor, gui.page + 1);
        } else if (slot == 49) {
            openTrackerOverview(p, 0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        Session s = sessions.get(p.getUniqueId());
        if (event.getInventory().getHolder() instanceof Gui tg && tg.type.startsWith("tracker")) {
            event.setCancelled(true);
            if (event.getClickedInventory() == event.getView().getTopInventory()) {
                handleTracker(p, tg, event);
            }
            return;
        }
        if (event.getInventory().getHolder() instanceof Gui gui) {
            event.setCancelled(true);
            if (s == null || event.getClickedInventory() != event.getView().getTopInventory()) {
                return;
            }
            if (gui.type.equals("main")) {
                handleMain(p, s, event);
            } else if (gui.type.equals("events")) {
                handleEvents(p, s, event);
            } else {
                handlePlayers(p, s, gui, event);
            }
            return;
        }
        if (s != null && involvesTool(p, event)) {
            event.setCancelled(true);
            return;
        }
        if (s != null && s.mode == Mode.SUPPORT) {
            // support mode is look-only: no taking or moving items anywhere
            event.setCancelled(true);
        }
    }

    private boolean involvesTool(Player p, InventoryClickEvent e) {
        if (isTool(e.getCurrentItem()) || isTool(e.getCursor())) {
            return true;
        }
        if (e.getView().getTopInventory() != p.getInventory() && e.getView().getTopInventory().getType() != org.bukkit.event.inventory.InventoryType.CRAFTING
            && (isSmite(e.getCurrentItem()) || isSmite(e.getCursor()))) {
            return true; // smite rod never goes into containers
        }
        if (e.getClick() == ClickType.NUMBER_KEY && e.getHotbarButton() >= 0
            && isTool(p.getInventory().getItem(e.getHotbarButton()))) {
            return true;
        }
        return e.getClick() == ClickType.SWAP_OFFHAND && isTool(p.getInventory().getItemInOffHand());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwapHands(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (isTool(event.getMainHandItem()) || isTool(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCloseContainer(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) {
            return;
        }
        // a tool must never end up in a container
        org.bukkit.inventory.Inventory top = event.getView().getTopInventory();
        if (top != event.getPlayer().getInventory()) {
            for (int i = 0; i < top.getSize(); i++) {
                if (isTool(top.getItem(i)) || isSmite(top.getItem(i))) {
                    top.setItem(i, null);
                }
            }
        }
    }

    /** Keep exactly one tool in hotbar slot 5 for everyone on duty. */
    private void enforceTool() {
        for (Map.Entry<UUID, Session> en : sessions.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p == null) {
                continue;
            }
            org.bukkit.inventory.PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.getSize(); i++) {
                if (i != TOOL_SLOT && isTool(inv.getItem(i))) {
                    inv.setItem(i, null);
                }
            }
            if (isTool(p.getItemOnCursor())) {
                p.setItemOnCursor(null);
            }
            if (!isTool(inv.getItem(TOOL_SLOT))) {
                ItemStack old = inv.getItem(TOOL_SLOT);
                inv.setItem(TOOL_SLOT, toolItem(en.getValue()));
                if (old != null && !old.getType().isAir()) {
                    inv.addItem(old).values().forEach(rest -> p.getWorld().dropItem(p.getLocation(), rest));
                }
            }
        }
    }

    private void handleMain(Player p, Session s, InventoryClickEvent e) {
        switch (e.getRawSlot()) {
            case 10 -> s.fly = !s.fly;
            case 11 -> s.god = !s.god;
            case 12 -> s.nightVision = !s.nightVision;
            case 13 -> {
                if (s.mode != Mode.ADMIN) {
                    return;
                }
                p.setGameMode(p.getGameMode() == GameMode.CREATIVE ? GameMode.SURVIVAL : GameMode.CREATIVE);
            }
            case 18 -> {
                if (canModerate(s)) {
                    openPlayers(p, 0, true);
                }
                return;
            }
            case 26 -> {
                if (!canModerate(s)) {
                    return;
                }
                if (e.getClick() == ClickType.DOUBLE_CLICK) {
                    p.getInventory().addItem(smiteItem());
                    tracker.log(p.getName(), "TOOK SMITE ROD @ " + fmt(p.getLocation()));
                    p.closeInventory();
                    msg(p, "You took the smite rod. Hitting a player with it kills them.", NamedTextColor.DARK_RED);
                } else {
                    msg(p, "Double-click to take the smite rod.", NamedTextColor.GRAY);
                }
                return;
            }
            case 20 -> {
                if (s.mode != Mode.ADMIN) {
                    return;
                }
                s.build = !s.build;
                tracker.log(p.getName(), "BUILD " + (s.build ? "ON" : "OFF") + " @ " + fmt(p.getLocation()));
            }
            case 14 -> {
                int step = e.isShiftClick() ? 5 : 1;
                s.speed += e.isRightClick() ? -step : step;
                s.speed = Math.max(1, Math.min(MAX_SPEED, s.speed));
            }
            case 15 -> {
                openPlayers(p, 0);
                return;
            }
            case 24 -> {
                if (s.mode == Mode.ADMIN) {
                    openEvents(p);
                }
                return;
            }
            case 16 -> {
                if (s.returnLocation != null) {
                    internalAction.add(p.getUniqueId());
                    try {
                        p.teleport(s.returnLocation);
                    } finally {
                        internalAction.remove(p.getUniqueId());
                    }
                }
                p.closeInventory();
                return;
            }
            case 22 -> {
                requestExit(p, null);
                return;
            }
            default -> {
                return;
            }
        }
        apply(p, s);
        openMain(p);
    }

    // ------------------------------------------------------------------ events GUI
    // One column per event: icon (start/stop), multiplier +/-, duration +/-.

    private void openEvents(Player p) {
        Gui gui = new Gui("events", 0);
        Inventory inv = Bukkit.createInventory(gui, 54, Component.text("Server events"));
        gui.inventory = inv;
        Events.Type[] types = Events.Type.values();
        for (int i = 0; i < types.length; i++) {
            Events.Type t = types[i];
            Events.State st = events.state(t);
            int col = i + 1;
            boolean on = st.active();
            List<String> lore = new ArrayList<>();
            lore.add(t.what + " x" + st.mult);
            if (on) {
                lore.add("RUNNING - " + Events.time(st.endsAt - System.currentTimeMillis()) + " left");
                lore.add("Click to stop now");
            } else {
                lore.add("Off - would run for " + Events.time(st.duration));
                lore.add("Click to start");
            }
            ItemStack icon = button(t.icon, t.title + " x" + st.mult, on ? NamedTextColor.GREEN : NamedTextColor.GRAY, lore.toArray(String[]::new));
            icon.setAmount(st.mult);
            if (on) {
                ItemMeta m = icon.getItemMeta();
                m.setEnchantmentGlintOverride(true);
                icon.setItemMeta(m);
            }
            inv.setItem(col, icon);
            inv.setItem(9 + col, button(Material.LIME_STAINED_GLASS_PANE, "Multiplier +1", NamedTextColor.GREEN, "Now x" + st.mult + " (max x" + Events.MAX_MULT + ")"));
            inv.setItem(18 + col, button(Material.RED_STAINED_GLASS_PANE, "Multiplier -1", NamedTextColor.RED, "Now x" + st.mult + " (min x" + Events.MIN_MULT + ")"));
            String len = on ? Events.time(st.endsAt - System.currentTimeMillis()) + " left" : Events.time(st.duration);
            inv.setItem(27 + col, button(Material.CLOCK, "Longer +30m", NamedTextColor.GREEN, "Shift-click: +6h", "Now " + len));
            inv.setItem(36 + col, button(Material.COMPASS, "Shorter -30m", NamedTextColor.RED, "Shift-click: -6h", "Now " + len));
        }
        inv.setItem(49, button(Material.OAK_DOOR, "Back", NamedTextColor.WHITE));
        p.openInventory(inv);
    }

    private void handleEvents(Player p, Session s, InventoryClickEvent e) {
        if (s.mode != Mode.ADMIN) {
            return;
        }
        int slot = e.getRawSlot();
        if (slot == 49) {
            openMain(p);
            return;
        }
        int col = slot % 9;
        int row = slot / 9;
        Events.Type[] types = Events.Type.values();
        if (col < 1 || col > types.length || row > 4) {
            return;
        }
        Events.Type t = types[col - 1];
        long step = e.isShiftClick() ? Events.BIG_STEP : Events.STEP;
        switch (row) {
            case 0 -> events.toggle(p, t);
            case 1 -> events.changeMult(p, t, 1);
            case 2 -> events.changeMult(p, t, -1);
            case 3 -> events.changeDuration(p, t, step);
            case 4 -> events.changeDuration(p, t, -step);
            default -> {
                return;
            }
        }
        openEvents(p);
    }

    private void handlePlayers(Player p, Session s, Gui gui, InventoryClickEvent e) {
        int slot = e.getRawSlot();
        boolean ban = gui.type.equals("ban");
        if (slot == 45) {
            openPlayers(p, gui.page - 1, ban);
            return;
        }
        if (slot == 53) {
            openPlayers(p, gui.page + 1, ban);
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
        if (ban) {
            if (!canModerate(s)) {
                return;
            }
            pendingBan.put(p.getUniqueId(), target.getName());
            msg(p, "Type the ban reason for " + target.getName() + " in chat (or 'cancel').", NamedTextColor.GOLD);
            return;
        }
        if (e.isShiftClick()) {
            p.performCommand("endersee " + target.getName());
        } else if (e.isRightClick()) {
            p.performCommand("invsee " + target.getName());
        } else {
            internalAction.add(p.getUniqueId());
            try {
                p.teleport(target.getLocation());
            } finally {
                internalAction.remove(p.getUniqueId());
            }
            s.teleports.add(target.getName());
            tracker.log(p.getName(), "TELEPORT to " + target.getName() + " at " + fmt(target.getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) {
            event.setCancelled(true);
            return;
        }
        Session s = sessions.get(event.getWhoClicked().getUniqueId());
        if (s != null && (s.mode == Mode.SUPPORT || isTool(event.getOldCursor()))) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ reason via chat

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        String banTarget = pendingBan.remove(id);
        if (banTarget != null) {
            event.setCancelled(true);
            String banReason = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
            Bukkit.getScheduler().runTask(this, () -> doBan(id, banTarget, banReason));
            return;
        }
        if (!awaitingReason.remove(id)) {
            return;
        }
        event.setCancelled(true);
        String reason = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(this, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !sessions.containsKey(id)) {
                return;
            }
            if (reason.isEmpty()) {
                requestExit(p, null);
            } else {
                exit(p, reason, true);
            }
        });
    }

    private void doBan(UUID id, String target, String reason) {
        Player p = Bukkit.getPlayer(id);
        Session s = sessions.get(id);
        if (p == null || s == null || !canModerate(s)) {
            return;
        }
        if (reason.isEmpty() || reason.equalsIgnoreCase("cancel")) {
            msg(p, "Ban cancelled.", NamedTextColor.GRAY);
            return;
        }
        p.performCommand("ban " + target + " " + reason);
        s.bans.add(target);
        tracker.log(p.getName(), "BAN " + target + " | reason: " + reason);
        msg(p, target + " was banned.", NamedTextColor.YELLOW);
    }

    private boolean canModerate(Session s) {
        return s.mode == Mode.ADMIN || !s.dutyGroup.equals(getConfig().getString("duty-groups.helper", "helper-duty"));
    }

    private ItemStack smiteItem() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("Smite", NamedTextColor.DARK_RED).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("One hit kills any player or mob", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
            Component.text("Logged in /tracker", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(smiteKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isSmite(ItemStack item) {
        return item != null && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(smiteKey, PersistentDataType.BYTE);
    }

    // ------------------------------------------------------------------ duty mode rules

    private Session supportOnly(UUID id) {
        Session s = sessions.get(id);
        return s != null && s.mode == Mode.SUPPORT ? s : null;
    }

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
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        if (s.mode == Mode.SUPPORT || !s.build) {
            event.setCancelled(true);
            if (s.mode == Mode.ADMIN) {
                event.getPlayer().sendActionBar(Component.text("Build is OFF - turn it on in the admin tools", NamedTextColor.RED));
            }
        } else {
            s.blocksBroken++;
            tracker.log(event.getPlayer().getName(), "BREAK " + event.getBlock().getType().name().toLowerCase(Locale.ROOT) + " @ " + fmt(event.getBlock().getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        if (s.mode == Mode.SUPPORT || !s.build) {
            event.setCancelled(true);
            if (s.mode == Mode.ADMIN) {
                event.getPlayer().sendActionBar(Component.text("Build is OFF - turn it on in the admin tools", NamedTextColor.RED));
            }
        } else {
            s.blocksPlaced++;
            tracker.log(event.getPlayer().getName(), "PLACE " + event.getBlock().getType().name().toLowerCase(Locale.ROOT) + " @ " + fmt(event.getBlock().getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null) {
            return;
        }
        if (s.mode == Mode.SUPPORT || isTool(event.getItemDrop().getItemStack()) || isSmite(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        } else {
            tracker.log(event.getPlayer().getName(), "DROP " + describe(event.getItemDrop().getItemStack()) + " at " + fmt(event.getPlayer().getLocation()));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (supportOnly(event.getEntity().getUniqueId()) != null) {
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
        Session s = sessions.get(attacker.getUniqueId());
        if (s != null && event.getDamager() == attacker && isSmite(attacker.getInventory().getItemInMainHand())
            && canModerate(s) && event.getEntity() instanceof org.bukkit.entity.LivingEntity victim) {
            event.setCancelled(true);
            if (sessions.containsKey(victim.getUniqueId())) {
                msg(attacker, "You cannot smite staff on duty.", NamedTextColor.RED);
                return;
            }
            victim.setHealth(0);
            if (victim instanceof Player) {
                s.kills.add(victim.getName());
                tracker.log(attacker.getName(), "KILL " + victim.getName() + " with smite @ " + fmt(victim.getLocation()));
            }
            return;
        }
        if (s != null) {
            if (s.mode == Mode.SUPPORT || event.getEntity() instanceof Player) {
                event.setCancelled(true); // staff on duty never hurt players
            }
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
        lastCreative.remove(p.getUniqueId());
        pendingBan.remove(p.getUniqueId());
        if (sessions.containsKey(p.getUniqueId())) {
            exit(p, "(logged out, no reason given)", true);
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

    // ------------------------------------------------------------------ tracker

    private boolean tracked(Player p) {
        return sessions.containsKey(p.getUniqueId()) || p.hasPermission("civnova.tracked");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!tracked(p)) {
            return;
        }
        String raw = event.getMessage();
        String label = raw.length() > 1 ? raw.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT) : "";
        if (getConfig().getStringList("tracker.ignored-commands").contains(label)) {
            return;
        }
        Session s = sessions.get(p.getUniqueId());
        if (s != null && !event.isCancelled() && GIVE_COMMANDS.contains(label)) {
            s.giveCommands++;
        }
        tracker.log(p.getName(), "CMD " + raw + (event.isCancelled() ? " (blocked)" : "") + " @ " + fmt(p.getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsole(ServerCommandEvent event) {
        String cmd = event.getCommand();
        if (cmd.startsWith("lp user ") && (cmd.contains(" parent addtemp ") || cmd.contains(" parent removetemp "))) {
            return; // our own duty group changes
        }
        tracker.log(event.getSender().getName(), "CMD /" + cmd);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGamemode(PlayerGameModeChangeEvent event) {
        Player p = event.getPlayer();
        if (!tracked(p) || internalAction.contains(p.getUniqueId())) {
            return;
        }
        Session s = sessions.get(p.getUniqueId());
        if (s != null) {
            s.gamemodeChanges++;
        }
        tracker.log(p.getName(), "GAMEMODE " + p.getGameMode() + " -> " + event.getNewGameMode() + " @ " + fmt(p.getLocation()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player p = event.getPlayer();
        if (internalAction.contains(p.getUniqueId()) || !tracked(p)) {
            return;
        }
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause != PlayerTeleportEvent.TeleportCause.COMMAND && cause != PlayerTeleportEvent.TeleportCause.SPECTATE) {
            return;
        }
        Session s = sessions.get(p.getUniqueId());
        String to = fmt(event.getTo());
        Player near = null;
        double best = 9;
        for (Player o : event.getTo().getWorld().getPlayers()) {
            if (o != p && o.getLocation().distanceSquared(event.getTo()) < best) {
                best = o.getLocation().distanceSquared(event.getTo());
                near = o;
            }
        }
        String target = near != null ? near.getName() : to;
        if (s != null) {
            s.teleports.add(target);
        }
        tracker.log(p.getName(), "TELEPORT (" + cause + ") " + fmt(event.getFrom()) + " -> " + to + (near != null ? " (to " + near.getName() + ")" : ""));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player p) || !tracked(p)) {
            return;
        }
        ItemStack cursor = event.getCursor();
        if (cursor == null || cursor.getType().isAir()) {
            return;
        }
        String key = describe(cursor);
        String last = lastCreative.get(p.getUniqueId());
        String now = key + "@" + (System.currentTimeMillis() / 2000);
        if (now.equals(last)) {
            return; // same item within ~2 seconds (moving it around)
        }
        lastCreative.put(p.getUniqueId(), now);
        Session s = sessions.get(p.getUniqueId());
        if (s != null) {
            s.creativeItems++;
        }
        tracker.log(p.getName(), "CREATIVE ITEM " + key + " @ " + fmt(p.getLocation()));
    }

    // ------------------------------------------------------------------ helpers

    private static String describe(ItemStack item) {
        String name = item.getType().name().toLowerCase(Locale.ROOT);
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            name += " \"" + PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()) + "\"";
        }
        return item.getAmount() + "x " + name;
    }

    private static boolean isVanished(Player p) {
        return p.getMetadata("vanished").stream().anyMatch(v -> v.asBoolean());
    }

    private static String fmt(Location l) {
        if (l == null || l.getWorld() == null) {
            return "?";
        }
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private static void msg(Player p, String text, NamedTextColor color) {
        p.sendMessage(Component.text("[Duty] ", NamedTextColor.AQUA).append(Component.text(text, color)));
    }
}
