package dev.orderboard;

import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * /orders opens the market. /orders new [item] amount price posts a request, mine and collect are for what was
 * delivered, and any other word is a search.
 */
final class OrderBoardCommand implements TabExecutor {

    private static final List<String> WORDS = List.of("new", "mine", "collect");

    private final OrderBoardPlugin plugin;

    OrderBoardCommand(OrderBoardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        Messages messages = plugin.messages();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);

        if (first.equals("reload") || first.equals("remove") || first.equals("list")) {
            if (!sender.hasPermission("orderboard.admin")) {
                messages.send(sender, "no-permission");
            } else if (first.equals("reload")) {
                messages.send(sender, plugin.reload() ? "reloaded" : "reload-failed");
            } else if (first.equals("remove")) {
                remove(sender, args);
            } else {
                list(sender, args.length > 1 ? args[1] : null);
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            messages.send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("orderboard.use")) {
            messages.send(player, "no-permission");
            return true;
        }

        switch (first) {
            case "" -> new MarketMenu(plugin, player, null).open();
            case "mine" -> new MineMenu(plugin, player).open();
            case "collect" -> {
                for (Order order : new ArrayList<>(plugin.orders().all())) {
                    if (order.owner.equals(player.getUniqueId()) && !plugin.collect(player, order)) break;
                }
            }
            case "new" -> create(player, args);
            default -> new MarketMenu(plugin, player, String.join(" ", args)).open();
        }
        return true;
    }

    /** new, new amount price, new item amount price. */
    private void create(Player player, String[] args) {
        Messages messages = plugin.messages();
        if (args.length == 1) {
            new EditorMenu(plugin, player).open();
            return;
        }

        ItemStack item;
        int at = 1;
        if (args.length == 4) {
            Material material = Material.matchMaterial(args[1]);
            if (material == null || !material.isItem() || material.isAir()) {
                messages.send(player, "invalid-item");
                return;
            }
            item = new ItemStack(material);
            at = 2;
        } else if (args.length == 3) {
            item = player.getInventory().getItemInMainHand();
        } else {
            messages.send(player, "usage");
            return;
        }

        Integer amount = OrderBoardPlugin.parseAmount(args[at]);
        Double price = OrderBoardPlugin.parsePrice(args[at + 1]);
        if (amount == null) messages.send(player, "invalid-amount", "max", Menu.count(plugin.settings().maxAmount));
        else if (price == null) messages.send(player, "invalid-price", "min", plugin.settings().money(plugin.settings().minPrice), "max", plugin.settings().money(plugin.settings().maxPrice));
        else plugin.create(player, item, amount, price);
    }

    private void remove(CommandSender sender, String[] args) {
        Order order = null;
        if (args.length > 1) {
            try {
                order = plugin.orders().get(Long.parseLong(args[1].replace("#", "")));
            } catch (NumberFormatException ignored) {
                // falls through to the usage message
            }
        }
        if (order == null) plugin.messages().send(sender, "usage-remove");
        else plugin.remove(sender, order);
    }

    /** Console has no GUI to see request ids in - this is the only way to find one to pass to remove. */
    private void list(CommandSender sender, String ownerFilter) {
        List<Order> matching = new ArrayList<>(plugin.orders().all());
        matching.removeIf(order -> ownerFilter != null && !order.ownerName.equalsIgnoreCase(ownerFilter));
        if (matching.isEmpty()) {
            plugin.messages().send(sender, "list-empty");
            return;
        }
        plugin.messages().send(sender, "list-header", "count", String.valueOf(matching.size()));
        for (Order order : matching) {
            sender.sendMessage("#" + order.id + " " + order.ownerName + " - " + Matching.name(order.item)
                    + " x" + order.remaining() + "/" + order.amount + " @ " + plugin.settings().money(order.price)
                    + (order.open ? "" : " (closed)"));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        if (args.length == 1) {
            out.addAll(WORDS);
            if (sender.hasPermission("orderboard.admin")) out.addAll(List.of("remove", "reload", "list"));
        } else if (args[0].equalsIgnoreCase("new") && args.length == 2 && !last.matches("\\d.*")) {
            Arrays.stream(Material.values()).filter(m -> m.isItem() && !m.isAir()).map(m -> m.name().toLowerCase(Locale.ROOT)).forEach(out::add);
        } else if (args[0].equalsIgnoreCase("remove") && args.length == 2 && sender.hasPermission("orderboard.admin")) {
            for (Order order : plugin.orders().all()) out.add(String.valueOf(order.id));
        } else if (args[0].equalsIgnoreCase("list") && args.length == 2 && sender.hasPermission("orderboard.admin")) {
            org.bukkit.Bukkit.getOnlinePlayers().forEach(player -> out.add(player.getName()));
        }
        out.removeIf(word -> !word.startsWith(last));
        return out.size() > 50 ? out.subList(0, 50) : out;
    }
}
