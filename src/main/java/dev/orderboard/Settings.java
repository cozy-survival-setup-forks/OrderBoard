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

    Settings(FileConfiguration config, java.util.logging.Logger log) {
        currency = config.getString("currency", "$");
        taxPercent = Math.max(0, Math.min(100, config.getDouble("tax-percent", 0)));
        minPrice = Math.max(0.01, config.getDouble("min-price", 0.01));
        maxPrice = Math.max(minPrice, config.getDouble("max-price", 1_000_000));
        maxAmount = Math.max(1, config.getInt("max-amount", 100_000));
        defaultMaxOrders = Math.max(1, config.getInt("default-max-orders", 5));
        expireMillis = Math.max(0, config.getLong("expire-days", 14)) * 86_400_000L;
        for (String name : config.getStringList("blacklist")) {
            Material material = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
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
