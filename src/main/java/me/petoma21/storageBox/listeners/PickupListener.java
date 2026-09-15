package me.petoma21.storageBox.listeners;

import me.petoma21.storageBox.ItemUtil;
import me.petoma21.storageBox.StorageBox;
import me.petoma21.storageBox.StorageEntry;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;

public class PickupListener implements Listener {

    private final StorageBox plugin;
    private final ItemUtil itemUtil;

    public PickupListener(StorageBox plugin) {
        this.plugin = plugin;
        this.itemUtil = plugin.getItemUtil();
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
