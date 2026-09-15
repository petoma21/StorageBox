package me.petoma21.storageBox;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class MessageManager {

    private final StorageBox plugin;
    private final LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
    private File file;
    private FileConfiguration config;

    public MessageManager(StorageBox plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        file = new File(plugin.getDataFolder(), "message.yml");
        if (!file.exists()) {
            plugin.saveResource("message.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);

        // Fill in any keys that are missing (e.g. after a plugin update) using the bundled defaults.
        try (InputStream in = plugin.getResource("message.yml")) {
            if (in != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
                config.setDefaults(defaults);
                config.options().copyDefaults(true);
            }
        } catch (IOException ignored) {
        }
    }

    private String prefix() {
        return config.getString("prefix", "");
    }

    public String raw(String path) {
        String s = config.getString(path, path);
        return s;
    }

    public List<String> rawList(String path) {
        return config.getStringList(path);
    }

    private String applyPlaceholders(String input, Map<String, String> placeholders) {
        String result = input;
        if (placeholders != null) {
            for (Map.Entry<String, String> e : placeholders.entrySet()) {
                result = result.replace("%" + e.getKey() + "%", e.getValue());
            }
        }
        return result;
    }

    /** Public wrapper so other classes (e.g. ItemUtil) can reuse the same %placeholder% substitution. */
    public String withPlaceholders(String input, Map<String, String> placeholders) {
        return applyPlaceholders(input, placeholders);
    }

    /**
     * Returns a Component representing an item's name that will render correctly in the viewer's
     * own client language (e.g. Japanese clients see the Japanese vanilla name automatically),
     * unless the item has an explicit custom display name, in which case that name is used as-is.
     */
    public Component itemNameComponent(ItemStack template) {
        if (template.hasItemMeta() && template.getItemMeta().hasDisplayName()) {
            Component custom = template.getItemMeta().displayName();
            if (custom != null) {
                return custom;
            }
        }
        return Component.translatable(template.translationKey())
                .color(NamedTextColor.WHITE);
    }

    /**
     * Builds a Component from a raw (already placeholder-substituted, except for %item%) legacy
     * string, splicing in a real item-name Component (see {@link #itemNameComponent}) wherever
     * %item% appears, instead of flattening it to plain text.
     */
    public Component buildLineWithItem(String raw, ItemStack template) {
        String[] parts = raw.split("%item%", -1);
        Component result = Component.empty();
        for (int i = 0; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                result = result.append(legacy.deserialize(parts[i]));
            }
            if (i < parts.length - 1) {
                result = result.append(itemNameComponent(template));
            }
        }
        return result;
    }

    public Component formatWithItemComponent(String path, ItemStack template, Map<String, String> placeholders) {
        String raw = applyPlaceholders(raw(path), placeholders);
        return buildLineWithItem(raw, template);
    }

    public Component format(String path, Map<String, String> placeholders) {
        String raw = raw(path);
        String applied = applyPlaceholders(raw, placeholders);
        return legacy.deserialize(applied);
    }

    public Component formatWithPrefix(String path, Map<String, String> placeholders) {
        String raw = prefix() + raw(path);
        String applied = applyPlaceholders(raw, placeholders);
        return legacy.deserialize(applied);
    }

    public Component legacyToComponent(String legacyText) {
        return legacy.deserialize(legacyText);
    }

    public void send(CommandSender sender, String path, Map<String, String> placeholders) {
        sender.sendMessage(formatWithPrefix(path, placeholders));
    }

    public void send(CommandSender sender, String path) {
        send(sender, path, null);
    }

    /** Same as {@link #send}, but %item% is spliced in as a real (client-localized) Component. */
    public void sendWithItem(CommandSender sender, String path, ItemStack template, Map<String, String> placeholders) {
        Component prefixComp = legacy.deserialize(prefix());
        Component body = formatWithItemComponent(path, template, placeholders);
        sender.sendMessage(prefixComp.append(body));
    }

    public FileConfiguration getConfig() {
        return config;
    }
}
