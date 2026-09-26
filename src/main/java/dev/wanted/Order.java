package dev.wanted;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** One request: this many of that exact item, at this price each. Money for the missing items is held by the plugin. */
final class Order {

    final long id;
    final UUID owner;
    final String ownerName;
    /** What is wanted, one of it. */
    final ItemStack item;
    final int amount;
    final double price;
    final long created;
    final long expires;

    int filled;
    int collected;
    boolean open;

    Order(long id, UUID owner, String ownerName, ItemStack item, int amount, int filled, int collected, double price,
          boolean open, long created, long expires) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.item = item;
        this.amount = amount;
        this.filled = filled;
        this.collected = collected;
        this.price = price;
        this.open = open;
        this.created = created;
        this.expires = expires;
    }

    /** Items still wanted. */
    int remaining() {
        return open ? amount - filled : 0;
    }

    /** Delivered but not taken by the owner yet. */
    int stored() {
        return filled - collected;
    }

    /** The money the owner gets back for what was never delivered. */
    double refund() {
        return Money.round(remaining() * price);
    }
}
