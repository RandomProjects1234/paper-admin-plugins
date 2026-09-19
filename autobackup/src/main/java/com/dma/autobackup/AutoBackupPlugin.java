package com.dma.autobackup;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Backs the server up on a timer and on demand.
 *
 * <p>Only the things that are expensive to lose are archived: the world folders, the plugins
 * directory and the server's own config files. The server jar, caches, libraries and previous
 * backups are deliberately skipped - they are large, and re-downloadable.
 */
public final class AutoBackupPlugin extends JavaPlugin {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String PREFIX = "backup-";
    private static final String SUFFIX = ".zip";

    /** Top-level files worth keeping. Anything not listed here and not a world/plugin dir is skipped. */
    private static final List<String> CONFIG_FILES = List.of(
            "server.properties", "bukkit.yml", "spigot.yml", "commands.yml", "help.yml",
            "permissions.yml", "ops.json", "whitelist.json", "banned-players.json",
            "banned-ips.json", "usercache.json", "eula.txt"
    );

    /** Never archive these - they are locks, caches or noise. */
    private static final Set<String> SKIP_NAMES = Set.of("session.lock", "backups", "cache", "libraries", "versions", "logs");

    private final AtomicBoolean running = new AtomicBoolean(false);

    private Path serverRoot;
    private Path backupsDir;
    private int keepBackups;
    private boolean includePluginJars;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        // getDataFolder() is <root>/plugins/AutoBackup - walk up twice rather than trusting the CWD.
        serverRoot = getDataFolder().toPath().toAbsolutePath().normalize().getParent().getParent();
        backupsDir = serverRoot.resolve("backups");

        int intervalMinutes = Math.max(1, getConfig().getInt("interval-minutes", 30));
        long periodTicks = intervalMinutes * 60L * 20L;
        getServer().getScheduler().runTaskTimer(this,
                () -> startBackup(getServer().getConsoleSender(), false), periodTicks, periodTicks);

        getLogger().info("Enabled. Backing up every " + intervalMinutes
                + " minutes, keeping the newest " + keepBackups + ". Manual backups: /savebackup");
    }

    private void reloadSettings() {
        keepBackups = Math.max(1, getConfig().getInt("keep-backups", 48));
        includePluginJars = getConfig().getBoolean("include-plugin-jars", true);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("savebackup")) {
            return false;
        }
        startBackup(sender, true);
        return true;
    }

    /**
     * Kicks off a backup unless one is already running. Everything that touches the world must
     * happen on the main thread; the zip itself runs async.
     */
    private void startBackup(CommandSender requester, boolean manual) {
        if (!running.compareAndSet(false, true)) {
            message(requester, Component.text("A backup is already running.", NamedTextColor.YELLOW));
            return;
        }

        message(requester, Component.text("Starting backup...", NamedTextColor.GRAY));
        long startedAt = System.currentTimeMillis();

        // Flush everything to disk, and hold auto-save off so region files don't change mid-zip.
        List<World> paused = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            if (world.isAutoSave()) {
                world.setAutoSave(false);
                paused.add(world);
            }
            world.save();
        }
        Bukkit.savePlayers();

        List<Path> sources = collectSources();
        UUID requesterId = (requester instanceof Player p) ? p.getUniqueId() : null;

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            String result;
            boolean ok = false;
            try {
                Path zip = writeArchive(sources);
                int pruned = pruneOldBackups();
                long mb = Math.max(1, Files.size(zip) / (1024 * 1024));
                long secs = Math.max(1, (System.currentTimeMillis() - startedAt) / 1000);
                result = "Backup saved: backups/" + zip.getFileName() + " (" + mb + " MB, " + secs + "s)"
                        + (pruned > 0 ? ", pruned " + pruned + " old backup" + (pruned == 1 ? "" : "s") : "");
                ok = true;
            } catch (Exception e) {
                result = "Backup FAILED: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                getLogger().severe(result);
            }

            String finalResult = result;
            boolean finalOk = ok;
            // Restoring auto-save and messaging players are both main-thread jobs.
            getServer().getScheduler().runTask(this, () -> {
                for (World world : paused) {
                    world.setAutoSave(true);
                }
                running.set(false);

                if (finalOk) {
                    getLogger().info(finalResult);
                }
                Component msg = Component.text(finalResult, finalOk ? NamedTextColor.GREEN : NamedTextColor.RED);
                if (requesterId != null) {
                    Player p = Bukkit.getPlayer(requesterId);
                    if (p != null && p.isOnline()) {
                        p.sendMessage(msg);
                    }
                } else if (manual) {
                    Bukkit.getConsoleSender().sendMessage(msg);
                }
            });
        });
    }

    /** World folders, the plugins directory, the paper config directory and the core config files. */
    private List<Path> collectSources() {
        Set<Path> sources = new LinkedHashSet<>();
        for (World world : Bukkit.getWorlds()) {
            sources.add(world.getWorldFolder().toPath().toAbsolutePath().normalize());
        }
        for (String dir : List.of("plugins", "config")) {
            Path p = serverRoot.resolve(dir);
            if (Files.isDirectory(p)) {
                sources.add(p);
            }
        }
        for (String file : CONFIG_FILES) {
            Path p = serverRoot.resolve(file);
            if (Files.isRegularFile(p)) {
                sources.add(p);
            }
        }
        return new ArrayList<>(sources);
    }

    private Path writeArchive(List<Path> sources) throws IOException {
        Files.createDirectories(backupsDir);
        Path zip = backupsDir.resolve(PREFIX + LocalDateTime.now().format(STAMP) + SUFFIX);

        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            zos.setLevel(Deflater.BEST_SPEED); // world data is bulky; speed matters more than ratio
            for (Path source : sources) {
                if (Files.isRegularFile(source)) {
                    addFile(zos, source, entryName(source));
                    continue;
                }
                try (Stream<Path> walk = Files.walk(source)) {
                    List<Path> files = walk.filter(Files::isRegularFile).filter(this::included).toList();
                    for (Path file : files) {
                        addFile(zos, file, entryName(file));
                    }
                }
            }
        } catch (IOException e) {
            Files.deleteIfExists(zip); // don't leave a half-written archive looking like a good backup
            throw e;
        }
        return zip;
    }

    private boolean included(Path path) {
        for (Path part : path) {
            if (SKIP_NAMES.contains(part.toString().toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return includePluginJars || !path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    /** Path inside the zip, relative to the server root where possible. */
    private String entryName(Path file) {
        Path abs = file.toAbsolutePath().normalize();
        Path relative = abs.startsWith(serverRoot) ? serverRoot.relativize(abs) : abs.getFileName();
        return relative.toString().replace('\\', '/');
    }

    private void addFile(ZipOutputStream zos, Path file, String name) {
        try {
            zos.putNextEntry(new ZipEntry(name));
            Files.copy(file, zos);
            zos.closeEntry();
        } catch (IOException e) {
            // A file being locked or deleted mid-backup shouldn't abort the whole archive.
            getLogger().warning("Skipped " + name + ": " + e.getMessage());
        }
    }

    /** Deletes the oldest archives beyond {@code keep-backups}. Returns how many were removed. */
    private int pruneOldBackups() throws IOException {
        if (!Files.isDirectory(backupsDir)) {
            return 0;
        }
        List<Path> archives;
        try (Stream<Path> list = Files.list(backupsDir)) {
            archives = list
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith(PREFIX) && n.endsWith(SUFFIX);
                    })
                    // timestamped names sort chronologically
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
        int excess = archives.size() - keepBackups;
        int removed = 0;
        for (int i = 0; i < excess; i++) {
            try {
                Files.deleteIfExists(archives.get(i));
                removed++;
            } catch (IOException e) {
                getLogger().warning("Could not delete old backup " + archives.get(i).getFileName() + ": " + e.getMessage());
            }
        }
        return removed;
    }

    private void message(CommandSender sender, Component component) {
        sender.sendMessage(component);
    }

    @Override
    public void onDisable() {
        // If a backup is mid-flight the async task still holds auto-save off; put it back.
        if (running.get()) {
            for (World world : Bukkit.getWorlds()) {
                world.setAutoSave(true);
            }
        }
    }
}
