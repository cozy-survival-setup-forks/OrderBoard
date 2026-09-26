package dev.wanted;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * What counts as the wanted item: the very same item, name, lore, enchantments, potion and all, and for tools
 * no more worn than the one that was asked for. What the owner gets is that same item, never a different one.
 */
final class Matching {

    /** Larger than this and the request is refused, so nobody stores a huge book in the database. */
    static final int MAX_BYTES = 8192;

    private Matching() {
    }

    static boolean matches(ItemStack wanted, ItemStack offered) {
        if (offered == null || offered.getType() != wanted.getType()) return false;

        ItemStack a = wanted.clone();
        ItemStack b = offered.clone();
        a.setAmount(1);
        b.setAmount(1);

        if (a.getItemMeta() instanceof Damageable wantedMeta && b.getItemMeta() instanceof Damageable offeredMeta) {
            if (offeredMeta.getDamage() > wantedMeta.getDamage()) return false;
            // only when they differ: setting the damage of an unworn item would make it look different from the request
            if (offeredMeta.getDamage() != wantedMeta.getDamage()) {
                offeredMeta.setDamage(wantedMeta.getDamage());
                b.setItemMeta((ItemMeta) offeredMeta);
            }
        }
        return a.isSimilar(b);
    }

    /** A copy of one item, safe to store as the request. Null if it cannot be a request. */
    static ItemStack template(ItemStack item) {
        if (item == null || item.getType() == Material.AIR || !item.getType().isItem()) return null;
        ItemStack copy = item.clone();
        copy.setAmount(1);
        return copy.serializeAsBytes().length > MAX_BYTES ? null : copy;
    }

    /** Diamond Pickaxe from DIAMOND_PICKAXE, or the custom name when it has one. */
    static String name(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasDisplayName()) {
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                    .serialize(item.getItemMeta().displayName());
        }
        return pretty(item.getType());
    }

    static String pretty(Material material) {
        StringBuilder out = new StringBuilder();
        for (String word : material.name().toLowerCase(java.util.Locale.ROOT).split("_")) {
            if (out.length() > 0) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word, 1, word.length());
        }
        return out.toString();
    }
}
