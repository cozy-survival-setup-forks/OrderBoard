package dev.orderboard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A window. Nothing can be put in or taken out of it, every click is a button, so items can never end up inside.
 */
abstract class Menu implements InventoryHolder {

    static final int ITEM_SLOTS = 45;
    static final int PREV = 45, NEXT = 53;

    final OrderBoardPlugin plugin;
    final Player viewer;
    final Inventory inventory;

    Menu(OrderBoardPlugin plugin, Player viewer, int size, String titleKey) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.inventory = org.bukkit.Bukkit.createInventory(this, size, plugin.messages().component(titleKey));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** Draws the window again. */
    abstract void render();

    abstract void click(int slot, ClickType click);

    void open() {
        render();
        viewer.openInventory(inventory);
    }

    // ---------------------------------------------------------------- pieces

    ItemStack button(Material material, String nameKey, List<Component> lore, String... pairs) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(nameKey == null ? Component.empty() : noItalic(plugin.messages().component(nameKey, pairs)));
        if (lore != null) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    ItemStack button(Material material, String nameKey, String loreKey, String... pairs) {
        List<Component> lore = null;
        if (loreKey != null) {
            lore = new ArrayList<>();
            for (String line : plugin.messages().lines(loreKey)) lore.add(noItalic(plugin.messages().parse(line, pairs)));
        }
        return button(material, nameKey, lore, pairs);
    }

    /** The shown item with more lines under its own. */
    ItemStack withLore(ItemStack item, List<Component> extra) {
        ItemStack icon = item.clone();
        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.addAll(extra);
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    Component line(String key, String... pairs) {
        return noItalic(plugin.messages().component(key, pairs));
    }

    void fillBottom(int from) {
        ItemStack filler = button(Material.GRAY_STAINED_GLASS_PANE, null, (List<Component>) null);
        for (int slot = from; slot < inventory.getSize(); slot++) inventory.setItem(slot, filler);
    }

    static Component noItalic(Component component) {
        return component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    static String count(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** 3d 4h, 5h 10m, 12m. */
    static String duration(long millis) {
        long minutes = Math.max(1, millis / 60_000);
        long days = minutes / 1440, hours = minutes / 60 % 24, mins = minutes % 60;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + mins + "m";
        return mins + "m";
    }
}
