package me.petoma21.storageBox;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AutoCollectPoller {

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

    public void resync(Player player, ItemStack template) {
        Map<String, Integer> baseline = baselines.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        baseline.put(ItemUtil.templateKey(template), countMatching(player, template));
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
            boolean browsingContainer = isViewingChestLikeInventory(player);

            for (StorageEntry entry : entries.values()) {
                ItemStack template = entry.getTemplate();
                String key = ItemUtil.templateKey(template);
                int current = countMatching(player, template);
                Integer prev = baseline.get(key);

                if (prev == null) {
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

    private int countMatching(Player player, ItemStack template) {
        int total = 0;
        for (ItemStack it : player.getInventory().getStorageContents()) {
            if (it == null || it.getType() == Material.AIR) continue;
            if (plugin.getItemUtil().isStorageBox(it)) continue;
            if (!it.isSimilar(template)) continue;
            total += it.getAmount();
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
    private boolean isViewingChestLikeInventory(Player player) {
        InventoryType type = player.getOpenInventory().getTopInventory().getType();
        return type == InventoryType.CHEST || type == InventoryType.SHULKER_BOX || type == InventoryType.BARREL;
    }
}
