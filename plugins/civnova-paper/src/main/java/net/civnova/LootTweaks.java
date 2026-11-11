package net.civnova;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;

/**
 * Civitas Nova loot changes:
 * - custom crafting recipes (netherstar shards)
 * - fishing: less junk, more treasure, extra custom catches
 * - extra drops for mobs carrying a scoreboard tag (set by MythicMobs)
 * Custom items are defined as serialized ItemStacks in config.yml (section "items"),
 * exactly like FactoryMod does it, so they are identical to factory inputs/outputs.
 */
final class LootTweaks implements Listener {

    private static final java.util.Set<Material> JUNK = java.util.EnumSet.of(
        Material.LEATHER_BOOTS, Material.LEATHER, Material.BONE, Material.POTION, Material.STRING,
        Material.BOWL, Material.STICK, Material.INK_SAC, Material.TRIPWIRE_HOOK, Material.ROTTEN_FLESH,
        Material.LILY_PAD, Material.BAMBOO);
    private static final java.util.Set<Material> FISH = java.util.EnumSet.of(
        Material.COD, Material.SALMON, Material.TROPICAL_FISH, Material.PUFFERFISH);

    private final CivNovaPlugin plugin;
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    LootTweaks(CivNovaPlugin plugin) {
        this.plugin = plugin;
        registerRecipes();
    }

    ItemStack item(String key) {
        ItemStack i = plugin.getConfig().getItemStack("items." + key);
        return i == null ? null : i.clone();
    }

    // ---------------------------------------------------------------- recipes

    void registerRecipes() {
        for (NamespacedKey k : recipeKeys) {
            Bukkit.removeRecipe(k);
        }
        recipeKeys.clear();
        ItemStack shard = item("netherstar-shard");
        if (shard == null) {
            plugin.getLogger().warning("items.netherstar-shard missing in config, recipes not registered");
            return;
        }
        NamespacedKey starKey = new NamespacedKey(plugin, "netherstar_from_shards");
        ShapedRecipe star = new ShapedRecipe(starKey, new ItemStack(Material.NETHER_STAR));
        star.shape("SSS", "SSS", "SSS");
        star.setIngredient('S', new RecipeChoice.ExactChoice(shard));
        Bukkit.addRecipe(star);
        recipeKeys.add(starKey);

        NamespacedKey shardKey = new NamespacedKey(plugin, "netherstar_shard");
        ItemStack one = shard.clone();
        one.setAmount(1);
        ShapedRecipe sr = new ShapedRecipe(shardKey, one);
        sr.shape("GGG", "GDG", "GGG");
        sr.setIngredient('G', Material.GLASS_PANE);
        sr.setIngredient('D', Material.DIAMOND);
        Bukkit.addRecipe(sr);
        recipeKeys.add(shardKey);
        Bukkit.updateRecipes();
    }

    // ---------------------------------------------------------------- fishing

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFish(PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(e.getCaught() instanceof Item caught)) {
            return;
        }
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("fishing");
        if (cfg == null || !cfg.getBoolean("enabled", true)) {
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        // 1) special catches
        ConfigurationSection special = cfg.getConfigurationSection("special");
        if (special != null) {
            double roll = r.nextDouble();
            double acc = 0;
            for (String key : special.getKeys(false)) {
                acc += special.getDouble(key + ".chance");
                if (roll < acc) {
                    ItemStack it = pick(special.getConfigurationSection(key));
                    if (it != null) {
                        caught.setItemStack(it);
                        return;
                    }
                }
            }
        }
        // 2) rebalance vanilla categories: junk -> treasure, some fish -> treasure
        Material type = caught.getItemStack().getType();
        double toTreasure = 0;
        if (JUNK.contains(type)) {
            toTreasure = cfg.getDouble("junk-to-treasure", 0.6);
        } else if (FISH.contains(type)) {
            toTreasure = cfg.getDouble("fish-to-treasure", 0.06);
        }
        if (toTreasure > 0 && r.nextDouble() < toTreasure) {
            LootTable table = Bukkit.getLootTable(NamespacedKey.minecraft("gameplay/fishing/treasure"));
            if (table != null) {
                LootContext ctx = new LootContext.Builder(caught.getLocation()).killer(e.getPlayer()).luck(0).build();
                Collection<ItemStack> loot = table.populateLoot(r, ctx);
                if (!loot.isEmpty()) {
                    caught.setItemStack(loot.iterator().next());
                }
            }
        }
    }

    /** A special entry is either "item: <key in items>" or a list "items: [keys]" (random one), plus optional amount. */
    private ItemStack pick(ConfigurationSection s) {
        if (s == null) {
            return null;
        }
        String key = s.getString("item");
        List<String> keys = s.getStringList("items");
        if (key == null && !keys.isEmpty()) {
            key = keys.get(ThreadLocalRandom.current().nextInt(keys.size()));
        }
        if (key == null) {
            return null;
        }
        ItemStack it = item(key);
        if (it == null) {
            Material m = Material.matchMaterial(key);
            it = m == null ? null : new ItemStack(m);
        }
        if (it != null) {
            int min = s.getInt("min", 1);
            int max = s.getInt("max", min);
            it.setAmount(min + ThreadLocalRandom.current().nextInt(Math.max(1, max - min + 1)));
        }
        return it;
    }

    // ---------------------------------------------------------------- tagged mob drops

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(EntityDeathEvent e) {
        LivingEntity ent = e.getEntity();
        ConfigurationSection drops = plugin.getConfig().getConfigurationSection("mob-drops");
        if (drops == null || ent.getScoreboardTags().isEmpty()) {
            return;
        }
        Player killer = ent.getKiller();
        for (String tag : ent.getScoreboardTags()) {
            ConfigurationSection mob = drops.getConfigurationSection(tag);
            if (mob == null) {
                continue;
            }
            if (mob.getBoolean("require-player-kill", true) && killer == null) {
                continue;
            }
            if (mob.getBoolean("clear-vanilla-drops", false)) {
                e.getDrops().clear();
            }
            List<Map<?, ?>> list = mob.getMapList("drops");
            for (Map<?, ?> m : list) {
                double chance = m.get("chance") instanceof Number n ? n.doubleValue() : 1.0;
                if (ThreadLocalRandom.current().nextDouble() >= chance) {
                    continue;
                }
                String key = String.valueOf(m.get("item"));
                ItemStack it = item(key);
                if (it == null) {
                    Material mat = Material.matchMaterial(key);
                    if (mat == null) {
                        plugin.getLogger().warning("mob-drops." + tag + ": unknown item " + key);
                        continue;
                    }
                    it = new ItemStack(mat);
                }
                int min = m.get("min") instanceof Number n ? n.intValue() : 1;
                int max = m.get("max") instanceof Number n ? n.intValue() : min;
                it.setAmount(min + ThreadLocalRandom.current().nextInt(Math.max(1, max - min + 1)));
                e.getDrops().add(it);
            }
        }
    }
}
