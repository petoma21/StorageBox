package me.petoma21.storageBox.listeners;

import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

/**
 * Identifies the 1-row "register an item" GUI and remembers which hand of which player
 * held the (still unregistered) StorageBox item that opened it.
 */
public class RegisterGuiHolder implements InventoryHolder {

    public static final int TARGET_SLOT = 4;

    private final UUID playerId;
    private final EquipmentSlot hand;
    private Inventory inventory;
    private boolean closing = false;

    public RegisterGuiHolder(UUID playerId, EquipmentSlot hand) {
        this.playerId = playerId;
        this.hand = hand;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public EquipmentSlot getHand() {
        return hand;
    }

    public boolean isClosing() {
        return closing;
    }

    public void setClosing(boolean closing) {
        this.closing = closing;
    }
}
