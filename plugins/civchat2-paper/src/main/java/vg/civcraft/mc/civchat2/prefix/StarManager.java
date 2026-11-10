package vg.civcraft.mc.civchat2.prefix;

import com.programmerdan.minecraft.banstick.data.BSPlayer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.event.HoverEventSource;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import vg.civcraft.mc.civchat2.CivChat2;

public class StarManager {

    private static final String STAR = "⋆";
    private static final String MOON = "☾";
    private static final String MOON_PREFIX_PERMISSION = "civchat.prefix.moon";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("d MMM uuuu");

    private final boolean playtimeStars;

    public StarManager(boolean playtimeStars) {
        this.playtimeStars = playtimeStars;
    }

    private long getJoined(Player player) {
        if (!Bukkit.getPluginManager().isPluginEnabled("BanStick")) {
            return player.getFirstPlayed();
        }

        BSPlayer bsPlayer = BSPlayer.byUUID(player.getUniqueId());
        if (bsPlayer == null) {
            return 0;
        }
        return bsPlayer.getFirstAdd().toInstant().toEpochMilli();
    }

    // Civitas Nova: staff icon (style B) and playtime icons (style A)
    private static final String STAFF = "\u2726";       // ✦
    private static final String MONTH = "\u25C7";       // ◇  1+ month
    private static final String HALF_YEAR = "\u25C6";   // ◆  6+ months
    private static final String YEAR = "\u2726";        // ✦  per full year

    private String rankName(Player player) {
        if (player.hasPermission("civchat.admin")) {
            return "Admin";
        } else if (player.hasPermission("civchat.mod")) {
            return "Moderator";
        } else if (player.hasPermission("civchat.helper")) {
            return "Helper";
        }
        return "Player";
    }

    public HoverEventSource<?> hover(Player player) {
        long firstPlayed = getJoined(player);
        String joined = firstPlayed == 0 ? "unknown" : FORMATTER.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(firstPlayed), ZoneId.systemDefault()));
        String text = "Rank: " + rankName(player) + "\nJoined: " + joined;
        if (player.hasPermission("rankedpvpstar")) {
            text += "\nTop 10 in ranked PvP";
        }
        return HoverEvent.showText(Component.text(text));
    }

    public String getPrefix(Player player) {
        if (!CivChat2.getInstance().getCivChat2SettingsManager().isShowPrefix(player.getUniqueId())) {
            return "";
        }
        if (player.hasPermission("civchat.admin")) {
            return ChatColor.RED + STAFF;
        } else if (player.hasPermission("civchat.mod")) {
            return ChatColor.BLUE + STAFF;
        } else if (player.hasPermission("civchat.helper")) {
            return ChatColor.GREEN + STAFF;
        }

        final StringBuilder rendered = new StringBuilder();
        if (playtimeStars && CivChat2.getInstance().getCivChat2SettingsManager().isShowStars(player.getUniqueId())) {
            long firstPlayed = getJoined(player);
            if (firstPlayed != 0) {
                LocalDateTime start = LocalDateTime.ofInstant(Instant.ofEpochMilli(firstPlayed), ZoneId.systemDefault());
                long months = start.until(LocalDateTime.now(), ChronoUnit.MONTHS);
                if (months >= 12) {
                    rendered.append(ChatColor.GOLD).append(YEAR.repeat((int) Math.min(5, months / 12)));
                } else if (months >= 6) {
                    rendered.append(ChatColor.YELLOW).append(HALF_YEAR);
                } else if (months >= 1) {
                    rendered.append(ChatColor.GRAY).append(MONTH);
                }
            }
        }
        if (player.hasPermission("rankedpvpstar")
            && CivChat2.getInstance().getCivChat2SettingsManager().isShowPvpStarPrefix(player.getUniqueId())) {
            rendered.append(ChatColor.LIGHT_PURPLE).append(STAR);
        }
        if (player.hasPermission(MOON_PREFIX_PERMISSION)
            && CivChat2.getInstance().getCivChat2SettingsManager().isShowCustomPrefixes(player.getUniqueId())) {
            rendered.append(ChatColor.GOLD).append(MOON);
        }
        return rendered.toString();
    }
}
