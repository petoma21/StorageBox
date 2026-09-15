package me.petoma21.storageBox;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.CustomModelData;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ItemUtil {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final StorageBox plugin;

    public ItemUtil(StorageBox plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    //  Unregistered box creation
    // =========================================================================

    public ItemStack createUnregisteredBox(int amount) {
        ConfigManager cfg = plugin.getConfigManager();
        ItemStack item = new ItemStack(cfg.getItemMaterial(), amount);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(LEGACY.deserialize(cfg.getItemName()).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        for (String line : cfg.getItemLore()) {
            lore.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);

        meta.getPersistentDataContainer().set(Keys.isStorageBox(plugin), PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);

        if (!cfg.getCustomModelDataStrings().isEmpty()) {
            CustomModelData.Builder cmd = CustomModelData.customModelData();
            for (String s : cfg.getCustomModelDataStrings()) {
                cmd.addString(s);
            }
            item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, cmd.build());
        }

        if (!cfg.isStackable()) {
            item.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        } else {
            item.resetData(DataComponentTypes.MAX_STACK_SIZE);
        }

        item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);

        return item;
    }

    // =========================================================================
    //  Marker / registration checks
    // =========================================================================

    public boolean isStorageBox(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(Keys.isStorageBox(plugin), PersistentDataType.BYTE);
    }

    public boolean isRegistered(ItemStack item) {
        if (!isStorageBox(item)) return false;
        return item.getItemMeta().getPersistentDataContainer().has(Keys.registeredTemplate(plugin), PersistentDataType.STRING);
    }

    public UUID getOwner(ItemStack item) {
        if (!isStorageBox(item)) return null;
        String s = item.getItemMeta().getPersistentDataContainer().get(Keys.owner(plugin), PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public ItemStack getTemplate(ItemStack item) {
        if (!isRegistered(item)) return null;
        String encoded = item.getItemMeta().getPersistentDataContainer().get(Keys.registeredTemplate(plugin), PersistentDataType.STRING);
        return decode(encoded);
    }

    // =========================================================================
    //  Registration
    // =========================================================================

    /**
     * Turns an unregistered StorageBox item into a registered one that stores {@code template}.
     * The box's own material is changed to match the registered item (e.g. registering a gold
     * block turns the box itself into a gold block), so it visually reflects its contents.
     * Mutates and returns the given item (call sites should re-set it into the relevant slot).
     */
    public ItemStack register(ItemStack box, ItemStack template, UUID owner) {
        ItemStack templateClone = template.clone();
        templateClone.setAmount(1);

        // Change the box's own material to match the item it now stores.
        box.setType(template.getType());

        ItemMeta meta = box.getItemMeta();
        meta.getPersistentDataContainer().set(Keys.isStorageBox(plugin), PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(Keys.registeredTemplate(plugin), PersistentDataType.STRING, encode(templateClone));
        meta.getPersistentDataContainer().set(Keys.owner(plugin), PersistentDataType.STRING, owner.toString());
        box.setItemMeta(meta);

        // The box no longer needs the "unregistered" custom model, since it now visually IS the
        // registered material; but it should keep glowing and (per config) stay non-stackable.
        box.resetData(DataComponentTypes.CUSTOM_MODEL_DATA);
        box.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        if (!plugin.getConfigManager().isStackable()) {
            box.setData(DataComponentTypes.MAX_STACK_SIZE, 1);
        } else {
            box.resetData(DataComponentTypes.MAX_STACK_SIZE);
        }

        return box;
    }

    // =========================================================================
    //  Display refresh (name / lore) based on current stored amount
    // =========================================================================

    public void refreshDisplay(ItemStack box, ItemStack template, StorageEntry entry) {
        if (!isStorageBox(box)) return;
        MessageManager msg = plugin.getMessageManager();

        Material mat = template.getType();
        int maxStack = mat.getMaxStackSize() <= 0 ? 64 : mat.getMaxStackSize();
        long total = entry.getCount();
        long stacks = total / maxStack;
        long remainder = total % maxStack;

        String autocollectLabel = entry.isAutocollect()
                ? msg.raw("registered-item.autocollect-on-label")
                : msg.raw("registered-item.autocollect-off-label");

        // Note: "item" is intentionally NOT in this map - %item% is spliced in afterwards as a
        // real (client-localized) Component by MessageManager#buildLineWithItem, not as plain text.
        Map<String, String> ph = Map.of(
                "stacks", String.valueOf(stacks),
                "remainder", String.valueOf(remainder),
                "total", String.valueOf(total)
        );

        String nameLine = msg.withPlaceholders(msg.raw("registered-item.name"), ph);
        nameLine = nameLine.replace("%autocollect_colored%", autocollectLabel);

        ItemMeta meta = box.getItemMeta();
        meta.displayName(msg.buildLineWithItem(nameLine, template).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        for (String line : msg.rawList("registered-item.lore")) {
            String applied = msg.withPlaceholders(line, ph);
            applied = applied.replace("%autocollect_colored%", autocollectLabel);
            lore.add(msg.buildLineWithItem(applied, template).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        box.setItemMeta(meta);
    }

    // =========================================================================
    //  GUI filler glass pane
    // =========================================================================

    public ItemStack createFillerGlass() {
        ItemStack glass = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = glass.getItemMeta();
        meta.displayName(Component.text(" "));
        meta.getPersistentDataContainer().set(Keys.guiFiller(plugin), PersistentDataType.BYTE, (byte) 1);
        glass.setItemMeta(meta);
        glass.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true).build());
        return glass;
    }

    public boolean isFillerGlass(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(Keys.guiFiller(plugin), PersistentDataType.BYTE);
    }

    // =========================================================================
    //  Serialization helpers (used both for PDC storage and the per-player data files)
    // =========================================================================

    public static String encode(ItemStack item) {
        return Base64.getEncoder().encodeToString(item.serializeAsBytes());
    }

    public static ItemStack decode(String encoded) {
        if (encoded == null) return null;
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (Exception e) {
            return null;
        }
    }

    /** Stable key used to group storage entries of the same logical item (ignores amount). */
    public static String templateKey(ItemStack template) {
        ItemStack clone = template.clone();
        clone.setAmount(1);
        return encode(clone);
    }
}
