package me.petoma21.storageBox;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class ConfigManager {

    private final StorageBox plugin;

    private boolean craftEnabled;
    private Material itemMaterial;
    private String itemName;
    private List<String> itemLore;
    private List<String> customModelDataStrings;
    private boolean stackable;
    private String openSound;
    private String registerSound;
    private boolean autocollectDefault;
    private int antiSpamCooldownTicks;
    private Set<Material> registrationBlacklist;

    public ConfigManager(StorageBox plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        craftEnabled = c.getBoolean("craft.enabled", true);

        String matName = c.getString("item.material", "CHEST");
        Material mat = Material.matchMaterial(matName);
        itemMaterial = mat != null ? mat : Material.CHEST;

        itemName = c.getString("item.name", "&e&lStorageBox");
        itemLore = c.getStringList("item.lore");
        customModelDataStrings = c.getStringList("item.custom_model_data.strings");
        stackable = c.getBoolean("item.stackable", false);

        openSound = c.getString("sound.open", "minecraft:block.barrel.open");
        registerSound = c.getString("sound.register", "minecraft:entity.player.levelup");

        autocollectDefault = c.getBoolean("autocollect.default", true);
        antiSpamCooldownTicks = c.getInt("anti-spam.cooldown-ticks", 6);

        registrationBlacklist = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("registration.blacklist")) {
            Material blacklisted = Material.matchMaterial(name);
            if (blacklisted != null) {
                registrationBlacklist.add(blacklisted);
            } else {
                plugin.getLogger().log(Level.WARNING, "config.yml: unknown material in registration.blacklist: " + name);
            }
        }
    }

    public boolean isCraftEnabled() {
        return craftEnabled;
    }

    public Material getItemMaterial() {
        return itemMaterial;
    }

    public String getItemName() {
        return itemName;
    }

    public List<String> getItemLore() {
        return itemLore;
    }

    public List<String> getCustomModelDataStrings() {
        return customModelDataStrings;
    }

    public boolean isStackable() {
        return stackable;
    }

    public String getOpenSound() {
        return openSound;
    }

    public String getRegisterSound() {
        return registerSound;
    }

    public boolean isAutocollectDefault() {
        return autocollectDefault;
    }

    public int getAntiSpamCooldownTicks() {
        return antiSpamCooldownTicks;
    }

    /** True if config.yml's registration.blacklist forbids registering this material into a StorageBox. */
    public boolean isRegistrationBlacklisted(Material material) {
        return registrationBlacklist.contains(material);
    }
}