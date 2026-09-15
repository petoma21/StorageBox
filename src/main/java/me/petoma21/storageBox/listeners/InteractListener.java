package me.petoma21.storageBox.listeners;

import me.petoma21.storageBox.ItemUtil;
import me.petoma21.storageBox.StorageBox;
import me.petoma21.storageBox.StorageEntry;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class InteractListener implements Listener {

    private static final double USE_RANGE_BLOCKS = 4.5;

    private final StorageBox plugin;
    private final ItemUtil itemUtil;
    private final GuiListener guiListener;

    /** UUID -> timestamp (millis) of the last processed click/action, used for debouncing. */
    private final Map<UUID, Long> lastActionMillis = new HashMap<>();

    public InteractListener(StorageBox plugin, GuiListener guiListener) {
        this.plugin = plugin;
        this.itemUtil = plugin.getItemUtil();
        this.guiListener = guiListener;
    }

    private boolean onCooldown(Player player) {
        long now = System.currentTimeMillis();
        long cooldownMillis = plugin.getConfigManager().getAntiSpamCooldownTicks() * 50L;
        Long last = lastActionMillis.get(player.getUniqueId());
        if (last != null && now - last < cooldownMillis) {
            return true;
        }
        lastActionMillis.put(player.getUniqueId(), now);
        return false;
    }

    /** True if {@code clickedBlock} is within {@link #USE_RANGE_BLOCKS} of the player's own position. */
    private boolean isWithinUseRange(Player player, Block clickedBlock) {
        if (clickedBlock == null) return false;
        Location blockCenter = clickedBlock.getLocation().add(0.5, 0.5, 0.5);
        return player.getLocation().distance(blockCenter) <= USE_RANGE_BLOCKS;
    }

    /** Only blocks within the player's own 3x3x3 neighbourhood (their feet block +/-1) count as "right next to them". */
    private boolean isWithinOneBlock(Player player, Block block) {
        Block feet = player.getLocation().getBlock();
        return Math.abs(block.getX() - feet.getX()) <= 1
                && Math.abs(block.getY() - feet.getY()) <= 1
                && Math.abs(block.getZ() - feet.getZ()) <= 1;
    }

    /** Returns the inventory of the chest/shulker box/barrel the player is looking at, if it's also within 1 block of them. */
    private Inventory nearbyContainerInventory(Player player, Block clickedBlock) {
        if (clickedBlock == null || !isWithinOneBlock(player, clickedBlock)) return null;
        BlockState state = clickedBlock.getState();
        if (state instanceof Chest chest) return chest.getInventory();
        if (state instanceof ShulkerBox shulker) return shulker.getInventory();
        if (state instanceof Barrel barrel) return barrel.getInventory();
        return null;
    }

    // =========================================================================
    //  Main interaction entry point (both left and right click)
    // =========================================================================

    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!itemUtil.isStorageBox(hand)) return;

        Action action = event.getAction();

        // ---- Left click: case 3 (withdraw 1 stack) / case 5 (sneaking: withdraw max that fits) ----
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            // Always cancel - a StorageBox must never break the block it's used on.
            event.setCancelled(true);

            if (!itemUtil.isRegistered(hand)) return;
            if (onCooldown(player)) return;

            ItemStack template = itemUtil.getTemplate(hand);
            UUID owner = itemUtil.getOwner(hand);
            if (template == null || owner == null) return;
            StorageEntry entry = plugin.getStorageDataManager().getOrCreateEntry(owner, template);

            if (player.isSneaking()) {
                Inventory containerInv = action == Action.LEFT_CLICK_BLOCK
                        ? nearbyContainerInventory(player, event.getClickedBlock())
                        : null;
                if (containerInv != null) {
                    doContainerPush(player, hand, template, entry, containerInv); // priority override
                } else {
                    doWithdrawAll(player, hand, template, entry); // case 5
                }
            } else {
                doWithdraw(player, hand, template, entry); // case 3
            }
            return;
        }

        // ---- Right click: open GUI (unregistered) / case 1 (use) / case 2 (deposit 1 stack) / case 4 (sneaking: deposit all) ----
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        // Note: this event fires "pre-cancelled" by the server whenever vanilla would do nothing
        // (e.g. right-clicking air with a plain block item) - that's why this handler explicitly
        // uses ignoreCancelled = false above, otherwise those clicks would silently never reach us.
        event.setCancelled(true);

        if (!itemUtil.isRegistered(hand)) {
            if (onCooldown(player)) return;
            plugin.playConfiguredSound(player, plugin.getConfigManager().getOpenSound());
            guiListener.openRegisterGui(player, EquipmentSlot.HAND);
            return;
        }

        if (onCooldown(player)) return;

        ItemStack template = itemUtil.getTemplate(hand);
        UUID owner = itemUtil.getOwner(hand);
        if (template == null || owner == null) return;
        StorageEntry entry = plugin.getStorageDataManager().getOrCreateEntry(owner, template);

        if (player.isSneaking()) {
            Inventory containerInv = action == Action.RIGHT_CLICK_BLOCK
                    ? nearbyContainerInventory(player, event.getClickedBlock())
                    : null;
            if (containerInv != null) {
                doContainerPull(player, hand, template, entry, containerInv); // priority override
            } else {
                doDepositAll(player, hand, template, entry); // case 4
            }
        } else if (isWithinUseRange(player, event.getClickedBlock())) {
            doUse(player, event, template, entry); // case 1
        } else {
            doDeposit(player, hand, template, entry); // case 2
        }
    }

    // =========================================================================
    //  Left-click on an entity: withdraw, without dealing damage (same rules as world left-click)
    // =========================================================================

    @EventHandler(ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!itemUtil.isRegistered(hand)) return;

        event.setCancelled(true);
        if (onCooldown(player)) return;

        ItemStack template = itemUtil.getTemplate(hand);
        UUID owner = itemUtil.getOwner(hand);
        if (template == null || owner == null) return;
        StorageEntry entry = plugin.getStorageDataManager().getOrCreateEntry(owner, template);

        if (player.isSneaking()) {
            doWithdrawAll(player, hand, template, entry); // case 5
        } else {
            doWithdraw(player, hand, template, entry); // case 3
        }
    }

    // =========================================================================
    //  Actions: player inventory <-> storage
    // =========================================================================

    /** Case 2: deposit 1 stack from the inventory into storage. No chat message (only on failure). */
    private void doDeposit(Player player, ItemStack box, ItemStack template, StorageEntry entry) {
        int maxStack = Math.max(1, template.getType().getMaxStackSize());
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();

        int collected = 0;
        for (int i = 0; i < contents.length && collected < maxStack; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() == Material.AIR) continue;
            if (itemUtil.isStorageBox(it)) continue;
            if (!it.isSimilar(template)) continue;

            int take = Math.min(it.getAmount(), maxStack - collected);
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) {
                contents[i] = null;
            }
            collected += take;
        }

        if (collected == 0) {
            plugin.getMessageManager().sendWithItem(player, "storage.deposit-none", template, null);
            return;
        }

        inv.setStorageContents(contents);
        entry.addCount(collected);
        finishTransaction(player, box, template, entry);
    }

    /** Case 3: withdraw 1 stack from storage into the inventory. No chat message (only on failure). */
    private void doWithdraw(Player player, ItemStack box, ItemStack template, StorageEntry entry) {
        long available = entry.getCount();
        if (available <= 0) {
            plugin.getMessageManager().sendWithItem(player, "storage.withdraw-empty", template, null);
            return;
        }

        int maxStack = Math.max(1, template.getType().getMaxStackSize());
        int amount = (int) Math.min(maxStack, available);

        entry.setCount(available - amount);

        ItemStack give = template.clone();
        give.setAmount(amount);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(give);
        for (ItemStack l : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), l);
        }

        finishTransaction(player, box, template, entry);
    }

    /** Case 4: deposit EVERY matching item in the inventory into storage. Always sends a chat message. */
    private void doDepositAll(Player player, ItemStack box, ItemStack template, StorageEntry entry) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();

        long collected = 0;
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() == Material.AIR) continue;
            if (itemUtil.isStorageBox(it)) continue;
            if (!it.isSimilar(template)) continue;
            collected += it.getAmount();
            contents[i] = null;
        }

        if (collected == 0) {
            plugin.getMessageManager().sendWithItem(player, "storage.deposit-none", template, null);
            return;
        }

        inv.setStorageContents(contents);
        entry.addCount(collected);
        finishTransaction(player, box, template, entry);
        plugin.getMessageManager().sendWithItem(player, "storage.deposit-all-success", template, Map.of(
                "amount", String.valueOf(collected),
                "total", String.valueOf(entry.getCount())
        ));
    }

    /** Case 5: withdraw as much as fits in the inventory. Always sends a chat message. */
    private void doWithdrawAll(Player player, ItemStack box, ItemStack template, StorageEntry entry) {
        long available = entry.getCount();
        if (available <= 0) {
            plugin.getMessageManager().sendWithItem(player, "storage.withdraw-empty", template, null);
            return;
        }

        int wanted = (int) Math.min(available, Integer.MAX_VALUE - 1);
        ItemStack give = template.clone();
        give.setAmount(wanted);

        // Inventory#addItem distributes an oversized amount across multiple stacks/slots on its
        // own; whatever doesn't fit is returned as leftover instead of being placed anywhere.
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(give);
        int notPlaced = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        int actuallyGiven = wanted - notPlaced;

        if (actuallyGiven <= 0) {
            plugin.getMessageManager().sendWithItem(player, "storage.withdraw-all-full", template, null);
            return;
        }

        entry.setCount(available - actuallyGiven);
        finishTransaction(player, box, template, entry);
        plugin.getMessageManager().sendWithItem(player, "storage.withdraw-all-success", template, Map.of(
                "amount", String.valueOf(actuallyGiven),
                "total", String.valueOf(entry.getCount())
        ));
    }

    // =========================================================================
    //  Actions: container (chest / shulker box / barrel) <-> storage
    //  (priority override for shift-clicks aimed at a nearby container - see class javadoc)
    // =========================================================================

    /** Shift+left on a nearby container: push storage's contents into it, as much as fits. Always sends a chat message. */
    private void doContainerPush(Player player, ItemStack box, ItemStack template, StorageEntry entry, Inventory containerInv) {
        long available = entry.getCount();
        if (available <= 0) {
            plugin.getMessageManager().sendWithItem(player, "container.push-empty", template, null);
            return;
        }

        int maxStack = Math.max(1, template.getType().getMaxStackSize());
        ItemStack[] contents = containerInv.getContents();
        long remaining = available;
        long moved = 0;

        // Top up existing matching stacks first...
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack it = contents[i];
            if (it != null && it.isSimilar(template) && it.getAmount() < maxStack) {
                int space = maxStack - it.getAmount();
                int add = (int) Math.min(space, remaining);
                it.setAmount(it.getAmount() + add);
                remaining -= add;
                moved += add;
            }
        }
        // ...then fill empty slots.
        for (int i = 0; i < contents.length && remaining > 0; i++) {
            if (contents[i] == null || contents[i].getType() == Material.AIR) {
                int add = (int) Math.min(maxStack, remaining);
                ItemStack put = template.clone();
                put.setAmount(add);
                contents[i] = put;
                remaining -= add;
                moved += add;
            }
        }

        if (moved == 0) {
            plugin.getMessageManager().sendWithItem(player, "container.push-full", template, null);
            return;
        }

        containerInv.setContents(contents);
        entry.setCount(available - moved);
        finishTransaction(player, box, template, entry);
        plugin.getMessageManager().sendWithItem(player, "container.push-success", template, Map.of(
                "amount", String.valueOf(moved),
                "total", String.valueOf(entry.getCount())
        ));
    }

    /** Shift+right on a nearby container: pull every matching item out of it into storage. Always sends a chat message. */
    private void doContainerPull(Player player, ItemStack box, ItemStack template, StorageEntry entry, Inventory containerInv) {
        ItemStack[] contents = containerInv.getContents();
        long collected = 0;

        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() == Material.AIR) continue;
            if (!it.isSimilar(template)) continue;
            collected += it.getAmount();
            contents[i] = null;
        }

        if (collected == 0) {
            plugin.getMessageManager().sendWithItem(player, "container.pull-none", template, null);
            return;
        }

        containerInv.setContents(contents);
        entry.addCount(collected);
        finishTransaction(player, box, template, entry);
        plugin.getMessageManager().sendWithItem(player, "container.pull-success", template, Map.of(
                "amount", String.valueOf(collected),
                "total", String.valueOf(entry.getCount())
        ));
    }

    // =========================================================================
    //  Case 1: "Use" the stored item (block placement / consuming)
    // =========================================================================

    /**
     * "Use" the stored item once: places a block, eats food, drinks a potion, empties a
     * water/lava bucket, drinks milk, or throws a spawn egg - mirroring the most common
     * vanilla right-click interactions, and returning the resulting empty container
     * (glass bottle / bucket) to the player's inventory just like vanilla does. Anything not
     * covered here is a safe no-op, matching vanilla's own behaviour of doing nothing for
     * items with no right-click action. No chat message on success (only on failure).
     */
    private void doUse(Player player, PlayerInteractEvent event, ItemStack template, StorageEntry entry) {
        if (entry.getCount() <= 0) {
            plugin.getMessageManager().sendWithItem(player, "storage.use-empty", template, null);
            return;
        }

        Material mat = template.getType();
        boolean consumed = false;
        Block clicked = event.getClickedBlock();

        if (mat.isBlock() && event.getAction() == Action.RIGHT_CLICK_BLOCK && clicked != null) {
            Block target = clicked.getRelative(event.getBlockFace());
            if (canPlaceAt(target)) {
                target.setType(mat);
                consumed = true;
            }
        } else if (isEdible(mat)) {
            applyFood(player, template);
            consumed = true;
        } else if (mat == Material.POTION) {
            applyPotion(player, template);
            giveContainer(player, Material.GLASS_BOTTLE);
            consumed = true;
        } else if (mat == Material.MILK_BUCKET) {
            for (PotionEffect eff : player.getActivePotionEffects()) {
                player.removePotionEffect(eff.getType());
            }
            giveContainer(player, Material.BUCKET);
            consumed = true;
        } else if ((mat == Material.WATER_BUCKET || mat == Material.LAVA_BUCKET) && clicked != null) {
            Block target = clicked.getRelative(event.getBlockFace());
            if (canPlaceAt(target)) {
                target.setType(mat == Material.WATER_BUCKET ? Material.WATER : Material.LAVA);
                giveContainer(player, Material.BUCKET);
                consumed = true;
            }
        } else if (mat.name().endsWith("_SPAWN_EGG") && clicked != null) {
            EntityType type = spawnEggEntityType(mat);
            if (type != null) {
                World world = clicked.getWorld();
                Location loc = clicked.getRelative(event.getBlockFace()).getLocation().add(0.5, 0.1, 0.5);
                world.spawnEntity(loc, type);
                consumed = true;
            }
        }

        if (consumed) {
            entry.setCount(entry.getCount() - 1);
            finishTransaction(player, player.getInventory().getItemInMainHand(), template, entry);
        }
    }

    private void giveContainer(Player player, Material containerMaterial) {
        ItemStack container = new ItemStack(containerMaterial, 1);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(container);
        for (ItemStack l : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), l);
        }
    }

    private boolean canPlaceAt(Block block) {
        return block.isEmpty() || block.isLiquid();
    }

    private boolean isEdible(Material mat) {
        try {
            return mat.isEdible();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Applies a generic, approximate "eating" effect. Reading the exact nutrition/saturation
     * values of an arbitrary food item requires the (currently @Experimental and fast-moving)
     * FOOD data component API; to keep this plugin stable across Paper builds we use a fixed,
     * reasonable approximation instead. Adjust the constants below if you want closer parity
     * with vanilla per-item nutrition values.
     */
    private void applyFood(Player player, ItemStack template) {
        int nutrition = 4;
        float saturation = 0.3f;
        player.setFoodLevel(Math.min(20, player.getFoodLevel() + nutrition));
        player.setSaturation(Math.min(20f, player.getSaturation() + saturation));
    }

    private void applyPotion(Player player, ItemStack template) {
        if (!(template.getItemMeta() instanceof PotionMeta meta)) return;
        PotionType base = meta.getBasePotionType();
        if (base != null) {
            for (PotionEffect effect : base.getPotionEffects()) {
                player.addPotionEffect(effect);
            }
        }
        for (PotionEffect effect : meta.getCustomEffects()) {
            player.addPotionEffect(effect);
        }
    }

    private EntityType spawnEggEntityType(Material mat) {
        String name = mat.name().replace("_SPAWN_EGG", "");
        try {
            return EntityType.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void finishTransaction(Player player, ItemStack box, ItemStack template, StorageEntry entry) {
        itemUtil.refreshDisplay(box, template, entry);
        plugin.getStorageDataManager().save(itemUtil.getOwner(box) != null ? itemUtil.getOwner(box) : player.getUniqueId());
        plugin.refreshMatchingBoxes(player, template, entry);
        // Tell the auto-collect poller about this plugin-caused inventory change so it doesn't
        // mistake items we just gave the player (e.g. via withdraw) for a fresh external pickup.
        plugin.getAutoCollectPoller().resync(player, template);
    }
}
