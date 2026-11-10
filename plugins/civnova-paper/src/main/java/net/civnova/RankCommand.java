package net.civnova;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.user.User;
import net.luckperms.api.track.DemotionResult;
import net.luckperms.api.track.PromotionResult;
import net.luckperms.api.track.Track;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** /promote and /demote on a LuckPerms track, without needing LuckPerms permissions. */
final class RankCommand {

    private RankCommand() {
    }

    static void run(CivNovaPlugin plugin, CommandSender sender, String[] args, boolean promote) {
        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /" + (promote ? "promote" : "demote") + " <player> [track]", NamedTextColor.RED));
            return;
        }
        if (Bukkit.getPluginManager().getPlugin("LuckPerms") == null) {
            sender.sendMessage(Component.text("LuckPerms is not installed.", NamedTextColor.RED));
            return;
        }
        String name = args[0];
        String trackName = args.length > 1 ? args[1] : plugin.getConfig().getString("rank-track", "staff");
        LuckPerms lp = LuckPermsProvider.get();
        Track track = lp.getTrackManager().getTrack(trackName);
        if (track == null) {
            sender.sendMessage(Component.text("Track " + trackName + " does not exist.", NamedTextColor.RED));
            return;
        }
        lp.getUserManager().lookupUniqueId(name).thenAccept(uuid -> {
            if (uuid == null) {
                reply(plugin, sender, Component.text("Unknown player " + name + ".", NamedTextColor.RED));
                return;
            }
            if (sender instanceof Player p && p.getUniqueId().equals(uuid)) {
                reply(plugin, sender, Component.text("You cannot change your own rank.", NamedTextColor.RED));
                return;
            }
            change(plugin, lp, track, uuid, name, sender, promote);
        });
    }

    private static void change(CivNovaPlugin plugin, LuckPerms lp, Track track, UUID uuid, String name, CommandSender sender, boolean promote) {
        lp.getUserManager().loadUser(uuid).thenAccept(user -> {
            Component result;
            if (promote) {
                PromotionResult r = track.promote(user, ImmutableContextSet.empty());
                result = r.wasSuccessful()
                    ? Component.text(name + " is now " + r.getGroupTo().orElse("?") + ".", NamedTextColor.GREEN)
                    : Component.text("Could not promote " + name + ": " + r.getStatus(), NamedTextColor.RED);
                if (r.wasSuccessful()) {
                    save(lp, user, plugin, sender, name, "promoted to " + r.getGroupTo().orElse("?"));
                }
            } else {
                DemotionResult r = track.demote(user, ImmutableContextSet.empty());
                result = r.wasSuccessful()
                    ? Component.text(name + " is now " + r.getGroupTo().orElse("a normal player") + ".", NamedTextColor.YELLOW)
                    : Component.text("Could not demote " + name + ": " + r.getStatus(), NamedTextColor.RED);
                if (r.wasSuccessful()) {
                    save(lp, user, plugin, sender, name, "demoted to " + r.getGroupTo().orElse("default"));
                }
            }
            reply(plugin, sender, result);
        });
    }

    private static void save(LuckPerms lp, User user, CivNovaPlugin plugin, CommandSender sender, String name, String what) {
        lp.getUserManager().saveUser(user);
        plugin.getLogger().info(sender.getName() + " " + what.replace("to ", name + " to "));
    }

    private static void reply(CivNovaPlugin plugin, CommandSender sender, Component msg) {
        Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(msg));
    }
}
