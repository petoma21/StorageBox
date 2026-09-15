package me.petoma21.storageBox;

import org.bukkit.NamespacedKey;

/**
 * Central place for all PersistentDataContainer keys used by the plugin.
 */
public final class Keys {

    private Keys() {
    }

    public static NamespacedKey isStorageBox(StorageBox plugin) {
        return new NamespacedKey(plugin, "storagebox_marker");
    }

    /** Base64-encoded ItemStack bytes of the registered template item (amount always 1). */
    public static NamespacedKey registeredTemplate(StorageBox plugin) {
        return new NamespacedKey(plugin, "registered_template");
    }

    /** UUID (as string) of the player who owns this box's storage data. */
    public static NamespacedKey owner(StorageBox plugin) {
        return new NamespacedKey(plugin, "owner_uuid");
    }

    /** Marks the gray glass pane filler slots inside the register GUI so we never move them. */
    public static NamespacedKey guiFiller(StorageBox plugin) {
        return new NamespacedKey(plugin, "gui_filler");
    }
}
