package net.civnova;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EnderSignal;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

public final class CivNovaPlugin extends JavaPlugin implements Listener {

    private final List<Location> portals = new ArrayList<>();
    private File portalFile;
    private Set<String> allowedPlugins;
    private Set<String> extraCommands;
    private Set<String> blockedCommands;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        portalFile = new File(getDataFolder(), "portals.yml");
        loadPortals();
        getServer().getPluginManager().registerEvents(this, this);
        applyMaxPlayers();
        getLogger().info("Loaded " + portals.size() + " known nether portals.");
    }

    private void loadSettings() {
        reloadConfig();
        allowedPlugins = lower(getConfig().getStringList("tab-filter.allowed-plugins"));
        extraCommands = lower(getConfig().getStringList("tab-filter.extra-commands"));
        blockedCommands = lower(getConfig().getStringList("tab-filter.blocked-commands"));
    }

    private static Set<String> lower(List<String> in) {
        Set<String> out = new HashSet<>();
        for (String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // ------------------------------------------------------------------ max players / help

    private void applyMaxPlayers() {
        int max = getConfig().getInt("max-players", 0);
        if (max > 0) {
            // the host resets server.properties on every start, so set it at runtime
            Bukkit.setMaxPlayers(max);
        }
    }

    @EventHandler
    public void onPing(PaperServerListPingEvent event) {
        int max = getConfig().getInt("max-players", 0);
        if (max > 0) {
            event.setMaxPlayers(max);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHelp(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage().toLowerCase(Locale.ROOT);
        String label = msg.split(" ", 2)[0];
        if (label.equals("/help") || label.equals("/?")) {
            event.setCancelled(true);
            sendDiscord(event.getPlayer());
        }
    }

    private void sendDiscord(CommandSender sender) {
        String url = getConfig().getString("discord", "");
        sender.sendMessage(Component.text("All guides and help are on our Discord: ", NamedTextColor.GOLD)
            .append(Component.text(url, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url))));
    }

    // ------------------------------------------------------------------ /promote /demote
    // NameLayer also owns /promote (/promote <group> <player> <rank>). With 1-2 arguments we
    // handle it as a staff rank change, with 3 arguments it stays NameLayer's command.

    private boolean handleRank(CommandSender sender, String line) {
        String[] parts = line.trim().split("\\s+");
        String label = parts[0].toLowerCase(Locale.ROOT);
        if (label.startsWith("/")) {
            label = label.substring(1);
        }
        boolean promote = label.equals("promote");
        if (!promote && !label.equals("demote")) {
            return false;
        }
        if (parts.length < 2 || parts.length > 3 || !sender.hasPermission("civnova.promote")) {
            return false;
        }
        RankCommand.run(this, sender, java.util.Arrays.copyOfRange(parts, 1, parts.length), promote);
        return true;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRankCommand(PlayerCommandPreprocessEvent event) {
        if (handleRank(event.getPlayer(), event.getMessage())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRankConsole(ServerCommandEvent event) {
        if (handleRank(event.getSender(), event.getCommand())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ tab filter

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandSend(PlayerCommandSendEvent event) {
        if (!getConfig().getBoolean("tab-filter.enabled", true)) {
            return;
        }
        if (event.getPlayer().hasPermission("civnova.tab.all")) {
            return;
        }
        event.getCommands().removeIf(label -> !isVisible(label));
    }

    private boolean isVisible(String label) {
        String l = label.toLowerCase(Locale.ROOT);
        if (l.contains(":")) {
            return false; // hide namespaced duplicates like citadel:ctr
        }
        if (blockedCommands.contains(l)) {
            return false;
        }
        if (extraCommands.contains(l)) {
            return true;
        }
        Command cmd = Bukkit.getCommandMap().getCommand(l);
        if (cmd instanceof PluginIdentifiableCommand pic) {
            return allowedPlugins.contains(pic.getPlugin().getName().toLowerCase(Locale.ROOT));
        }
        return false;
    }

    // ------------------------------------------------------------------ ender eyes

    private static boolean isEssence(ItemStack item) {
        if (item == null || item.getType() != Material.ENDER_EYE || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.hasDisplayName() || meta.hasCustomName() || meta.hasLore();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEyeUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.ENDER_EYE) {
            return;
        }
        if (isEssence(item)) {
            // Player Essence is pearl fuel, it must never be thrown or placed
            event.setCancelled(true);
            return;
        }
        if (!getConfig().getBoolean("portal-eyes.enabled", true)) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked != null && clicked.getType() == Material.END_PORTAL_FRAME) {
            return; // keep vanilla behaviour for end portal frames
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND && event.getHand() != EquipmentSlot.OFF_HAND) {
            return;
        }
        Player player = event.getPlayer();
        Location target = nearestPortal(player.getLocation());
        if (target == null) {
            player.sendActionBar(Component.text("The eye finds no nether portal in this world.", NamedTextColor.GRAY));
            return;
        }
        Location start = player.getEyeLocation();
        boolean drop = ThreadLocalRandom.current().nextInt(5) > 0; // vanilla: 80% chance to survive
        player.getWorld().spawn(start, EnderSignal.class, signal -> {
            signal.setTargetLocation(target);
            signal.setDropItem(drop);
            signal.setItem(new ItemStack(Material.ENDER_EYE));
        });
        player.getWorld().playSound(start, Sound.ENTITY_ENDER_EYE_LAUNCH, 0.5f, 0.4f);
        player.swingHand(event.getHand());
        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
    }

    private Location nearestPortal(Location from) {
        World world = from.getWorld();
        Location best = null;
        double bestDist = Double.MAX_VALUE;
        boolean changed = false;
        for (var it = portals.iterator(); it.hasNext(); ) {
            Location p = it.next();
            if (p.getWorld() == null || !p.getWorld().equals(world)) {
                continue;
            }
            double d = p.distanceSquared(from);
            if (d >= bestDist) {
                continue;
            }
            if (p.isChunkLoaded() && p.getBlock().getType() != Material.NETHER_PORTAL) {
                it.remove(); // portal was destroyed
                changed = true;
                continue;
            }
            best = p;
            bestDist = d;
        }
        if (changed) {
            savePortals();
        }
        return best == null ? null : best.clone().add(0.5, 0.5, 0.5);
    }

    // ------------------------------------------------------------------ portal registry

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        for (BlockState state : event.getBlocks()) {
            if (state.getType() == Material.NETHER_PORTAL) {
                addPortal(state.getLocation());
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortalUse(PlayerPortalEvent event) {
        Block b = event.getFrom().getBlock();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block r = b.getRelative(dx, dy, dz);
                    if (r.getType() == Material.NETHER_PORTAL) {
                        addPortal(r.getLocation());
                        return;
                    }
                }
            }
        }
    }

    private boolean addPortal(Location loc) {
        Location block = loc.getBlock().getLocation();
        for (Location p : portals) {
            if (p.getWorld() != null && p.getWorld().equals(block.getWorld()) && p.distanceSquared(block) < 16 * 16) {
                return false; // same portal (or one right next to it) already known
            }
        }
        portals.add(block);
        savePortals();
        return true;
    }

    private void loadPortals() {
        portals.clear();
        if (!portalFile.isFile()) {
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(portalFile);
        for (String s : yml.getStringList("portals")) {
            String[] p = s.split(",");
            if (p.length != 4) {
                continue;
            }
            World w = Bukkit.getWorld(p[0]);
            if (w == null) {
                continue;
            }
            try {
                portals.add(new Location(w, Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])));
            } catch (NumberFormatException ignored) {
                // skip broken line
            }
        }
    }

    private void savePortals() {
        YamlConfiguration yml = new YamlConfiguration();
        List<String> out = new ArrayList<>();
        for (Location p : portals) {
            out.add(p.getWorld().getName() + "," + p.getBlockX() + "," + p.getBlockY() + "," + p.getBlockZ());
        }
        yml.set("portals", out);
        try {
            yml.save(portalFile);
        } catch (IOException e) {
            getLogger().warning("Could not save portals.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("promote") || command.getName().equalsIgnoreCase("demote")) {
            RankCommand.run(this, sender, args, command.getName().equalsIgnoreCase("promote"));
            return true;
        }
        if (command.getName().equalsIgnoreCase("discord")) {
            sendDiscord(sender);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            loadSettings();
            loadPortals();
            applyMaxPlayers();
            sender.sendMessage("CivNova reloaded.");
            return true;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("portal")) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "list" -> {
                    sender.sendMessage("Known nether portals: " + portals.size());
                    for (Location p : portals) {
                        sender.sendMessage(" - " + p.getWorld().getName() + " " + p.getBlockX() + " " + p.getBlockY() + " " + p.getBlockZ());
                    }
                    return true;
                }
                case "add" -> {
                    if (!(sender instanceof Player player)) {
                        return false;
                    }
                    sender.sendMessage(addPortal(player.getLocation()) ? "Portal location added." : "A portal is already known within 16 blocks.");
                    return true;
                }
                case "remove" -> {
                    if (!(sender instanceof Player player)) {
                        return false;
                    }
                    boolean removed = portals.removeIf(p -> p.getWorld().equals(player.getWorld())
                        && p.distanceSquared(player.getLocation()) < 16 * 16);
                    savePortals();
                    sender.sendMessage(removed ? "Portal location removed." : "No known portal within 16 blocks.");
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        sender.sendMessage("/civnova reload | portal add | portal remove | portal list");
        return true;
    }
}
