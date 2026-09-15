package me.petoma21.storageBox.commands;

import me.petoma21.storageBox.ItemUtil;
import me.petoma21.storageBox.StorageBox;
import me.petoma21.storageBox.StorageEntry;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StbCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN_PERMISSION = "storagebox.admin";

    private final StorageBox plugin;
    private final ItemUtil itemUtil;

    public StbCommand(StorageBox plugin) {
        this.plugin = plugin;
        this.itemUtil = plugin.getItemUtil();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            plugin.getMessageManager().send(sender, "general.unknown-command");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give" -> handleGive(sender, args);
            case "reload" -> handleReload(sender);
            case "autocollect" -> handleAutocollect(sender);
            default -> plugin.getMessageManager().send(sender, "general.unknown-command");
        }
        return true;
    }

    // -------------------------------------------------------------------
    // /stb give <player> [amount]   (OP only)
    // -------------------------------------------------------------------
    private void handleGive(CommandSender sender, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            plugin.getMessageManager().send(sender, "general.no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().send(sender, "give.usage");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.getMessageManager().send(sender, "give.player-not-found", Map.of("player", args[1]));
            return;
        }

        int amount = 1;
        if (args.length >= 3) {
            try {
                amount = Integer.parseInt(args[2]);
                if (amount < 1) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                plugin.getMessageManager().send(sender, "give.invalid-amount");
                return;
            }
        }

        ItemStack box = itemUtil.createUnregisteredBox(amount);
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(box);
        for (ItemStack l : leftover.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), l);
        }

        plugin.getMessageManager().send(sender, "give.success-sender", Map.of(
                "player", target.getName(),
                "amount", String.valueOf(amount)
        ));
        if (!sender.equals(target)) {
            plugin.getMessageManager().send(target, "give.success-target", Map.of("amount", String.valueOf(amount)));
        }
    }

    // -------------------------------------------------------------------
    // /stb reload   (OP only)
    // -------------------------------------------------------------------
    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            plugin.getMessageManager().send(sender, "general.no-permission");
            return;
        }
        plugin.reloadAll();
        plugin.getMessageManager().send(sender, "general.reload-success");
    }

    // -------------------------------------------------------------------
    // /stb autocollect   (anyone, must hold a registered StorageBox)
    // -------------------------------------------------------------------
    private void handleAutocollect(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().send(sender, "general.player-only");
            return;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!itemUtil.isRegistered(hand)) {
            plugin.getMessageManager().send(player, "autocollect.not-holding");
            return;
        }

        ItemStack template = itemUtil.getTemplate(hand);
        UUID owner = itemUtil.getOwner(hand);
        StorageEntry entry = plugin.getStorageDataManager().getOrCreateEntry(owner, template);

        entry.setAutocollect(!entry.isAutocollect());
        itemUtil.refreshDisplay(hand, template, entry);
        plugin.getStorageDataManager().save(owner);
        plugin.refreshMatchingBoxes(player, template, entry);

        String key = entry.isAutocollect() ? "autocollect.on" : "autocollect.off";
        plugin.getMessageManager().sendWithItem(player, key, template, null);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("give", "reload", "autocollect")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }
}
