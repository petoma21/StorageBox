package me.petoma21.storageBox.listeners;

import me.petoma21.storageBox.ItemUtil;
import me.petoma21.storageBox.StorageBox;
import me.petoma21.storageBox.StorageDataManager;
import me.petoma21.storageBox.StorageEntry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class PickupListener implements Listener {

    private final StorageBox plugin;
    private final ItemUtil itemUtil;

    /** Players with a checkPlayer() already scheduled for the end of the current tick. */
    private final Set<UUID> pendingCheck = new HashSet<>();

    public PickupListener(StorageBox plugin) {
        this.plugin = plugin;
        this.itemUtil = plugin.getItemUtil();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // Deliberately batched to at most once per player per tick rather than running for every
        // single block break: an AOE pickaxe breaking 9 blocks in one swing happens within the
        // same tick, so without this, checkPlayer() (a full inventory scan per registered item)
        // would run up to 9 times for a single swing. Only the FIRST block break in a given tick
        // schedules the check; any further ones that tick just piggyback on it - the check itself
        // still runs at the very next tick (imperceptible, ~50ms), so nothing meaningfully slower
        // is lost, but the worst-case load is now bounded by ticks-per-second rather than by how
        // fast a player can break blocks.
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (pendingCheck.add(id)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                pendingCheck.remove(id);
                if (player.isOnline()) {
                    plugin.getAutoCollectPoller().checkPlayer(player);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDropItem(BlockDropItemEvent event) {
        Player player = event.getPlayer();
        StorageDataManager dataManager = plugin.getStorageDataManager();
        Set<ItemStack> touchedTemplates = new HashSet<>();

        // Copy the list first - we remove entities from the world as we go, and mutating the
        // event's own backing list while iterating it directly would be asking for trouble.
        for (Item itemEntity : new ArrayList<>(event.getItems())) {
            ItemStack drop = itemEntity.getItemStack();
            if (itemUtil.isStorageBox(drop)) continue;

            StorageEntry entry = dataManager.findEntry(player.getUniqueId(), drop);
            if (entry == null || !entry.isAutocollect()) continue;
            if (!plugin.hasMatchingBoxInInventory(player, entry.getTemplate())) continue;

            entry.addCount(drop.getAmount());
            itemEntity.remove();
            touchedTemplates.add(entry.getTemplate());
        }

        if (!touchedTemplates.isEmpty()) {
            dataManager.save(player.getUniqueId());
            for (ItemStack template : touchedTemplates) {
                StorageEntry entry = dataManager.getOrCreateEntry(player.getUniqueId(), template);
                plugin.refreshMatchingBoxes(player, template, entry);
                plugin.getAutoCollectPoller().resync(player, template);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        ItemStack picked = event.getItem().getItemStack();
        if (itemUtil.isStorageBox(picked)) return; // never auto-collect a StorageBox item itself

        StorageEntry entry = plugin.getStorageDataManager().findEntry(player.getUniqueId(), picked);
        if (entry == null || !entry.isAutocollect()) return;
        if (!plugin.hasMatchingBoxInInventory(player, entry.getTemplate())) return;

        event.setCancelled(true);
        event.getItem().remove();

        entry.addCount(picked.getAmount());
        plugin.getStorageDataManager().save(player.getUniqueId());
        plugin.refreshMatchingBoxes(player, entry.getTemplate(), entry);
        plugin.getAutoCollectPoller().resync(player, entry.getTemplate());
    }
}