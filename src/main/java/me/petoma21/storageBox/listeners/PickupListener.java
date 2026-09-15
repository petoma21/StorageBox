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

/**
 * Redirects freshly picked-up items straight into storage - but ONLY if the player is currently
 * carrying a matching registered StorageBox SOMEWHERE in their inventory (any slot, not just the
 * main hand - like it's "equipped" as long as it's on their person). If no such box is present
 * anywhere in the inventory, picked-up items are left alone and simply stay in the inventory as
 * normal, even if that item type has been registered before (e.g. the box is sitting in a chest
 * elsewhere).
 * <p>
 * This also naturally satisfies "items already sitting in the inventory are never re-collected":
 * we only ever look at the item that was JUST picked up (from {@link EntityPickupItemEvent}),
 * never at pre-existing inventory contents.
 */
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
