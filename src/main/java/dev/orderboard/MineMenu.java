package dev.orderboard;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** The player's own requests: collect what was delivered, or cancel and get the rest of the money back. */
final class MineMenu extends Menu {

    private static final int COLLECT_ALL = 47, BACK = 49;

    private final List<Order> shown = new ArrayList<>();
    private int page;

    MineMenu(OrderBoardPlugin plugin, Player viewer) {
        super(plugin, viewer, 54, "title-mine");
    }

    @Override
    void render() {
        shown.clear();
        for (Order order : plugin.orders().all()) if (order.owner.equals(viewer.getUniqueId())) shown.add(order);
        shown.sort(Comparator.comparingLong((Order o) -> o.created).reversed());
        redraw();
    }

    /** Redraws the current page without touching which requests are shown or their order - see
     * MarketMenu.redraw() for why the periodic refresh timer must use this instead of render(). */
    void redraw() {
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
        inventory.setItem(COLLECT_ALL, button(Material.HOPPER, "button-collect-all", "button-collect-all-lore",
                "waiting", String.valueOf(plugin.orders().waiting(viewer.getUniqueId()))));
        inventory.setItem(BACK, button(Material.BARRIER, "button-back", (String) null));
    }

    private ItemStack icon(Order order) {
        Settings settings = plugin.settings();
        List<Component> lore = new ArrayList<>();
        lore.add(line(order.open ? "mine-open" : "mine-closed"));
        lore.add(line("order-price", "price", settings.money(order.price)));
        lore.add(line("mine-progress", "filled", count(order.filled), "amount", count(order.amount)));
        lore.add(line("mine-stored", "stored", count(order.stored())));
        if (order.open && order.expires > 0) lore.add(line("order-expires", "time", duration(order.expires - System.currentTimeMillis())));
        lore.add(Component.empty());
        if (order.stored() > 0) lore.add(line("mine-collect", "stored", count(order.stored())));
        if (order.open) lore.add(line("mine-cancel", "refund", settings.money(order.refund())));
        return withLore(order.item, lore);
    }

    @Override
    void click(int slot, ClickType click) {
        if (slot < ITEM_SLOTS) {
            int index = page * ITEM_SLOTS + slot;
            if (index < shown.size()) {
                Order order = shown.get(index);
                if (click.isRightClick()) plugin.cancel(viewer, order);
                else plugin.collect(viewer, order);
            }
        } else if (slot == PREV) {
            page--;
        } else if (slot == NEXT) {
            page++;
        } else if (slot == COLLECT_ALL) {
            for (Order order : new ArrayList<>(shown)) if (!plugin.collect(viewer, order)) break;
        } else if (slot == BACK) {
            new MarketMenu(plugin, viewer, null).open();
            return;
        }
        render();
    }
}
