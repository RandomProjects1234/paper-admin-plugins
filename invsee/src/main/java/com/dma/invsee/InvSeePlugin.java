package com.dma.invsee;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Opens another player's inventory or ender chest as a live view.
 *
 * <p>The view is the real inventory, so edits apply instantly - which is why editing is gated
 * behind its own permission and can be switched off entirely in the config.
 */
public final class InvSeePlugin extends JavaPlugin implements Listener {

    /** viewer -> target currently being inspected. */
    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<>();

    private boolean readOnly;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readOnly = getConfig().getBoolean("read-only", false);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Enabled." + (readOnly ? " Views are read-only." : ""));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(Component.text("Only a player can open an inventory view.", NamedTextColor.RED));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(Component.text("Usage: /" + label + " <player>", NamedTextColor.RED));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(Component.text(args[0] + " isn't online.", NamedTextColor.RED)
                    .append(Component.newline())
                    .append(Component.text("Only online players can be inspected.", NamedTextColor.GRAY)));
            return true;
        }

        boolean ender = command.getName().equalsIgnoreCase("endersee");
        viewer.openInventory(ender ? target.getEnderChest() : target.getInventory());
        sessions.put(viewer.getUniqueId(), target.getUniqueId());

        Component what = Component.text(ender ? "ender chest" : "inventory", NamedTextColor.WHITE);
        viewer.sendMessage(Component.text("Viewing ", NamedTextColor.GRAY)
                .append(Component.text(target.getName(), NamedTextColor.WHITE))
                .append(Component.text("'s ", NamedTextColor.GRAY))
                .append(what)
                .append(canEdit(viewer)
                        ? Component.text(" (changes are live)", NamedTextColor.YELLOW)
                        : Component.text(" (read-only)", NamedTextColor.GRAY)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return Collections.emptyList();
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed))
                .toList();
    }

    private boolean canEdit(Player viewer) {
        return !readOnly && viewer.hasPermission("invsee.edit");
    }

    // ---------------------------------------------------------------- guards

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        if (!sessions.containsKey(viewer.getUniqueId())) {
            return;
        }
        // Clicks in the viewer's own inventory (the bottom half of the screen) are always theirs.
        if (event.getClickedInventory() != null
                && event.getClickedInventory().equals(viewer.getInventory())
                && !isInspectingSelf(viewer)) {
            return;
        }
        if (!canEdit(viewer)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }
        if (sessions.containsKey(viewer.getUniqueId()) && !canEdit(viewer)) {
            event.setCancelled(true);
        }
    }

    private boolean isInspectingSelf(Player viewer) {
        return viewer.getUniqueId().equals(sessions.get(viewer.getUniqueId()));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID gone = event.getPlayer().getUniqueId();
        sessions.remove(gone);
        // Close anyone who was looking at the player that just left - the view would go stale.
        for (Map.Entry<UUID, UUID> entry : sessions.entrySet()) {
            if (gone.equals(entry.getValue())) {
                Player viewer = Bukkit.getPlayer(entry.getKey());
                if (viewer != null) {
                    viewer.closeInventory();
                    viewer.sendMessage(Component.text(event.getPlayer().getName()
                            + " logged out, closing the view.", NamedTextColor.GRAY));
                }
            }
        }
    }
}
