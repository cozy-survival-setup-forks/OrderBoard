package dev.orderboard;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.OfflinePlayer;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Vault, and money rounded to cents. */
final class Money {

    private final Economy economy;
    private final Logger log;

    Money(Economy economy, Logger log) {
        this.economy = economy;
        this.log = log;
    }

    static double round(double value) {
        return Math.round(value * 100D) / 100D;
    }

    boolean has(OfflinePlayer player, double amount) {
        try {
            return economy.has(player, amount);
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "The economy plugin failed checking " + player.getUniqueId() + "'s balance", e);
            return false;
        }
    }

    boolean withdraw(OfflinePlayer player, double amount) {
        try {
            return economy.withdrawPlayer(player, amount).transactionSuccess();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "The economy plugin failed withdrawing from " + player.getUniqueId(), e);
            return false;
        }
    }

    /**
     * Without this, one account the economy plugin can't deposit to (a bad row in an SQL-backed economy,
     * a balance cap, anything) throws out of retryPayouts and stops every payout after it in the same
     * pass from even being tried, defeating the entire point of the retry queue.
     */
    boolean deposit(OfflinePlayer player, double amount) {
        try {
            return economy.depositPlayer(player, amount).transactionSuccess();
        } catch (RuntimeException e) {
            log.log(Level.WARNING, "The economy plugin failed depositing to " + player.getUniqueId(), e);
            return false;
        }
    }
}
