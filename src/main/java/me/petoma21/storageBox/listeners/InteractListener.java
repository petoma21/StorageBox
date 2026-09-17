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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class InteractListener implements Listener {

    private static final double USE_RANGE_BLOCKS = 4.5;

    private final StorageBox plugin;
    private final ItemUtil itemUtil;
    private final GuiListener guiListener;

    /** UUID -> timestamp (millis) of the last processed click/action, used for debouncing. */
    private final Map<UUID, Long> lastActionMillis = new HashMap<>();

    /** Players who currently have ANY inventory GUI open (their own inventory, a chest, etc.). */
    private final Set<UUID> playersWithGuiOpen = new HashSet<>();
    /** UUID -> timestamp (millis) a GUI last closed for that player, for a short post-close grace period. */
    private final Map<UUID, Long> lastGuiCloseMillis = new HashMap<>();
    private static final long GUI_CLOSE_GRACE_MILLIS = 250L;

    public InteractListener(StorageBox plugin, GuiListener guiListener) {
        this.plugin = plugin;
        this.itemUtil = plugin.getItemUtil();
        this.guiListener = guiListener;
    }

    /**
     * Tracks whenever ANY inventory screen is open for a player (their own inventory, a chest,
     * a crafting table, etc. - not just our own GUIs) so that world-interaction handlers below
     * can refuse to act while one is open, plus for a short grace period right after it closes.
     * <p>
     * IMPORTANT: InventoryOpenEvent does NOT fire for a player's own inventory (pressing E) -
     * that's a known Bukkit limitation; it only fires for "real" containers like chests. So we
     * also mark the flag on the very first InventoryClickEvent/InventoryDragEvent we see for a
     * player (picking an item up is itself a click, which always happens before a "drop outside"
     * click), which reliably covers the own-inventory case too.
     */
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player) {
            playersWithGuiOpen.add(player.getUniqueId());
        }
    }

    @EventHandler
    public void onAnyInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            playersWithGuiOpen.add(player.getUniqueId());
        }
    }

    @EventHandler
    public void onAnyInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            playersWithGuiOpen.add(player.getUniqueId());
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            playersWithGuiOpen.remove(player.getUniqueId());
            lastGuiCloseMillis.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        playersWithGuiOpen.remove(id);
        lastGuiCloseMillis.remove(id);
        lastActionMillis.remove(id);
    }

    private boolean isGuiBusy(Player player) {
        UUID id = player.getUniqueId();
        if (playersWithGuiOpen.contains(id)) return true;
        Long lastClose = lastGuiCloseMillis.get(id);
        return lastClose != null && System.currentTimeMillis() - lastClose < GUI_CLOSE_GRACE_MILLIS;
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

    /** Returns whichever hand's item {@code hand} refers to. */
    private ItemStack getHandItem(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    /** Writes {@code item} back into whichever hand slot {@code hand} refers to. */
    private void setHandItem(Player player, EquipmentSlot hand, ItemStack item) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(item);
        } else {
            player.getInventory().setItemInMainHand(item);
        }
    }

    @EventHandler(ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (isGuiBusy(player)) return;

        // PlayerInteractEvent's hand tells us which hand vanilla itself decided is the "active"
        // one for this click - vanilla's own dual-wield priority already resolved this before the
        // event ever fires (main hand wins whenever it has anything actionable; the off-hand only
        // becomes active when the main hand doesn't). So we don't need to special-case "main hand
        // holds something else" here at all: if this event says OFF_HAND, that's because vanilla
        // itself chose to use the off-hand item for this click, and a StorageBox there should
        // behave exactly like it would in the main hand.
        EquipmentSlot activeHand = event.getHand();
        if (activeHand != EquipmentSlot.HAND && activeHand != EquipmentSlot.OFF_HAND) return;

        ItemStack hand = getHandItem(player, activeHand);
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
            guiListener.openRegisterGui(player, activeHand);
            return;
        }

        // If not sneaking and pointing at a block that has its own vanilla right-click behavior
        // (chest, furnace, crafting table, door, button, etc.), let it work completely normally
        // instead of triggering the StorageBox's use/placement - matching vanilla exactly for
        // this click. Sneaking is excluded from this rule (it still runs the container
        // pull/deposit-all logic above as before). We explicitly force ALLOW/DENY here rather
        // than just leaving the event untouched, since relying on default Result values proved
        // unreliable for guaranteeing the block's own interaction actually still runs.
        if (!player.isSneaking() && action == Action.RIGHT_CLICK_BLOCK
                && event.getClickedBlock() != null && event.getClickedBlock().getType().isInteractable()) {
            event.setUseInteractedBlock(Event.Result.ALLOW);
            event.setUseItemInHand(Event.Result.DENY);
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
            // case 1: deliberately NOT calling event.setCancelled(true) for the consumable branch - see doUse().
            doUse(player, event, template, entry, activeHand);
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
        if (isGuiBusy(player)) return;

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
    //  Case 1: "Use" the stored item
    // =========================================================================

    /**
     * Food, potions, milk, honey bottles, suspicious stew, etc. are let through to vanilla for
     * real processing (see class javadoc / onConsume below) - their nutrition/effects are too
     * varied and item-specific to safely hand-simulate. Everything else (blocks, buckets,
     * fireworks, spawn eggs) is cancelled and simulated manually here instead: letting vanilla
     * drive block placement turned out to be unreliable in practice (it stopped placing blocks
     * correctly), so those go back to the simple, previously-working direct approach.
     */
    private void doUse(Player player, PlayerInteractEvent event, ItemStack template, StorageEntry entry, EquipmentSlot hand) {
        if (entry.getCount() <= 0) {
            event.setCancelled(true);
            plugin.getMessageManager().sendWithItem(player, "storage.use-empty", template, null);
            return;
        }

        Material mat = template.getType();

        if (isConsumable(mat)) {
            // Don't cancel - let vanilla process the real eating/drinking. Only block the
            // clicked block's own interaction, same reasoning as the interactable-block check
            // above (so pointing near a chest while eating doesn't open it).
            event.setUseInteractedBlock(Event.Result.DENY);
            return;
        }

        // Everything else: cancel fully and simulate manually.
        event.setCancelled(true);

        boolean consumed = false;
        Block clicked = event.getClickedBlock();

        if (mat.isBlock() && event.getAction() == Action.RIGHT_CLICK_BLOCK && clicked != null) {
            Block target = clicked.getRelative(event.getBlockFace());
            if (canPlaceAt(target)) {
                target.setType(mat);
                consumed = true;
            }
        } else if (mat == Material.FIREWORK_ROCKET) {
            launchFirework(player, template);
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
                Location loc = clicked.getRelative(event.getBlockFace()).getLocation().add(0.5, 0.1, 0.5);
                clicked.getWorld().spawnEntity(loc, type);
                consumed = true;
            }
        }

        if (consumed) {
            entry.setCount(entry.getCount() - 1);
            finishTransaction(player, getHandItem(player, hand), template, entry);
        }
    }

    /** True for anything that goes through vanilla's eat/drink flow (fires PlayerItemConsumeEvent). */
    private boolean isConsumable(Material mat) {
        if (mat == Material.POTION || mat == Material.MILK_BUCKET) return true;
        try {
            return mat.isEdible();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean canPlaceAt(Block block) {
        return block.isEmpty() || block.isLiquid();
    }

    private void giveContainer(Player player, Material containerMaterial) {
        ItemStack container = new ItemStack(containerMaterial, 1);
        giveOrDrop(player, container);
    }

    /**
     * Launches a REAL firework rocket entity built from the registered rocket's own FireworkMeta
     * (so the explosion colors/shape/sound are identical to the genuine item), and applies an
     * elytra boost if the player is currently gliding.
     */
    private void launchFirework(Player player, ItemStack template) {
        Location loc = player.getLocation();
        Firework firework = player.getWorld().spawn(loc, Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();

        if (template.getItemMeta() instanceof FireworkMeta templateMeta) {
            meta.setPower(templateMeta.getPower());
            meta.clearEffects();
            meta.addEffects(templateMeta.getEffects());
        }
        firework.setFireworkMeta(meta);

        if (player.isGliding()) {
            Vector direction = player.getLocation().getDirection().normalize();
            double boost = 1.5 + (meta.getPower() * 0.5);
            player.setVelocity(player.getVelocity().add(direction.multiply(boost)));
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

    /**
     * Also catches food/potions/milk/honey/suspicious stew/etc. - these have a multi-tick
     * eating/drinking animation, so a simple 1-tick check would run too early (the item is still
     * "in use" at that point). This event fires exactly when the consumption actually completes,
     * whether that takes 1 tick or 32.
     */
    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        EquipmentSlot hand = event.getHand();
        if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) return;

        Player player = event.getPlayer();
        ItemStack consumedItem = event.getItem();
        if (!itemUtil.isRegistered(consumedItem)) return;

        ItemStack template = itemUtil.getTemplate(consumedItem);
        UUID owner = itemUtil.getOwner(consumedItem);
        if (template == null || owner == null || !owner.equals(player.getUniqueId())) return;

        StorageEntry entry = plugin.getStorageDataManager().getOrCreateEntry(owner, template);
        ItemStack snapshot = consumedItem.clone();
        Bukkit.getScheduler().runTask(plugin, () -> handleUseAftermath(player, snapshot, template, entry, hand));
    }

    /**
     * Compares the hand item to what it was before letting vanilla process a consumable
     * click. If fewer of our box are there now (it was fully consumed, or a stack got smaller),
     * that difference is what storage actually lost - vanilla's own byproduct (empty bucket,
     * glass bottle), if any, is preserved and given back rather than being overwritten, and the
     * box itself is restored to its original amount with an updated, refreshed display.
     */
    private void handleUseAftermath(Player player, ItemStack before, ItemStack template, StorageEntry entry, EquipmentSlot hand) {
        ItemStack after = getHandItem(player, hand);

        boolean afterIsSameBox = after != null && itemUtil.isRegistered(after)
                && itemUtil.getOwner(after) != null && itemUtil.getOwner(after).equals(itemUtil.getOwner(before));

        int consumed = before.getAmount() - (afterIsSameBox ? after.getAmount() : 0);
        if (consumed <= 0) return; // unchanged - nothing happened yet, or the action failed/did nothing

        ItemStack byproduct = (!afterIsSameBox && after != null && after.getType() != Material.AIR) ? after.clone() : null;

        entry.setCount(Math.max(0, entry.getCount() - consumed));
        ItemStack restoredBox = before.clone();
        setHandItem(player, hand, restoredBox);

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