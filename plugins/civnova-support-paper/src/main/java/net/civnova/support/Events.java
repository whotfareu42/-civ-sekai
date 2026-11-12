package net.civnova.support;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Server events (x2 essence, ore rush, ...) that admins start from the admin menu. */
public final class Events implements Listener {

    public enum Type {
        LOGIN_ESSENCE("Daily Essence", Material.ENDER_EYE, "Daily playtime essence reward"),
        VOTE_ESSENCE("Vote Essence", Material.WRITABLE_BOOK, "Essence reward for voting"),
        ORE_RUSH("Ore Rush", Material.DIAMOND_ORE, "Hidden ore drops while mining"),
        FOSSIL_FEVER("Fossil Fever", Material.BONE_BLOCK, "Fossils found while mining"),
        FISHING_FRENZY("Fishing Frenzy", Material.FISHING_ROD, "Everything caught while fishing"),
        MOB_LOOT("Monster Loot", Material.ROTTEN_FLESH, "Drops from mobs killed by players"),
        EMERALD_RUSH("Emerald Rush", Material.EMERALD, "XP from emeralds and XP bottles"),
        FACTORY_OVERDRIVE("Factory Overdrive", Material.BLAST_FURNACE, "Factory speed (less fuel per recipe)"),
        GROWTH_SPURT("Growth Spurt", Material.WHEAT, "Crop, tree and animal growth speed");

        final String title;
        final Material icon;
        final String what;

        Type(String title, Material icon, String what) {
            this.title = title;
            this.icon = icon;
            this.what = what;
        }
    }

    static final int MIN_MULT = 1;
    static final int MAX_MULT = 10;
    static final long STEP = 30L * 60_000L;          // 30 minutes
    static final long BIG_STEP = 6L * 60L * 60_000L; // 6 hours (shift-click)
    static final long MIN_DURATION = STEP;
    static final long MAX_DURATION = 7L * 24L * 60L * 60_000L;

    static final class State {
        int mult = 2;
        long duration = 2L * 60L * 60_000L;
        long endsAt; // 0 = not running

        boolean active() {
            return endsAt > System.currentTimeMillis();
        }
    }

    private final SupportPlugin plugin;
    private final File file;
    private final Map<Type, State> states = new EnumMap<>(Type.class);

    Events(SupportPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "events.yml");
        for (Type t : Type.values()) {
            states.put(t, new State());
        }
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 100L);
        if (Bukkit.getPluginManager().isPluginEnabled("HiddenOre")) {
            try {
                Bukkit.getPluginManager().registerEvents(new HiddenOreHook(this), plugin);
            } catch (Throwable t) {
                plugin.getLogger().warning("Could not hook HiddenOre for events: " + t);
            }
        }
        pushBoosts();
    }

    State state(Type t) {
        return states.get(t);
    }

    /** Current multiplier, 1 if the event is not running. */
    int mult(Type t) {
        State s = states.get(t);
        return s.active() ? s.mult : 1;
    }

    // ------------------------------------------------------------------ control

    void toggle(Player admin, Type t) {
        State s = states.get(t);
        if (s.active()) {
            s.endsAt = 0;
            announce(Component.text(t.title + " has ended.", NamedTextColor.GOLD));
            plugin.trackerLog(admin.getName(), "EVENT STOP " + t.name());
        } else {
            s.endsAt = System.currentTimeMillis() + s.duration;
            announce(Component.text(t.title + " x" + s.mult + " is live for " + time(s.duration) + "! ", NamedTextColor.GOLD)
                .append(Component.text(t.what + " x" + s.mult + ". See /events", NamedTextColor.YELLOW)));
            plugin.trackerLog(admin.getName(), "EVENT START " + t.name() + " x" + s.mult + " for " + time(s.duration));
        }
        save();
        pushBoosts();
    }

    void changeMult(Player admin, Type t, int delta) {
        State s = states.get(t);
        int old = s.mult;
        s.mult = Math.max(MIN_MULT, Math.min(MAX_MULT, s.mult + delta));
        if (s.mult != old && s.active()) {
            announce(Component.text(t.title + " is now x" + s.mult + ".", NamedTextColor.GOLD));
            plugin.trackerLog(admin.getName(), "EVENT " + t.name() + " multiplier x" + old + " -> x" + s.mult);
        }
        save();
        pushBoosts();
    }

    void changeDuration(Player admin, Type t, long delta) {
        State s = states.get(t);
        if (s.active()) {
            long now = System.currentTimeMillis();
            long left = Math.max(MIN_DURATION, Math.min(MAX_DURATION, s.endsAt - now + delta));
            s.endsAt = now + left;
            plugin.trackerLog(admin.getName(), "EVENT " + t.name() + " now ends in " + time(left));
        } else {
            s.duration = Math.max(MIN_DURATION, Math.min(MAX_DURATION, s.duration + delta));
        }
        save();
    }

    private void tick() {
        boolean changed = false;
        for (Type t : Type.values()) {
            State s = states.get(t);
            if (s.endsAt != 0 && !s.active()) {
                s.endsAt = 0;
                changed = true;
                announce(Component.text(t.title + " has ended.", NamedTextColor.GOLD));
            }
        }
        if (changed) {
            save();
            pushBoosts();
        }
    }

    /** Hands the multipliers to the Civ plugins that apply them. */
    private void pushBoosts() {
        push("EssenceGlue", () -> {
            com.github.maxopoly.essenceglue.RewardManager.loginMultiplier = mult(Type.LOGIN_ESSENCE);
            com.github.maxopoly.essenceglue.RewardManager.voteMultiplier = mult(Type.VOTE_ESSENCE);
        });
        push("SimpleAdminHacks", () ->
            com.programmerdan.minecraft.simpleadminhacks.hacks.basic.OldEnchanting.expMultiplier = mult(Type.EMERALD_RUSH));
        push("FactoryMod", () ->
            com.github.igotyou.FactoryMod.factories.FurnCraftChestFactory.speedMultiplier = mult(Type.FACTORY_OVERDRIVE));
        push("RealisticBiomes", () ->
            com.untamedears.realisticbiomes.growthconfig.PlantGrowthConfig.growthMultiplier = mult(Type.GROWTH_SPURT));
    }

    private void push(String pluginName, Runnable r) {
        if (!Bukkit.getPluginManager().isPluginEnabled(pluginName)) {
            return;
        }
        try {
            r.run();
        } catch (Throwable t) {
            plugin.getLogger().warning("Could not set " + pluginName + " event multiplier: " + t);
        }
    }

    private static void announce(Component text) {
        Bukkit.broadcast(Component.text("[Event] ", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
            .append(text.decoration(TextDecoration.BOLD, false)));
    }

    // ------------------------------------------------------------------ /events

    void listFor(org.bukkit.command.CommandSender sender) {
        List<Component> lines = new ArrayList<>();
        for (Type t : Type.values()) {
            State s = states.get(t);
            if (s.active()) {
                lines.add(Component.text(" " + t.title + " x" + s.mult, NamedTextColor.GOLD)
                    .append(Component.text(" - " + t.what + ", " + time(s.endsAt - System.currentTimeMillis()) + " left", NamedTextColor.GRAY)));
            }
        }
        if (lines.isEmpty()) {
            sender.sendMessage(Component.text("No events are running right now. Watch Discord for announcements.", NamedTextColor.GRAY));
            return;
        }
        sender.sendMessage(Component.text("Running events:", NamedTextColor.LIGHT_PURPLE));
        lines.forEach(sender::sendMessage);
    }

    static String time(long ms) {
        long min = Math.max(0, ms) / 60_000L;
        long d = min / (24 * 60);
        long h = (min / 60) % 24;
        long m = min % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("d ");
        }
        if (h > 0) {
            sb.append(h).append("h ");
        }
        if (m > 0 || sb.isEmpty()) {
            sb.append(m).append("m");
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ boosts

    /** Multiplies stackable items only, so gear and enchanted books are never duplicated. */
    static List<ItemStack> multiply(List<ItemStack> items, int mult) {
        if (mult <= 1) {
            return items;
        }
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack it : items) {
            if (it == null || it.getType().isAir() || it.getMaxStackSize() <= 1) {
                out.add(it);
                continue;
            }
            int total = it.getAmount() * mult;
            while (total > 0) {
                ItemStack copy = it.clone();
                int n = Math.min(total, it.getMaxStackSize());
                copy.setAmount(n);
                out.add(copy);
                total -= n;
            }
        }
        return out;
    }

    static boolean isFossil(ItemStack it) {
        if (it == null || !it.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = it.getItemMeta();
        Component name = meta.hasDisplayName() ? meta.displayName() : meta.hasCustomName() ? meta.customName() : null;
        return name != null && PlainTextComponentSerializer.plainText().serialize(name).toLowerCase(Locale.ROOT).contains("fossil");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        int mult = mult(Type.FISHING_FRENZY);
        if (mult <= 1 || event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) {
            return;
        }
        ItemStack stack = item.getItemStack();
        if (stack.getMaxStackSize() <= 1) {
            return;
        }
        List<ItemStack> all = multiply(List.of(stack), mult);
        item.setItemStack(all.get(0));
        for (int i = 1; i < all.size(); i++) {
            event.getPlayer().getInventory().addItem(all.get(i)).values()
                .forEach(rest -> event.getPlayer().getWorld().dropItem(event.getPlayer().getLocation(), rest));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobDeath(EntityDeathEvent event) {
        int mult = mult(Type.MOB_LOOT);
        LivingEntity e = event.getEntity();
        if (mult <= 1 || e instanceof Player || e.getKiller() == null) {
            return;
        }
        List<ItemStack> boosted = multiply(new ArrayList<>(event.getDrops()), mult);
        event.getDrops().clear();
        event.getDrops().addAll(boosted);
    }

    /** Separate class so the plugin still loads when HiddenOre is missing. */
    static final class HiddenOreHook implements Listener {
        private final Events events;

        HiddenOreHook(Events events) {
            this.events = events;
        }

        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onOre(com.github.devotedmc.hiddenore.events.HiddenOreEvent event) {
            int ore = events.mult(Type.ORE_RUSH);
            int fossil = events.mult(Type.FOSSIL_FEVER);
            if (ore <= 1 && fossil <= 1) {
                return;
            }
            List<ItemStack> out = new ArrayList<>();
            for (ItemStack it : event.getDrops()) {
                out.addAll(multiply(List.of(it), isFossil(it) ? fossil : ore));
            }
            event.setDrops(out);
        }
    }

    // ------------------------------------------------------------------ storage

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Type t : Type.values()) {
            String k = t.name().toLowerCase(Locale.ROOT);
            State s = states.get(t);
            s.mult = Math.max(MIN_MULT, Math.min(MAX_MULT, y.getInt(k + ".multiplier", s.mult)));
            s.duration = Math.max(MIN_DURATION, Math.min(MAX_DURATION, y.getLong(k + ".duration", s.duration)));
            s.endsAt = y.getLong(k + ".ends-at", 0L);
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Type t : Type.values()) {
            String k = t.name().toLowerCase(Locale.ROOT);
            State s = states.get(t);
            y.set(k + ".multiplier", s.mult);
            y.set(k + ".duration", s.duration);
            y.set(k + ".ends-at", s.active() ? s.endsAt : 0L);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save events.yml: " + ex.getMessage());
        }
    }
}
