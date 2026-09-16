package me.petoma21.storageBox;

import me.petoma21.storageBox.commands.StbCommand;
import me.petoma21.storageBox.listeners.GuiListener;
import me.petoma21.storageBox.listeners.InteractListener;
import me.petoma21.storageBox.listeners.PickupListener;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Level;

public final class StorageBox extends JavaPlugin implements Listener {

    private ConfigManager configManager;
    private MessageManager messageManager;
    private StorageDataManager storageDataManager;
    private ItemUtil itemUtil;
    private AutoCollectPoller autoCollectPoller;

    private NamespacedKey craftRecipeKey;
    private boolean recipeRegistered = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.itemUtil = new ItemUtil(this);
        this.configManager = new ConfigManager(this);
        this.messageManager = new MessageManager(this);
        this.storageDataManager = new StorageDataManager(this);
        this.autoCollectPoller = new AutoCollectPoller(this);
        this.craftRecipeKey = new NamespacedKey(this, "storagebox_craft");

        GuiListener guiListener = new GuiListener(this);
        Bukkit.getPluginManager().registerEvents(guiListener, this);
        Bukkit.getPluginManager().registerEvents(new InteractListener(this, guiListener), this);
        Bukkit.getPluginManager().registerEvents(new PickupListener(this), this);
        Bukkit.getPluginManager().registerEvents(autoCollectPoller, this);
        Bukkit.getPluginManager().registerEvents(this, this);

        StbCommand command = new StbCommand(this);
        getCommand("stb").setExecutor(command);
        getCommand("stb").setTabCompleter(command);

        applyRecipeState();
        autoCollectPoller.start();
        hookAdvancedEnchantmentsIfPresent();

        getLogger().info("StorageBox has been enabled.");
    }

    @Override
    public void onDisable() {
        if (autoCollectPoller != null) {
            autoCollectPoller.stop();
        }
        if (storageDataManager != null) {
            storageDataManager.saveAll();
        }
        if (recipeRegistered && craftRecipeKey != null) {
            Bukkit.removeRecipe(craftRecipeKey);
        }
    }

    /** Reloads config.yml and message.yml, and re-applies the crafting recipe state. */
    public void reloadAll() {
        configManager.load();
        messageManager.load();
        applyRecipeState();
    }

    private void applyRecipeState() {
        boolean shouldBeEnabled = configManager.isCraftEnabled();
        if (shouldBeEnabled && !recipeRegistered) {
            registerRecipe();
        } else if (!shouldBeEnabled && recipeRegistered) {
            Bukkit.removeRecipe(craftRecipeKey);
            recipeRegistered = false;
        } else if (shouldBeEnabled) {
            // Re-register in case item appearance (name/lore/material/model data) changed.
            Bukkit.removeRecipe(craftRecipeKey);
            registerRecipe();
        }
    }

    private void registerRecipe() {
        ItemStack result = itemUtil.createUnregisteredBox(1);
        ShapedRecipe recipe = new ShapedRecipe(craftRecipeKey, result);
        recipe.shape("CCC", "C C", "CCC");
        recipe.setIngredient('C', org.bukkit.Material.CHEST);
        Bukkit.addRecipe(recipe);
        recipeRegistered = true;
    }

    /**
     * Best-effort, purely-optional integration: if AdvancedEnchantments is installed, its
     * generic {@code net.advancedplugins.ae.api.EffectsActivateEvent} fires whenever ANY of its
     * custom enchant effects activate (including a server admin's own "give item to inventory"
     * style effect used for things like auto-pickup-on-mine). We don't compile against
     * AdvancedEnchantments' jar (it isn't published to a public Maven repo, and hard-depending
     * on a proprietary plugin's internal classes is fragile across their updates), so this is
     * done via Bukkit's reflective event registration: if the class can't be found, or anything
     * else goes wrong, this silently does nothing and {@link AutoCollectPoller}'s periodic timer
     * remains the (sole) fallback, exactly as intended.
     * <p>
     * Note: AdvancedEnchantments' public docs (https://ae.advancedplugins.net) do not document
     * this event exposing which player/item/amount was involved in a re-usable, version-stable
     * way (it's a generic activation signal for arbitrary admin-defined effects) - so rather than
     * risk reflecting into undocumented fields, we simply use its firing as a "something just
     * happened, check now" trigger and re-run our own (cheap) check for every online player
     * immediately, instead of waiting for the next scheduled tick.
     */
    private void hookAdvancedEnchantmentsIfPresent() {
        if (Bukkit.getPluginManager().getPlugin("AdvancedEnchantments") == null) {
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Event> eventClass =
                    (Class<? extends Event>) Class.forName("net.advancedplugins.ae.api.EffectsActivateEvent");

            EventExecutor executor = (listener, event) ->
                    Bukkit.getScheduler().runTask(this, autoCollectPoller::checkNow);

            Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.MONITOR, executor, this, true);
            getLogger().info("Detected AdvancedEnchantments - hooked its effect events for faster autocollect response.");
        } catch (ClassNotFoundException e) {
            getLogger().info("AdvancedEnchantments detected, but its event class was not found (version mismatch?) - "
                    + "falling back to periodic autocollect polling only.");
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Failed to hook into AdvancedEnchantments events - "
                    + "falling back to periodic autocollect polling only.", t);
        }
    }

    // =========================================================================
    //  Shared helpers used by listeners/commands
    // =========================================================================

    public void playConfiguredSound(Player player, String soundKey) {
        try {
            NamespacedKey nk = NamespacedKey.fromString(soundKey.toLowerCase());
            Sound sound = nk != null ? Registry.SOUNDS.get(nk) : null;
            if (sound != null) {
                player.playSound(player.getLocation(), sound, 1f, 1f);
            } else {
                player.playSound(player.getLocation(), soundKey, 1f, 1f);
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Could not play sound '" + soundKey + "'", e);
        }
    }

    /** Refreshes the name/lore of every StorageBox in the given player's inventory that stores the same item. */
    public void refreshMatchingBoxes(Player player, ItemStack template, StorageEntry entry) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it == null) continue;
            if (!itemUtil.isRegistered(it)) continue;
            ItemStack itTemplate = itemUtil.getTemplate(it);
            if (itTemplate == null || !itTemplate.isSimilar(template)) continue;
            itemUtil.refreshDisplay(it, itTemplate, entry);
            inv.setItem(i, it);
        }
    }

    /**
     * True if the player is carrying (anywhere in their inventory, any slot - not necessarily
     * the main hand) a StorageBox registered to them that stores {@code template}. Autocollect
     * only applies while the matching box is somewhere on the player's person, like an equipped
     * backpack - if it's left behind in a chest elsewhere, autocollect does not apply.
     */
    public boolean hasMatchingBoxInInventory(Player player, ItemStack template) {
        PlayerInventory inv = player.getInventory();
        for (ItemStack it : inv.getContents()) {
            if (it == null) continue;
            if (!itemUtil.isRegistered(it)) continue;
            ItemStack itTemplate = itemUtil.getTemplate(it);
            if (itTemplate != null && itTemplate.isSimilar(template)) {
                return true;
            }
        }
        ItemStack offHand = inv.getItemInOffHand();
        if (itemUtil.isRegistered(offHand)) {
            ItemStack itTemplate = itemUtil.getTemplate(offHand);
            if (itTemplate != null && itTemplate.isSimilar(template)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        storageDataManager.save(uuid);
        autoCollectPoller.forget(uuid);
    }

    // =========================================================================
    //  Getters
    // =========================================================================

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public StorageDataManager getStorageDataManager() {
        return storageDataManager;
    }

    public ItemUtil getItemUtil() {
        return itemUtil;
    }

    public AutoCollectPoller getAutoCollectPoller() {
        return autoCollectPoller;
    }
}