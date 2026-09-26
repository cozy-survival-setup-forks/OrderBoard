package dev.wanted;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;

/** Vault, and money rounded to cents. */
final class Money {

    private final Economy economy;

    Money(Economy economy) {
        this.economy = economy;
    }

    static double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    boolean has(OfflinePlayer player, double amount) {
        return economy.has(player, amount);
    }

    boolean withdraw(OfflinePlayer player, double amount) {
        return economy.withdrawPlayer(player, amount).transactionSuccess();
    }

    boolean deposit(OfflinePlayer player, double amount) {
        return economy.depositPlayer(player, amount).transactionSuccess();
    }
}
