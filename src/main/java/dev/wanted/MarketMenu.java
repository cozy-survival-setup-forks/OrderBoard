package dev.wanted;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Every open request, a page at a time. Click one to deliver what you carry. */
final class MarketMenu extends Menu {

    private enum Sort {
        NEWEST("sort-newest", Comparator.comparingLong((Order o) -> o.created).reversed()),
        PRICE("sort-price", Comparator.comparingDouble((Order o) -> o.price).reversed()),
        LEFT("sort-left", Comparator.comparingInt(Order::remaining).reversed());

        final String key;
        final Comparator<Order> order;

        Sort(String key, Comparator<Order> order) {
            this.key = key;
            this.order = order;
        }
    }

    private static final int SORT = 46, SEARCH = 47, MINE = 49, NEW = 51;

    private final List<Order> shown = new ArrayList<>();
    private Sort sort = Sort.NEWEST;
    private String search;
    private int page;

    MarketMenu(WantedPlugin plugin, Player viewer, String search) {
        super(plugin, viewer, 54, "title-market");
        this.search = search == null || search.isBlank() ? null : search.toLowerCase(Locale.ROOT);
    }

    @Override
    void render() {
        shown.clear();
        for (Order order : plugin.orders().all()) {
            if (order.open && order.remaining() > 0 && matchesSearch(order)) shown.add(order);
        }
        shown.sort(sort.order);

        int pages = Math.max(1, (shown.size() + ITEM_SLOTS - 1) / ITEM_SLOTS);
        page = Math.max(0, Math.min(page, pages - 1));

        inventory.clear();
        int first = page * ITEM_SLOTS;
        for (int slot = 0; slot < ITEM_SLOTS && first + slot < shown.size(); slot++) {
            inventory.setItem(slot, icon(shown.get(first + slot)));
        }

        fillBottom(ITEM_SLOTS);
        if (page > 0) inventory.setItem(PREV, button(Material.ARROW, "button-prev", (String) null, "page", String.valueOf(page), "pages", String.valueOf(pages)));
        if (page < pages - 1) inventory.setItem(NEXT, button(Material.ARROW, "button-next", (String) null, "page", String.valueOf(page + 2), "pages", String.valueOf(pages)));
        inventory.setItem(SORT, button(Material.COMPARATOR, "button-sort", "button-sort-lore", "sort", plugin.messages().raw(sort.key)));
        inventory.setItem(SEARCH, button(Material.SPYGLASS, "button-search", "button-search-lore", "search", search == null ? "-" : search));
        inventory.setItem(MINE, button(Material.CHEST, "button-mine", "button-mine-lore", "waiting", String.valueOf(plugin.orders().waiting(viewer.getUniqueId()))));
        inventory.setItem(NEW, button(Material.WRITABLE_BOOK, "button-new", "button-new-lore"));
    }

    private boolean matchesSearch(Order order) {
        if (search == null) return true;
        return Matching.name(order.item).toLowerCase(Locale.ROOT).contains(search)
                || order.item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ').contains(search);
    }

    private ItemStack icon(Order order) {
        Settings settings = plugin.settings();
        int have = Orders.have(viewer.getInventory(), order.item);
        boolean own = order.owner.equals(viewer.getUniqueId());

        List<Component> lore = new ArrayList<>();
        lore.add(line("order-owner", "owner", order.ownerName));
        lore.add(line("order-price", "price", settings.money(order.price)));
        lore.add(line("order-needed", "left", count(order.remaining()), "amount", count(order.amount)));
        if (order.expires > 0) lore.add(line("order-expires", "time", duration(order.expires - System.currentTimeMillis())));
        lore.add(Component.empty());
        if (own) {
            lore.add(line("order-own"));
        } else if (have > 0) {
            int n = Math.min(have, order.remaining());
            lore.add(line("order-have", "have", count(have)));
            lore.add(line("order-deliver", "count", count(n), "money", settings.money(Money.round(n * order.price))));
        } else {
            lore.add(line("order-none"));
        }
        if (viewer.hasPermission("wanted.admin")) lore.add(line("order-admin", "id", String.valueOf(order.id)));
        return withLore(order.item, lore);
    }

    @Override
    void click(int slot, ClickType click) {
        if (slot < ITEM_SLOTS) {
            int index = page * ITEM_SLOTS + slot;
            if (index < shown.size()) {
                Order order = shown.get(index);
                if (click == ClickType.SHIFT_LEFT && viewer.hasPermission("wanted.admin")) plugin.remove(viewer, order);
                else plugin.deliver(viewer, order);
            }
        } else if (slot == PREV) {
            page--;
        } else if (slot == NEXT) {
            page++;
        } else if (slot == SORT) {
            sort = Sort.values()[(sort.ordinal() + 1) % Sort.values().length];
            page = 0;
        } else if (slot == SEARCH) {
            if (click.isRightClick()) {
                search = null;
                page = 0;
            } else {
                plugin.ask(viewer, "prompt-search", input -> {
                    if (input != null) {
                        search = input.isBlank() ? null : input.toLowerCase(Locale.ROOT);
                        page = 0;
                    }
                    open();
                });
                return;
            }
        } else if (slot == MINE) {
            new MineMenu(plugin, viewer).open();
            return;
        } else if (slot == NEW) {
            new EditorMenu(plugin, viewer).open();
            return;
        }
        render();
    }
}
