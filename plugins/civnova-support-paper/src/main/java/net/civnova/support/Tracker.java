package net.civnova.support;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;

/**
 * Writes staff actions to daily log files, keeps the newest entries in memory for /tracker
 * and optionally forwards them to a Discord webhook.
 */
public final class Tracker {

    public record Entry(LocalDateTime time, String actor, String action) {
        String format() {
            return TIME.format(time) + " " + actor + ": " + action;
        }

        String time() {
            return TIME.format(time);
        }

        /** Action text with world coordinates removed, for the public view. */
        String publicAction() {
            return COORDS.matcher(action).replaceAll("[coords hidden]");
        }
    }

    private static final java.util.regex.Pattern COORDS = java.util.regex.Pattern.compile("[A-Za-z0-9_]+ -?\\d+ -?\\d+ -?\\d+");

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MEMORY = 5000;

    private final SupportPlugin plugin;
    private final File dir;
    private final Deque<Entry> recent = new ArrayDeque<>();
    private final List<String> webhookQueue = new ArrayList<>();
    private final HttpClient http = HttpClient.newHttpClient();

    public Tracker(SupportPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "tracker");
        this.dir.mkdirs();
        loadRecent();
        // flush webhook queue every 5 seconds so Discord rate limits are not hit
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::flushWebhook, 100L, 100L);
    }

    public synchronized void log(String actor, String action) {
        Entry e = new Entry(LocalDateTime.now(), actor, action);
        recent.addLast(e);
        while (recent.size() > MEMORY) {
            recent.removeFirst();
        }
        File f = new File(dir, LocalDate.now() + ".log");
        try (Writer w = new FileWriter(f, StandardCharsets.UTF_8, true)) {
            w.write(e.format());
            w.write('\n');
        } catch (IOException ex) {
            plugin.getLogger().warning("Tracker could not write log: " + ex.getMessage());
        }
        if (!webhookUrl().isEmpty()) {
            webhookQueue.add(e.format());
        }
    }

    /** Load the last 7 days from disk so the GUI survives restarts. */
    private void loadRecent() {
        for (int d = 6; d >= 0; d--) {
            File f = new File(dir, LocalDate.now().minusDays(d) + ".log");
            if (!f.isFile()) {
                continue;
            }
            try {
                for (String line : java.nio.file.Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    if (line.length() < 21) {
                        continue;
                    }
                    int colon = line.indexOf(": ", 20);
                    if (colon < 0) {
                        continue;
                    }
                    try {
                        LocalDateTime t = LocalDateTime.parse(line.substring(0, 19), TIME);
                        recent.addLast(new Entry(t, line.substring(20, colon), line.substring(colon + 2)));
                    } catch (Exception ignored) {
                        // skip malformed line
                    }
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("Tracker could not read " + f.getName());
            }
        }
        while (recent.size() > MEMORY) {
            recent.removeFirst();
        }
    }

    /** Distinct actors, most recently active first. */
    public synchronized List<String> actors() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        var it = recent.descendingIterator();
        while (it.hasNext()) {
            out.add(it.next().actor());
        }
        return new ArrayList<>(out);
    }

    /** Newest first. */
    public synchronized List<Entry> find(String actor, int skip, int limit) {
        List<Entry> out = new ArrayList<>();
        var it = recent.descendingIterator();
        int skipped = 0;
        while (it.hasNext() && out.size() < limit) {
            Entry e = it.next();
            if (actor != null && !e.actor().toLowerCase(Locale.ROOT).equals(actor.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (skipped++ < skip) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private String webhookUrl() {
        return plugin.getConfig().getString("tracker.discord-webhook", "").trim();
    }

    private void flushWebhook() {
        String url = webhookUrl();
        List<String> batch;
        synchronized (this) {
            if (webhookQueue.isEmpty() || url.isEmpty()) {
                webhookQueue.clear();
                return;
            }
            batch = new ArrayList<>(webhookQueue);
            webhookQueue.clear();
        }
        StringBuilder sb = new StringBuilder();
        for (String line : batch) {
            if (sb.length() + line.length() > 1800) {
                send(url, sb.toString());
                sb.setLength(0);
            }
            sb.append(line).append('\n');
        }
        if (!sb.isEmpty()) {
            send(url, sb.toString());
        }
    }

    private void send(String url, String text) {
        String json = "{\"content\":\"```\\n" + escape(text) + "```\",\"allowed_mentions\":{\"parse\":[]}}";
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception ex) {
            plugin.getLogger().warning("Tracker webhook failed: " + ex.getMessage());
        }
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("`", "'");
    }
}
