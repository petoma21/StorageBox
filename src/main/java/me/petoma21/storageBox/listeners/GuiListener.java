package me.petoma21.storageBox.listeners;

import me.petoma21.storageBox.ItemUtil;
import me.petoma21.storageBox.StorageBox;
import me.petoma21.storageBox.StorageEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;


public class GuiListener implements Listener {

    private final StorageBox plugin;
    private final ItemUtil itemUtil;

    public GuiListener(StorageBox plugin) {
        this.plugin = plugin;
        this.itemUtil = plugin.getItemUtil();
    }

    public void openRegisterGui(Player player, EquipmentSlot hand) {
        RegisterGuiHolder holder = new RegisterGuiHolder(player.getUniqueId(), hand);
        Inventory inv = Bukkit.createInventory(holder, 9, plugin.getMessageManager().legacyToComponent(plugin.getMessageManager().raw("gui.title")));
        holder.setInventory(inv);

        ItemStack filler = itemUtil.createFillerGlass();
        for (int i = 0; i < 9; i++) {
            if (i == RegisterGuiHolder.TARGET_SLOT) continue;
            inv.setItem(i, filler.clone());
        }
        player.openInventory(inv);
    }

    @EventHandler(ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        InventoryView view = event.getView();
        if (!(view.getTopInventory().getHolder() instanceof RegisterGuiHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Inventory clicked = event.getClickedInventory();
        boolean clickedTop = clicked != null && clicked.equals(view.getTopInventory());

        if (clickedTop) {
            if (event.getSlot() != RegisterGuiHolder.TARGET_SLOT) {
                event.setCancelled(true);
                return;
            }
            if (event.getClick() == ClickType.DOUBLE_CLICK) {
                event.setCancelled(true);
                return;
            }
            ItemStack cursor = event.getCursor();
            if (cursor != null && itemUtil.isStorageBox(cursor)) {
                event.setCancelled(true);
                plugin.getMessageManager().send(player, "register.cannot-register");
                return;
            }
            // Allow the click to proceed, then check next tick whether the center slot now holds an item.
            Bukkit.getScheduler().runTask(plugin, () -> maybeAutoClose(holder, view.getTopInventory(), player));
        }
        // Clicks in the player's own inventory are left untouched.
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        InventoryView view = event.getView();
        if (!(view.getTopInventory().getHolder() instanceof RegisterGuiHolder holder)) return;
        int topSize = view.getTopInventory().getSize();

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < topSize && rawSlot != RegisterGuiHolder.TARGET_SLOT) {
                event.setCancelled(true);
                return;
            }
        }

        if (event.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> maybeAutoClose(holder, view.getTopInventory(), player));
        }
    }

    private void maybeAutoClose(RegisterGuiHolder holder, Inventory topInventory, Player player) {
        if (holder.isClosing()) return;
        ItemStack center = topInventory.getItem(RegisterGuiHolder.TARGET_SLOT);
        if (center != null && center.getType() != Material.AIR) {
            holder.setClosing(true);
            player.closeInventory();
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof RegisterGuiHolder holder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        ItemStack center = event.getInventory().getItem(RegisterGuiHolder.TARGET_SLOT);
        if (center == null || center.getType() == Material.AIR || itemUtil.isStorageBox(center)) {
            // Cancelled registration (E/Escape with nothing placed, or an invalid item) - nothing to register.
            return;
        }

        EquipmentSlot hand = holder.getHand();
        ItemStack handItem = hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();

        if (!itemUtil.isStorageBox(handItem) || itemUtil.isRegistered(handItem)) {
            // The box changed hands/slots while the GUI was open - abort silently.
            return;
        }

        ItemStack template = center.clone();
        template.setAmount(1);

        ItemStack registered = itemUtil.register(handItem, template, player.getUniqueId());
        StorageEntry entry = plugin.getStorageDataManager().getOrCreateEntry(player.getUniqueId(), template);
        entry.addCount(center.getAmount());
        itemUtil.refreshDisplay(registered, template, entry);

        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(registered);
        } else {
            player.getInventory().setItemInMainHand(registered);
        }

        plugin.getStorageDataManager().save(player.getUniqueId());
        plugin.getAutoCollectPoller().resync(player, template);
        plugin.playConfiguredSound(player, plugin.getConfigManager().getRegisterSound());
        plugin.getMessageManager().sendWithItem(player, "register.success", template, null);
    }
}
