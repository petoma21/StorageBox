package me.petoma21.storageBox;

import org.bukkit.inventory.ItemStack;

/**
 * Represents the storage state for one distinct registered item type belonging to one player.
 * All StorageBox items (possibly several physical copies) registered to the same item type
 * for the same player share and mutate the same StorageEntry - this is what keeps them "synced".
 */
public class StorageEntry {

    private final ItemStack template; // amount always 1, used only to know material/meta
    private long count;
    private boolean autocollect;

    public StorageEntry(ItemStack template, long count, boolean autocollect) {
        this.template = template;
        this.count = count;
        this.autocollect = autocollect;
    }

    public ItemStack getTemplate() {
        return template;
    }

    public long getCount() {
        return count;
    }

    public void setCount(long count) {
        this.count = Math.max(0, count);
    }

    public void addCount(long amount) {
        setCount(this.count + amount);
    }

    public boolean isAutocollect() {
        return autocollect;
    }

    public void setAutocollect(boolean autocollect) {
        this.autocollect = autocollect;
    }
}
