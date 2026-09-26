package dev.orderboard;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Every request and the money owed to players, in one SQLite file. Everything runs on the main thread, so two players
 * can never fill the same request at once, and every change that involves items or money is one transaction that is
 * committed before anything is handed out. A crash can lose an item, and in the milliseconds between paying a payout and deleting its row it can pay that payout twice. It cannot duplicate items.
 */
final class Orders {

    enum Status { OK, CLOSED, OWN, NOTHING, FULL, MONEY, LIMIT, ERROR }

    /** {@code count} items and {@code money} moved, when the status is OK. */
    record Outcome(Status status, int count, double money) {
        static Outcome of(Status status) {
            return new Outcome(status, 0, 0);
        }
    }

    /** A request that ran out of time, and the money that went back to its owner. */
    record Expired(Order order, double refund) {
    }

    private interface Work {
        void run() throws SQLException;
    }

    private final Logger log;
    private final Money money;
    private final String url;
    private final Map<Long, Order> orders = new LinkedHashMap<>();
    private Connection connection;
    private boolean warnedPayouts;

    Orders(File file, Logger log, Money money) {
        this.url = "jdbc:sqlite:" + file;
        this.log = log;
        this.money = money;
    }

    // ---------------------------------------------------------------- database

    void open() throws SQLException {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite driver not found", e);
        }
        connection = DriverManager.getConnection(url);
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous = FULL");
            s.execute("CREATE TABLE IF NOT EXISTS orders (id INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT NOT NULL, "
                    + "owner_name TEXT NOT NULL, item BLOB NOT NULL, amount INTEGER NOT NULL, filled INTEGER NOT NULL, "
                    + "collected INTEGER NOT NULL, price REAL NOT NULL, open INTEGER NOT NULL, created INTEGER NOT NULL, "
                    + "expires INTEGER NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS payouts (id INTEGER PRIMARY KEY AUTOINCREMENT, player TEXT NOT NULL, "
                    + "amount REAL NOT NULL, reason TEXT NOT NULL)");
        }
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, owner, owner_name, item, amount, filled, collected, price, open, created, expires FROM orders ORDER BY id")) {
            while (rs.next()) {
                try {
                    ItemStack item = ItemStack.deserializeBytes(rs.getBytes(4));
                    orders.put(rs.getLong(1), new Order(rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getString(3), item,
                            rs.getInt(5), rs.getInt(6), rs.getInt(7), rs.getDouble(8), rs.getInt(9) == 1, rs.getLong(10), rs.getLong(11)));
                } catch (RuntimeException e) {
                    log.warning("Request #" + rs.getLong(1) + " could not be read and is left alone: " + e.getMessage());
                }
            }
        }
    }

    void close() {
        try {
            if (connection != null) connection.close();
        } catch (SQLException ignored) {
            // shutting down
        }
    }

    private boolean tx(Work work) {
        try {
            connection.setAutoCommit(false);
            work.run();
            connection.commit();
            return true;
        } catch (SQLException e) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // nothing more to do
            }
            log.severe("Database error: " + e.getMessage());
            return false;
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // nothing more to do
            }
        }
    }

    // ---------------------------------------------------------------- reading

    Collection<Order> all() {
        return orders.values();
    }

    Order get(long id) {
        return orders.get(id);
    }

    int openCount(UUID owner) {
        int count = 0;
        for (Order order : orders.values()) if (order.open && order.owner.equals(owner)) count++;
        return count;
    }

    /** Items waiting for this player to collect. */
    int waiting(UUID owner) {
        int count = 0;
        for (Order order : orders.values()) if (order.owner.equals(owner)) count += order.stored();
        return count;
    }

    /** How many of the wanted item this player carries. */
    static int have(PlayerInventory inventory, ItemStack wanted) {
        int count = 0;
        for (ItemStack stack : inventory.getStorageContents()) if (Matching.matches(wanted, stack)) count += stack.getAmount();
        return count;
    }

    /** How many of an item fit in the inventory. */
    static int room(PlayerInventory inventory, ItemStack item) {
        int room = 0;
        for (ItemStack stack : inventory.getStorageContents()) {
            if (stack == null || stack.getType().isAir()) room += item.getMaxStackSize();
            else if (stack.isSimilar(item)) room += Math.max(0, item.getMaxStackSize() - stack.getAmount());
        }
        return room;
    }

    // ---------------------------------------------------------------- payments

    /** Records money owed, inside the transaction that earns it. @return the id, 0 if there is nothing to pay */
    private long queue(UUID player, double amount, String reason) throws SQLException {
        if (amount <= 0) return 0;
        try (PreparedStatement s = connection.prepareStatement("INSERT INTO payouts (player, amount, reason) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS)) {
            s.setString(1, player.toString());
            s.setDouble(2, amount);
            s.setString(3, reason);
            s.executeUpdate();
            try (ResultSet keys = s.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 0;
            }
        }
    }

    /** Pays a queued payout and forgets it. If the economy says no it stays and is tried again later. */
    private boolean settle(long id, UUID player, double amount) {
        if (id == 0) return true;
        OfflinePlayer target = Bukkit.getOfflinePlayer(player);
        if (!money.deposit(target, amount)) return false;
        try (PreparedStatement s = connection.prepareStatement("DELETE FROM payouts WHERE id = ?")) {
            s.setLong(1, id);
            s.executeUpdate();
        } catch (SQLException e) {
            log.severe("Paid a payout but could not remove it, check the payouts table for id " + id + ": " + e.getMessage());
        }
        return true;
    }

    /** Tries every payout that is still owed. Called once a minute and when a player joins. */
    void retryPayouts() {
        List<Object[]> owed = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery("SELECT id, player, amount FROM payouts")) {
            while (rs.next()) owed.add(new Object[]{rs.getLong(1), UUID.fromString(rs.getString(2)), rs.getDouble(3)});
        } catch (SQLException e) {
            log.severe("Could not read payouts: " + e.getMessage());
            return;
        }
        boolean failed = false;
        for (Object[] row : owed) failed |= !settle((Long) row[0], (UUID) row[1], (Double) row[2]);
        if (failed && !warnedPayouts) {
            log.warning("The economy refused some payouts. They are kept and tried again every minute.");
        }
        warnedPayouts = failed;
    }

    // ---------------------------------------------------------------- making a request

    Outcome create(Player owner, ItemStack template, int amount, double price, Settings settings) {
        if (openCount(owner.getUniqueId()) >= settings.maxOrders(owner)) return Outcome.of(Status.LIMIT);

        double subtotal = Money.round(amount * price);
        double total = Money.round(subtotal + subtotal * settings.taxPercent / 100D);
        if (!money.has(owner, total) || !money.withdraw(owner, total)) return Outcome.of(Status.MONEY);

        long now = System.currentTimeMillis();
        long expires = settings.expireMillis == 0 ? 0 : now + settings.expireMillis;
        long[] id = new long[1];
        boolean saved = tx(() -> {
            try (PreparedStatement s = connection.prepareStatement("INSERT INTO orders (owner, owner_name, item, amount, filled, collected, price, open, created, expires) VALUES (?, ?, ?, ?, 0, 0, ?, 1, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                s.setString(1, owner.getUniqueId().toString());
                s.setString(2, owner.getName());
                s.setBytes(3, template.serializeAsBytes());
                s.setInt(4, amount);
                s.setDouble(5, price);
                s.setLong(6, now);
                s.setLong(7, expires);
                s.executeUpdate();
                try (ResultSet keys = s.getGeneratedKeys()) {
                    if (!keys.next()) throw new SQLException("no id");
                    id[0] = keys.getLong(1);
                }
            }
        });
        if (!saved) {
            // give the money back, and if even that fails keep it as a payout so it is not lost
            if (!money.deposit(owner, total)) {
                tx(() -> queue(owner.getUniqueId(), total, "failed request"));
            }
            return Outcome.of(Status.ERROR);
        }
        orders.put(id[0], new Order(id[0], owner.getUniqueId(), owner.getName(), template, amount, 0, 0, price, true, now, expires));
        return new Outcome(Status.OK, amount, total);
    }

    // ---------------------------------------------------------------- delivering

    /** Takes every matching item the player carries, up to what is still wanted, and pays for it. */
    Outcome fill(Player player, Order order) {
        if (!order.open || order.remaining() <= 0) return Outcome.of(Status.CLOSED);
        if (order.owner.equals(player.getUniqueId())) return Outcome.of(Status.OWN);

        PlayerInventory inventory = player.getInventory();
        ItemStack[] current = inventory.getStorageContents();
        ItemStack[] backup = new ItemStack[current.length];
        ItemStack[] work = new ItemStack[current.length];
        int have = 0;
        for (int i = 0; i < current.length; i++) {
            if (current[i] == null) continue;
            backup[i] = current[i].clone();
            work[i] = current[i].clone();
            if (Matching.matches(order.item, work[i])) have += work[i].getAmount();
        }
        if (have == 0) return Outcome.of(Status.NOTHING);

        int count = Math.min(have, order.remaining());
        for (int i = 0, left = count; i < work.length && left > 0; i++) {
            if (!Matching.matches(order.item, work[i])) continue;
            int take = Math.min(left, work[i].getAmount());
            left -= take;
            if (take == work[i].getAmount()) work[i] = null;
            else work[i].setAmount(work[i].getAmount() - take);
        }

        double pay = Money.round(count * order.price);
        long[] payout = new long[1];
        inventory.setStorageContents(work);
        boolean saved = tx(() -> {
            try (PreparedStatement s = connection.prepareStatement("UPDATE orders SET filled = filled + ?, "
                    + "open = CASE WHEN filled + ? >= amount THEN 0 ELSE open END WHERE id = ? AND open = 1 AND filled + ? <= amount")) {
                s.setInt(1, count);
                s.setInt(2, count);
                s.setLong(3, order.id);
                s.setInt(4, count);
                if (s.executeUpdate() != 1) throw new SQLException("request " + order.id + " changed");
            }
            payout[0] = queue(player.getUniqueId(), pay, "delivery to #" + order.id);
        });
        if (!saved) {
            inventory.setStorageContents(backup);
            return Outcome.of(Status.ERROR);
        }

        order.filled += count;
        if (order.filled >= order.amount) order.open = false;
        settle(payout[0], player.getUniqueId(), pay);
        return new Outcome(Status.OK, count, pay);
    }

    // ---------------------------------------------------------------- collecting

    /** Gives the owner what was delivered, as much as fits. */
    Outcome collect(Player player, Order order) {
        int stored = order.stored();
        if (stored <= 0) return Outcome.of(Status.NOTHING);

        PlayerInventory inventory = player.getInventory();
        int count = Math.min(stored, room(inventory, order.item));
        if (count <= 0) return Outcome.of(Status.FULL);

        boolean saved = tx(() -> {
            try (PreparedStatement s = connection.prepareStatement("UPDATE orders SET collected = collected + ? WHERE id = ? AND filled - collected >= ?")) {
                s.setInt(1, count);
                s.setLong(2, order.id);
                s.setInt(3, count);
                if (s.executeUpdate() != 1) throw new SQLException("request " + order.id + " changed");
            }
        });
        if (!saved) return Outcome.of(Status.ERROR);
        order.collected += count;

        int lost = 0;
        for (int left = count; left > 0; ) {
            ItemStack stack = order.item.clone();
            stack.setAmount(Math.min(left, stack.getMaxStackSize()));
            left -= stack.getAmount();
            var overflow = inventory.addItem(stack);
            if (!overflow.isEmpty()) lost += overflow.get(0).getAmount();
        }
        if (lost > 0) {
            int back = lost;
            tx(() -> {
                try (PreparedStatement s = connection.prepareStatement("UPDATE orders SET collected = collected - ? WHERE id = ?")) {
                    s.setInt(1, back);
                    s.setLong(2, order.id);
                    s.executeUpdate();
                }
            });
            order.collected -= lost;
        }
        forgetIfDone(order);
        return new Outcome(Status.OK, count - lost, 0);
    }

    // ---------------------------------------------------------------- closing

    /** Stops the request and pays back the money for what was not delivered. Delivered items stay for the owner. */
    Outcome cancel(Order order, String reason) {
        if (!order.open) return Outcome.of(Status.CLOSED);

        double refund = order.refund();
        long[] payout = new long[1];
        boolean saved = tx(() -> {
            try (PreparedStatement s = connection.prepareStatement("UPDATE orders SET open = 0 WHERE id = ? AND open = 1")) {
                s.setLong(1, order.id);
                if (s.executeUpdate() != 1) throw new SQLException("request " + order.id + " changed");
            }
            payout[0] = queue(order.owner, refund, reason + " #" + order.id);
        });
        if (!saved) return Outcome.of(Status.ERROR);

        order.open = false;
        settle(payout[0], order.owner, refund);
        forgetIfDone(order);
        return new Outcome(Status.OK, 0, refund);
    }

    /** Closes every request that ran out of time. @return them, for telling their owners */
    List<Expired> expireDue() {
        long now = System.currentTimeMillis();
        List<Expired> expired = new ArrayList<>();
        for (Order order : new ArrayList<>(orders.values())) {
            if (!order.open || order.expires == 0 || order.expires > now) continue;
            Outcome result = cancel(order, "expired");
            if (result.status() == Status.OK) expired.add(new Expired(order, result.money()));
        }
        return expired;
    }

    /** A closed request with nothing left to collect is deleted. */
    private void forgetIfDone(Order order) {
        if (order.open || order.stored() > 0) return;
        try (PreparedStatement s = connection.prepareStatement("DELETE FROM orders WHERE id = ?")) {
            s.setLong(1, order.id);
            s.executeUpdate();
            orders.remove(order.id);
        } catch (SQLException e) {
            log.warning("Could not delete request #" + order.id + ": " + e.getMessage());
        }
    }
}
