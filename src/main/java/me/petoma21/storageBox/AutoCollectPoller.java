package me.petoma21.storageBox;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AutoCollectPoller implements Listener {

    private static final long PERIOD_TICKS = 10L; // 0.5s fallback tick, sped up by checkNow() when possible

    private final StorageBox plugin;
    private final Map<UUID, Map<String, Integer>> baselines = new HashMap<>();
    private BukkitTask task;

    public AutoCollectPoller(StorageBox plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::checkNow, PERIOD_TICKS, PERIOD_TICKS);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public void forget(UUID playerId) {
        baselines.remove(playerId);
    }

    /**
     * Re-synchronizes the baseline for {@code template} to the player's current actual count.
     * Call this immediately after this plugin itself adds or removes matching items from the
     * player's inventory (e.g. after a withdraw), so the next check doesn't treat that expected
     * change as a fresh external pickup.
     */
    public void resync(Player player, ItemStack template) {
        Map<String, Integer> baseline = baselines.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        baseline.put(ItemUtil.templateKey(template), countMatching(player, template));
    }

    /**
     * Resyncs baselines for EVERY one of the player's registered items to their current actual
     * count. Called whenever any inventory closes for them (see {@link #onInventoryClose}), so
     * that moving a registered item around within their own inventory, or manually taking it out
     * of a chest/shulker box/barrel, is never mistaken for an external "pickup" - even if the
     * whole open-move-close sequence happens faster than the periodic check's interval.
     */
    public void resyncAll(Player player) {
        Map<String, StorageEntry> entries = plugin.getStorageDataManager().getEntries(player.getUniqueId());
        if (entries.isEmpty()) return;
        Map<String, Integer> baseline = baselines.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        for (StorageEntry entry : entries.values()) {
            ItemStack template = entry.getTemplate();
            baseline.put(ItemUtil.templateKey(template), countMatching(player, template));
        }
    }

    /**
     * Any inventory closing (their own inventory screen, a chest, a shulker box, a barrel, a
     * crafting table, etc.) means whatever manual rearranging just happened is done - resync
     * right away rather than waiting for the next periodic tick.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            resyncAll(player);
        }
    }

    /** Runs one check pass immediately for every online player, instead of waiting for the timer. */
    public void checkNow() {
        StorageDataManager dataManager = plugin.getStorageDataManager();

        for (Player player : Bukkit.getOnlinePlayers()) {
            Map<String, StorageEntry> entries = dataManager.getEntries(player.getUniqueId());
            if (entries.isEmpty()) continue;

            Map<String, Integer> baseline = baselines.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());

            // While the player is manually browsing a chest/shulker box/barrel, don't sweep -
            // moving items out of it into their own inventory is a deliberate manual action, not
            // an external "pickup", and shouldn't be immediately vacuumed back into storage.
            // Baselines still get updated to "current" below so nothing is swept retroactively
            // once the container is closed.
            boolean browsingContainer = isViewingChestLikeInventory(player);

            for (StorageEntry entry : entries.values()) {
                ItemStack template = entry.getTemplate();
                String key = ItemUtil.templateKey(template);
                int current = countMatching(player, template);
                Integer prev = baseline.get(key);

                if (prev == null) {
                    // First time seeing this entry for this player (new registration, server
                    // restart, or the box just being brought back) - just record where things
                    // stand, don't sweep anything retroactively.
                    baseline.put(key, current);
                    continue;
                }

                boolean boxPresent = plugin.hasMatchingBoxInInventory(player, template);
                if (current > prev && entry.isAutocollect() && boxPresent && !browsingContainer) {
                    int gained = current - prev;
                    removeMatching(player, template, gained);
                    entry.addCount(gained);
                    dataManager.save(player.getUniqueId());
                    plugin.refreshMatchingBoxes(player, template, entry);
                    baseline.put(key, current - gained);
                } else {
                    baseline.put(key, current);
                }
            }
        }
    }

    /** True if the player currently has a chest, trapped chest, shulker box, or barrel inventory open. */
    private boolean isViewingChestLikeInventory(Player player) {
        InventoryType type = player.getOpenInventory().getTopInventory().getType();
        return type == InventoryType.CHEST || type == InventoryType.SHULKER_BOX || type == InventoryType.BARREL;
    }

    /**
     * Counts matching items in the inventory PLUS whatever is currently on the player's cursor.
     * The cursor slot must be included: while dragging an item between inventory slots (even
     * within the player's own inventory screen), it briefly sits on the cursor and disappears
     * from getStorageContents() for a moment - without counting it here, that momentary dip
     * would look like a "decrease" followed by an "increase" on the next check, which would
     * incorrectly trigger autocollect for an item the player never actually acquired.
     */
    private int countMatching(Player player, ItemStack template) {
        int total = 0;
        for (ItemStack it : player.getInventory().getStorageContents()) {
            if (it == null || it.getType() == Material.AIR) continue;
            if (plugin.getItemUtil().isStorageBox(it)) continue;
            if (!it.isSimilar(template)) continue;
            total += it.getAmount();
        }

        ItemStack cursor = player.getOpenInventory().getCursor();
        if (cursor != null && cursor.getType() != Material.AIR
                && !plugin.getItemUtil().isStorageBox(cursor) && cursor.isSimilar(template)) {
            total += cursor.getAmount();
        }

        return total;
    }

    private void removeMatching(Player player, ItemStack template, int amount) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        int remaining = amount;

        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() == Material.AIR) continue;
            if (plugin.getItemUtil().isStorageBox(it)) continue;
            if (!it.isSimilar(template)) continue;

            int take = Math.min(it.getAmount(), remaining);
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) {
                contents[i] = null;
            }
            remaining -= take;
        }

        inv.setStorageContents(contents);
    }
}