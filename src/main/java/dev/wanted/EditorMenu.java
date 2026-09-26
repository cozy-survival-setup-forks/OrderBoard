package dev.wanted;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Post a new request: pick the item, the amount and the price for each, then confirm. */
final class EditorMenu extends Menu {

    private static final int HELD = 10, ITEM = 12, AMOUNT = 14, PRICE = 16, BACK = 18, CONFIRM = 22;

    private ItemStack item;
    private int amount = 1;
    private double price;

    EditorMenu(WantedPlugin plugin, Player viewer) {
        super(plugin, viewer, 27, "title-new");
        this.item = Matching.template(viewer.getInventory().getItemInMainHand());
        this.price = Math.max(plugin.settings().minPrice, 1);
    }

    @Override
    void render() {
        Settings settings = plugin.settings();
        inventory.clear();
        fillBottom(0);

        inventory.setItem(HELD, button(Material.PAPER, "editor-held", "editor-held-lore"));
        inventory.setItem(ITEM, item == null ? button(Material.STRUCTURE_VOID, "editor-no-item", "editor-no-item-lore")
                : withLore(item, List.of(line("editor-item-lore"))));
        inventory.setItem(AMOUNT, button(Material.CHEST_MINECART, "editor-amount", "editor-amount-lore", "amount", count(amount)));
        inventory.setItem(PRICE, button(Material.GOLD_INGOT, "editor-price", "editor-price-lore", "price", settings.money(price)));
        inventory.setItem(BACK, button(Material.BARRIER, "button-back", (String) null));

        double subtotal = Money.round(amount * price);
        double tax = Money.round(subtotal * settings.taxPercent / 100D);
        List<Component> lore = new java.util.ArrayList<>();
        for (String text : plugin.messages().lines("editor-confirm-lore")) {
            lore.add(noItalic(plugin.messages().parse(text, "subtotal", settings.money(subtotal), "tax", settings.money(tax),
                    "total", settings.money(Money.round(subtotal + tax)), "percent", String.valueOf(settings.taxPercent))));
        }
        inventory.setItem(CONFIRM, button(item == null ? Material.RED_CONCRETE : Material.LIME_CONCRETE, "editor-confirm", lore));
    }

    @Override
    void click(int slot, ClickType click) {
        if (slot == HELD) {
            item = Matching.template(viewer.getInventory().getItemInMainHand());
            if (item == null) plugin.messages().send(viewer, "invalid-item");
        } else if (slot == AMOUNT) {
            plugin.ask(viewer, "prompt-amount", input -> {
                Integer parsed = input == null ? null : WantedPlugin.parseAmount(input);
                if (input != null && parsed == null) plugin.messages().send(viewer, "invalid-amount", "max", count(plugin.settings().maxAmount));
                else if (parsed != null) amount = Math.min(parsed, plugin.settings().maxAmount);
                open();
            });
            return;
        } else if (slot == PRICE) {
            plugin.ask(viewer, "prompt-price", input -> {
                Double parsed = input == null ? null : WantedPlugin.parsePrice(input);
                if (input != null && parsed == null) plugin.messages().send(viewer, "invalid-price", "min", plugin.settings().money(plugin.settings().minPrice), "max", plugin.settings().money(plugin.settings().maxPrice));
                else if (parsed != null) price = Math.max(plugin.settings().minPrice, Math.min(parsed, plugin.settings().maxPrice));
                open();
            });
            return;
        } else if (slot == CONFIRM) {
            if (plugin.create(viewer, item, amount, price)) {
                new MineMenu(plugin, viewer).open();
                return;
            }
        } else if (slot == BACK) {
            new MarketMenu(plugin, viewer, null).open();
            return;
        }
        render();
    }
}
