package me.petoma21.storageBox;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Stores each player's registered StorageBox items in {@code plugins/StorageBox/data/<uuid>.db}.
 * The file is plain YAML underneath (the .db extension is only cosmetic, as requested).
 */
public class StorageDataManager {

    private final StorageBox plugin;
    private final File dataFolder;
    private final Map<UUID, Map<String, StorageEntry>> cache = new HashMap<>();

    public StorageDataManager(StorageBox plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "data");
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    private File fileFor(UUID uuid) {
        return new File(dataFolder, uuid.toString() + ".db");
    }

    public Map<String, StorageEntry> getEntries(UUID owner) {
        return cache.computeIfAbsent(owner, this::load);
    }

    public StorageEntry findEntry(UUID owner, ItemStack template) {
        return getEntries(owner).get(ItemUtil.templateKey(template));
    }

    public StorageEntry getOrCreateEntry(UUID owner, ItemStack template) {
        Map<String, StorageEntry> entries = getEntries(owner);
        String key = ItemUtil.templateKey(template);
        StorageEntry entry = entries.get(key);
        if (entry == null) {
            ItemStack clone = template.clone();
            clone.setAmount(1);
            entry = new StorageEntry(clone, 0L, plugin.getConfigManager().isAutocollectDefault());
            entries.put(key, entry);
        }
        return entry;
    }

    private Map<String, StorageEntry> load(UUID uuid) {
        Map<String, StorageEntry> map = new HashMap<>();
        File file = fileFor(uuid);
        if (!file.exists()) {
            return map;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<Map<?, ?>> items = yaml.getMapList("items");
        for (Map<?, ?> raw : items) {
            try {
                String templateB64 = String.valueOf(raw.get("template"));
                long count = raw.get("count") instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(raw.get("count")));
                boolean autocollect = raw.get("autocollect") instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(raw.get("autocollect")));

                ItemStack template = ItemUtil.decode(templateB64);
                if (template == null) continue;
                String key = ItemUtil.templateKey(template);
                map.put(key, new StorageEntry(template, count, autocollect));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to parse a storage entry for " + uuid, e);
            }
        }
        return map;
    }

    public void save(UUID uuid) {
        Map<String, StorageEntry> entries = cache.get(uuid);
        if (entries == null) return;

        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new java.util.ArrayList<>();
        for (StorageEntry entry : entries.values()) {
            Map<String, Object> m = new HashMap<>();
            m.put("template", ItemUtil.encode(entry.getTemplate()));
            m.put("count", entry.getCount());
            m.put("autocollect", entry.isAutocollect());
            list.add(m);
        }
        yaml.set("items", list);

        try {
            yaml.save(fileFor(uuid));
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to save storage data for " + uuid, e);
        }
    }

    public void saveAll() {
        for (UUID uuid : cache.keySet()) {
            save(uuid);
        }
    }

    public void unload(UUID uuid) {
        save(uuid);
        cache.remove(uuid);
    }
}
