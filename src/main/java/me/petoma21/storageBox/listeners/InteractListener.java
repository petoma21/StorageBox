package me.petoma21.storageBox.listeners;

import me.petoma21.storageBox.ItemUtil;
import me.petoma21.storageBox.StorageBox;
import me.petoma21.storageBox.StorageEntry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Barrel;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

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

    /**
     * Debounces a single physical click from being registered as multiple actions. This does
     * NOT limit how fast you can click on purpose - the default (2 ticks / 100ms) sits well
     * below a deliberate 3-5 clicks/sec rapid-click pace (200-330ms apart), so genuine repeated
     * clicks always go through; it only swallows a click being misread as more than one action.
     *
     * Note: we intentionally do NOT use Bukkit's native Player#setCooldown/hasCooldown here.
     * Paper's cooldown system is now backed by the USE_COOLDOWN data component, which arbitrary
     * items (a plain gold block, a chest, etc.) do not define a cooldown group for by default -
     * meaning setCooldown() can silently no-op for our items. Tracking timestamps ourselves
     * guarantees the debounce actually applies regardless of the item's own component data.
     * There is no server-side "wait for button release" signal in the Minecraft protocol at
     * all - the client only ever sends discrete per-click packets - so a short, tunable window
     * (anti-spam.cooldown-ticks in config.yml) is the only mechanism available; adjust it if
     * your testing shows it's too strict or too lax for your playerbase/latency.
     */
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

    /**
     * Blocks anyone other than the registering owner from using a registered StorageBox at all.
     * Sends the "not yours" message (including the owner's name) and returns false if denied.
     */
    private boolean checkOwner(Player player, UUID owner) {
        if (owner.equals(player.getUniqueId())) {
            return true;
        }
        String ownerName = Bukkit.getOfflinePlayer(owner).getName();
        if (ownerName == null) {
            ownerName = owner.toString();
        }
        plugin.getMessageManager().send(player, "storage.not-owner", Map.of("owner", ownerName));
        return false;
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
            if (!checkOwner(player, owner)) return;
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

        if (!itemUtil.isRegistered(hand)) {
            // Note: this event fires "pre-cancelled" by the server whenever vanilla would do
            // nothing (e.g. right-clicking air with a plain block item) - that's why this handler
            // explicitly uses ignoreCancelled = false, otherwise those clicks would never reach us.
            event.setCancelled(true);
            if (onCooldown(player)) return;
            plugin.playConfiguredSound(player, plugin.getConfigManager().getOpenSound());
            guiListener.openRegisterGui(player, EquipmentSlot.HAND);
            return;
        }

        if (onCooldown(player)) return;

        ItemStack template = itemUtil.getTemplate(hand);
        UUID owner = itemUtil.getOwner(hand);
        if (template == null || owner == null) {
            event.setCancelled(true);
            return;
        }
        if (!checkOwner(player, owner)) {
            event.setCancelled(true);
            return;
        }
        StorageEntry entry = plugin.getStorageDataManager().getOrCreateEntry(owner, template);

        if (player.isSneaking()) {
            event.setCancelled(true);
            Inventory containerInv = action == Action.RIGHT_CLICK_BLOCK
                    ? nearbyContainerInventory(player, event.getClickedBlock())
                    : null;
            if (containerInv != null) {
                doContainerPull(player, hand, template, entry, containerInv); // priority override
            } else {
                doDepositAll(player, hand, template, entry); // case 4
            }
        } else if (isWithinUseRange(player, event.getClickedBlock())) {
            // case 1: deliberately NOT calling event.setCancelled(true) here - see doUse().
            doUse(player, event, template, entry);
        } else {
            event.setCancelled(true);
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
        if (!checkOwner(player, owner)) return;
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
    //  Case 1: "Use" the stored item - let VANILLA do the real work
    // =========================================================================

    /**
     * Instead of hand-simulating every possible item behaviour (which can never cover every
     * special case - rotten flesh's hunger effect, golden apple's absorption, chorus fruit's
     * teleport, firework elytra boosts, correct block orientation, etc.), we let vanilla process
     * the box's OWN real item completely normally - its Material AND full ItemMeta already match
     * the registered item exactly (see ItemUtil#register), so vanilla's own logic handles it
     * perfectly. We only deny the CLICKED BLOCK's own interaction (so casually pointing at a
     * nearby chest/door/button while using the box doesn't open it), and otherwise don't cancel
     * anything - then we look at what vanilla actually did (see {@link #handleUseAftermath}) and
     * convert it into "decrement storage by 1, restore the box" instead of the box being consumed
     * for real. This is also why we don't need a chat message here: whatever it does is exactly
     * what using the real item would do (or, if it fails - full hunger, invalid placement, etc. -
     * exactly what NOT being able to use it would look like too).
     */
    private void doUse(Player player, PlayerInteractEvent event, ItemStack template, StorageEntry entry) {
        if (entry.getCount() <= 0) {
            event.setCancelled(true);
            plugin.getMessageManager().sendWithItem(player, "storage.use-empty", template, null);
            return;
        }

        event.setUseInteractedBlock(Event.Result.DENY);

        ItemStack before = player.getInventory().getItemInMainHand().clone();
        Bukkit.getScheduler().runTask(plugin, () -> handleUseAftermath(player, before, template, entry));
    }

    /**
     * Also catches food/potions/milk/honey/suspicious stew/etc. - these have a multi-tick
     * eating/drinking animation, so the 1-tick check scheduled by {@link #doUse} runs too early
     * (the item is still "in use" at that point, so it correctly sees no change and does
     * nothing). This event fires exactly when the consumption actually completes, whether that
     * takes 1 tick or 32.
     */
    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        ItemStack consumedItem = event.getItem();
        if (!itemUtil.isRegistered(consumedItem)) return;

        ItemStack template = itemUtil.getTemplate(consumedItem);
        UUID owner = itemUtil.getOwner(consumedItem);
        if (template == null || owner == null || !owner.equals(player.getUniqueId())) return;

        StorageEntry entry = plugin.getStorageDataManager().getOrCreateEntry(owner, template);
        ItemStack snapshot = consumedItem.clone();
        Bukkit.getScheduler().runTask(plugin, () -> handleUseAftermath(player, snapshot, template, entry));
    }

    /**
     * Compares the main-hand item to what it was before letting vanilla process the click. If
     * fewer of our box are there now (it was fully consumed, or a stack got smaller), that
     * difference is what storage actually lost - vanilla's own byproduct (empty bucket, glass
     * bottle), if any, is preserved and given back rather than being overwritten, and the box
     * itself is restored to its original amount with an updated, refreshed display.
     */
    private void handleUseAftermath(Player player, ItemStack before, ItemStack template, StorageEntry entry) {
        ItemStack after = player.getInventory().getItemInMainHand();

        boolean afterIsSameBox = after != null && itemUtil.isRegistered(after)
                && itemUtil.getOwner(after) != null && itemUtil.getOwner(after).equals(itemUtil.getOwner(before));

        int consumed = before.getAmount() - (afterIsSameBox ? after.getAmount() : 0);
        if (consumed <= 0) return; // unchanged - nothing happened yet, or the action failed/did nothing

        ItemStack byproduct = (!afterIsSameBox && after != null && after.getType() != Material.AIR) ? after.clone() : null;

        entry.setCount(Math.max(0, entry.getCount() - consumed));
        ItemStack restoredBox = before.clone();
        player.getInventory().setItemInMainHand(restoredBox);

        if (byproduct != null) {
            giveOrDrop(player, byproduct);
        }

        finishTransaction(player, restoredBox, template, entry);
    }

    private void giveOrDrop(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack l : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), l);
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