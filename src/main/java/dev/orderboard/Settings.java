package dev.orderboard;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/** config.yml, read once per reload. */
final class Settings {

    final String currency;
    final double taxPercent;
    final double minPrice;
    final double maxPrice;
    final int maxAmount;
    final int defaultMaxOrders;
    final long expireMillis;
    final Set<Material> blacklist = EnumSet.noneOf(Material.class);

    /** Above this, amount*price*100 approaches Long.MAX_VALUE and Money.round starts saturating instead
     * of rounding, so escrow could cap out lower than what deliveries actually pay out over time. */
    private static final double MAX_TOTAL = 1_000_000_000_000D;

    Settings(FileConfiguration config, java.util.logging.Logger log) {
        currency = config.getString("currency", "$");
        taxPercent = Math.max(0, Math.min(100, config.getDouble("tax-percent", 0)));
        minPrice = Math.max(0.01, config.getDouble("min-price", 0.01));
        maxAmount = Math.max(1, config.getInt("max-amount", 100_000));
        maxPrice = Math.max(minPrice, Math.min(config.getDouble("max-price", 1_000_000), MAX_TOTAL / maxAmount));
        defaultMaxOrders = Math.max(1, config.getInt("default-max-orders", 5));
        expireMillis = Math.max(0, config.getLong("expire-days", 14)) * 86_400_000L;
        for (String name : config.getStringList("blacklist")) {
            // Material.matchMaterial upper-cases internally and looks for a lowercase "minecraft:" prefix
            // before doing so - upper-casing first here breaks "minecraft:spawner" style names, because
            // "MINECRAFT:SPAWNER" no longer starts with the lowercase prefix it's checking for.
            Material material = Material.matchMaterial(name);
            if (material == null) log.warning("blacklist: unknown item " + name);
            else blacklist.add(material);
        }
    }

    /** The most requests this player can have open, from orderboard.max.N permissions. */
    int maxOrders(Player player) {
        int best = defaultMaxOrders;
        for (var info : player.getEffectivePermissions()) {
            String node = info.getPermission();
            if (!info.getValue() || !node.startsWith("orderboard.max.")) continue;
            try {
                best = Math.max(best, Integer.parseInt(node.substring("orderboard.max.".length())));
            } catch (NumberFormatException ignored) {
                // orderboard.max.* is only a placeholder
            }
        }
        return best;
    }

    String money(double amount) {
        return currency + String.format(Locale.US, "%,.2f", amount);
    }
}
