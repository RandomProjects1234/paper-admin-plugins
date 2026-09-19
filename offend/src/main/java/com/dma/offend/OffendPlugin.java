package com.dma.offend;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary bans measured in minutes.
 *
 * <p>Bans are kept in the plugin's own bans.yml rather than the vanilla ban list, so that a ban can
 * be placed on a player who has never joined (matched by name until their UUID is seen) and so the
 * expiry is enforced on login rather than needing a scheduled unban.
 */
public final class OffendPlugin extends JavaPlugin implements Listener {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final long MAX_MINUTES = 525_600L; // one year

    private final Map<UUID, Ban> byUuid = new ConcurrentHashMap<>();
    private final Map<String, Ban> byName = new ConcurrentHashMap<>();

    private File bansFile;

    @Override
    public void onEnable() {
        bansFile = new File(getDataFolder(), "bans.yml");
        loadBans();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Enabled with " + byName.size() + " active ban(s).");
    }

    // ---------------------------------------------------------------- commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "offend" -> {
                return doOffend(sender, args);
            }
            case "unoffend" -> {
                return doUnoffend(sender, args);
            }
            case "offends" -> {
                return doList(sender);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean doOffend(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /offend <player> <minutes> [reason]", NamedTextColor.RED));
            return true;
        }

        String targetName = args[0];
        long minutes;
        try {
            minutes = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            sender.sendMessage(Component.text("\"" + args[1] + "\" is not a number of minutes.", NamedTextColor.RED));
            return true;
        }
        if (minutes <= 0) {
            sender.sendMessage(Component.text("Minutes must be greater than 0.", NamedTextColor.RED));
            return true;
        }
        if (minutes > MAX_MINUTES) {
            sender.sendMessage(Component.text("That's over a year - cap is " + MAX_MINUTES + " minutes.", NamedTextColor.RED));
            return true;
        }

        String reason = args.length > 2
                ? String.join(" ", java.util.Arrays.copyOfRange(args, 2, args.length))
                : "No reason given";

        Player online = Bukkit.getPlayerExact(targetName);
        if (online != null) {
            targetName = online.getName(); // normalise capitalisation
            if (online.hasPermission("offend.exempt")) {
                sender.sendMessage(Component.text(targetName + " is exempt from being offended.", NamedTextColor.RED));
                return true;
            }
        }

        long until = System.currentTimeMillis() + minutes * 60_000L;
        Ban ban = new Ban(targetName, online != null ? online.getUniqueId() : null, until, reason,
                sender.getName(), System.currentTimeMillis());
        store(ban);
        saveBansAsync();

        if (online != null) {
            online.kick(banScreen(ban));
        }

        sender.sendMessage(Component.text("Offended " + ban.name() + " for " + formatDuration(minutes * 60_000L)
                + " (until " + WHEN.format(Instant.ofEpochMilli(until)) + ")", NamedTextColor.GREEN)
                .append(Component.newline())
                .append(Component.text("Reason: " + reason, NamedTextColor.GRAY)));
        getLogger().info(sender.getName() + " offended " + ban.name() + " for " + minutes + "m: " + reason);
        return true;
    }

    private boolean doUnoffend(CommandSender sender, String[] args) {
        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /unoffend <player>", NamedTextColor.RED));
            return true;
        }
        Ban removed = remove(args[0]);
        if (removed == null) {
            sender.sendMessage(Component.text(args[0] + " is not currently offended.", NamedTextColor.YELLOW));
            return true;
        }
        saveBansAsync();
        sender.sendMessage(Component.text("Pardoned " + removed.name() + ".", NamedTextColor.GREEN));
        getLogger().info(sender.getName() + " pardoned " + removed.name());
        return true;
    }

    private boolean doList(CommandSender sender) {
        purgeExpired();
        List<Ban> active = new ArrayList<>(byName.values());
        if (active.isEmpty()) {
            sender.sendMessage(Component.text("Nobody is currently offended.", NamedTextColor.GRAY));
            return true;
        }
        active.sort((a, b) -> Long.compare(a.until(), b.until()));
        sender.sendMessage(Component.text(active.size() + " active ban(s):", NamedTextColor.GOLD));
        long now = System.currentTimeMillis();
        for (Ban ban : active) {
            sender.sendMessage(Component.text("  " + ban.name(), NamedTextColor.WHITE)
                    .append(Component.text(" - " + formatDuration(ban.until() - now) + " left", NamedTextColor.YELLOW))
                    .append(Component.text(" - " + ban.reason(), NamedTextColor.GRAY)));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("offend")) {
            if (args.length == 1) {
                return partial(args[0], Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
            }
            if (args.length == 2) {
                return partial(args[1], List.of("5", "10", "30", "60", "120", "1440"));
            }
        } else if (name.equals("unoffend") && args.length == 1) {
            return partial(args[0], byName.values().stream().map(Ban::name).toList());
        }
        return Collections.emptyList();
    }

    private static List<String> partial(String typed, List<String> options) {
        String lower = typed.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    // ---------------------------------------------------------------- enforcement

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        Ban ban = byUuid.get(event.getUniqueId());
        if (ban == null) {
            ban = byName.get(event.getName().toLowerCase(Locale.ROOT));
        }
        if (ban == null) {
            return;
        }
        if (ban.expired()) {
            remove(ban.name());
            saveBansAsync();
            return;
        }
        // First time we've seen this name attached to a UUID - remember it so a name change
        // can't shake the ban off.
        if (ban.uuid() == null) {
            Ban bound = ban.withUuid(event.getUniqueId());
            store(bound);
            saveBansAsync();
            ban = bound;
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(ban));
    }

    private Component banScreen(Ban ban) {
        return Component.text("You are temporarily banned", NamedTextColor.RED)
                .append(Component.newline())
                .append(Component.newline())
                .append(Component.text("Reason: ", NamedTextColor.GRAY))
                .append(Component.text(ban.reason(), NamedTextColor.WHITE))
                .append(Component.newline())
                .append(Component.text("Time left: ", NamedTextColor.GRAY))
                .append(Component.text(formatDuration(ban.until() - System.currentTimeMillis()), NamedTextColor.YELLOW))
                .append(Component.newline())
                .append(Component.text("Expires: ", NamedTextColor.GRAY))
                .append(Component.text(WHEN.format(Instant.ofEpochMilli(ban.until())), NamedTextColor.WHITE));
    }

    // ---------------------------------------------------------------- storage

    private void store(Ban ban) {
        byName.put(ban.name().toLowerCase(Locale.ROOT), ban);
        if (ban.uuid() != null) {
            byUuid.put(ban.uuid(), ban);
        }
    }

    private Ban remove(String name) {
        Ban removed = byName.remove(name.toLowerCase(Locale.ROOT));
        if (removed != null && removed.uuid() != null) {
            byUuid.remove(removed.uuid());
        }
        return removed;
    }

    private void purgeExpired() {
        boolean changed = false;
        for (Ban ban : new ArrayList<>(byName.values())) {
            if (ban.expired()) {
                remove(ban.name());
                changed = true;
            }
        }
        if (changed) {
            saveBansAsync();
        }
    }

    private void loadBans() {
        byName.clear();
        byUuid.clear();
        if (!bansFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(bansFile);
        ConfigurationSection root = yaml.getConfigurationSection("bans");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            String name = s.getString("name", key);
            long until = s.getLong("until");
            if (until <= System.currentTimeMillis()) {
                continue; // already expired - drop it on load
            }
            UUID uuid = null;
            String rawUuid = s.getString("uuid");
            if (rawUuid != null && !rawUuid.isBlank()) {
                try {
                    uuid = UUID.fromString(rawUuid);
                } catch (IllegalArgumentException ignored) {
                    // corrupt entry, fall back to name matching
                }
            }
            store(new Ban(name, uuid, until, s.getString("reason", "No reason given"),
                    s.getString("by", "unknown"), s.getLong("at")));
        }
    }

    /** bans.yml is small; writing it off the main thread keeps login handling snappy. */
    private void saveBansAsync() {
        List<Ban> snapshot = new ArrayList<>(byName.values());
        Runnable write = () -> {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Ban ban : snapshot) {
                String key = ban.name().toLowerCase(Locale.ROOT);
                yaml.set("bans." + key + ".name", ban.name());
                yaml.set("bans." + key + ".uuid", ban.uuid() == null ? null : ban.uuid().toString());
                yaml.set("bans." + key + ".until", ban.until());
                yaml.set("bans." + key + ".reason", ban.reason());
                yaml.set("bans." + key + ".by", ban.by());
                yaml.set("bans." + key + ".at", ban.at());
            }
            try {
                if (!getDataFolder().isDirectory() && !getDataFolder().mkdirs()) {
                    throw new IOException("could not create " + getDataFolder());
                }
                yaml.save(bansFile);
            } catch (IOException e) {
                getLogger().severe("Could not save bans.yml: " + e.getMessage());
            }
        };

        if (isEnabled()) {
            getServer().getScheduler().runTaskAsynchronously(this, write);
        } else {
            write.run(); // shutting down - the scheduler is gone, just write it
        }
    }

    @Override
    public void onDisable() {
        saveBansAsync();
    }

    // ---------------------------------------------------------------- helpers

    /** "2h 30m", "45m", "3d 4h" - always something readable, never "0m". */
    static String formatDuration(long millis) {
        long totalMinutes = Math.max(1, millis / 60_000L);
        long days = totalMinutes / 1440;
        long hours = (totalMinutes % 1440) / 60;
        long minutes = totalMinutes % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) {
            sb.append(days).append("d ");
        }
        if (hours > 0) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0 || sb.isEmpty()) {
            sb.append(minutes).append("m");
        }
        return sb.toString().trim();
    }

    record Ban(String name, UUID uuid, long until, String reason, String by, long at) {
        boolean expired() {
            return System.currentTimeMillis() >= until;
        }

        Ban withUuid(UUID id) {
            return new Ban(name, id, until, reason, by, at);
        }
    }
}
