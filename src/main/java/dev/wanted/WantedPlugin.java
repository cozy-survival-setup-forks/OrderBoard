package dev.wanted;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Players post what they want to buy, other players deliver it and get paid. See the README. */
public final class WantedPlugin extends JavaPlugin implements Listener {

    private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)([kKmM]?)");
    private static final long PROMPT_TICKS = 20L * 30;

    private Settings settings;
    private Messages messages;
    private Orders orders;
    private final Map<UUID, Consumer<String>> prompts = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        RegisteredServiceProvider<Economy> provider = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (provider == null) {
            getLogger().severe("No economy plugin is registered with Vault. Wanted needs one.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        settings = new Settings(getConfig(), getLogger());
        messages = new Messages(this);
        messages.load();

        orders = new Orders(new java.io.File(getDataFolder(), "wanted.db"), getLogger(), new Money(provider.getProvider()));
        try {
            getDataFolder().mkdirs();
            orders.open();
        } catch (SQLException e) {
            getLogger().severe("Could not open the database: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        Bukkit.getPluginManager().registerEvents(this, this);
        var command = getCommand("wanted");
        if (command != null) {
            var handler = new WantedCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::refreshMenus, 40L, 40L);
        Bukkit.getScheduler().runTaskTimer(this, this::everyMinute, 20L, 1200L);
    }

    @Override
    public void onDisable() {
        if (orders != null) orders.close();
    }

    public void reload() {
        reloadConfig();
        settings = new Settings(getConfig(), getLogger());
        messages.load();
    }

    Settings settings() {
        return settings;
    }

    Messages messages() {
        return messages;
    }

    Orders orders() {
        return orders;
    }

    // ---------------------------------------------------------------- what players do

    /** @return true if the request was posted */
    boolean create(Player player, ItemStack item, int amount, double price) {
        ItemStack template = Matching.template(item);
        if (template == null) {
            messages.send(player, "invalid-item");
        } else if (settings.blacklist.contains(template.getType())) {
            messages.send(player, "blacklisted");
        } else if (amount < 1 || amount > settings.maxAmount) {
            messages.send(player, "invalid-amount", "max", Menu.count(settings.maxAmount));
        } else if (!Double.isFinite(price) || price < settings.minPrice || price > settings.maxPrice) {
            messages.send(player, "invalid-price", "min", settings.money(settings.minPrice), "max", settings.money(settings.maxPrice));
        } else {
            Orders.Outcome result = orders.create(player, template, amount, Money.round(price), settings);
            switch (result.status()) {
                case OK -> {
                    messages.send(player, "created", "amount", Menu.count(amount), "item", Matching.name(template),
                            "price", settings.money(Money.round(price)), "total", settings.money(result.money()));
                    player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                    return true;
                }
                case LIMIT -> messages.send(player, "limit", "max", String.valueOf(settings.maxOrders(player)));
                case MONEY -> messages.send(player, "not-enough-money");
                default -> messages.send(player, "error");
            }
        }
        return false;
    }

    void deliver(Player player, Order order) {
        Orders.Outcome result = orders.fill(player, order);
        switch (result.status()) {
            case OK -> {
                String item = Matching.name(order.item);
                messages.send(player, "delivered", "count", Menu.count(result.count()), "item", item, "money", settings.money(result.money()));
                player.playSound(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
                Player owner = Bukkit.getPlayer(order.owner);
                if (owner != null) {
                    messages.send(owner, order.open ? "delivered-owner" : "delivered-owner-done", "player", player.getName(),
                            "count", Menu.count(result.count()), "item", item, "left", Menu.count(order.remaining()));
                }
            }
            case CLOSED -> messages.send(player, "closed");
            case OWN -> messages.send(player, "own-order");
            case NOTHING -> messages.send(player, "none-to-deliver");
            default -> messages.send(player, "error");
        }
    }

    /** @return false if there is no room left, so a loop over many requests can stop */
    boolean collect(Player player, Order order) {
        Orders.Outcome result = orders.collect(player, order);
        switch (result.status()) {
            case OK -> {
                messages.send(player, "collected", "count", Menu.count(result.count()), "item", Matching.name(order.item));
                return true;
            }
            case FULL -> {
                messages.send(player, "inventory-full");
                return false;
            }
            case NOTHING -> {
                return true;
            }
            default -> {
                messages.send(player, "error");
                return true;
            }
        }
    }

    /** The owner cancels their own request. */
    void cancel(Player player, Order order) {
        if (!order.owner.equals(player.getUniqueId())) return;
        Orders.Outcome result = orders.cancel(order, "cancelled");
        if (result.status() == Orders.Status.OK) {
            messages.send(player, "cancelled", "refund", settings.money(result.money()));
        } else if (result.status() == Orders.Status.ERROR) {
            messages.send(player, "error");
        }
    }

    /** An admin removes anyone's request. The owner is paid back what was not delivered. */
    void remove(CommandSender sender, Order order) {
        Orders.Outcome result = orders.cancel(order, "removed");
        if (result.status() == Orders.Status.OK) {
            messages.send(sender, "removed", "id", String.valueOf(order.id), "owner", order.ownerName);
            Player owner = Bukkit.getPlayer(order.owner);
            if (owner != null) messages.send(owner, "removed-owner", "item", Matching.name(order.item), "refund", settings.money(result.money()));
        } else {
            messages.send(sender, "closed");
        }
    }

    /** Asks for a line of chat. The next thing the player says goes to the callback, or null if they cancel or wait. */
    void ask(Player player, String messageKey, Consumer<String> then) {
        player.closeInventory();
        messages.send(player, messageKey);
        prompts.put(player.getUniqueId(), then);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (prompts.get(player.getUniqueId()) == then) {
                prompts.remove(player.getUniqueId());
                then.accept(null);
            }
        }, PROMPT_TICKS);
    }

    static Integer parseAmount(String text) {
        Double value = number(text);
        return value == null || value < 1 || value > Integer.MAX_VALUE || value % 1 != 0 ? null : value.intValue();
    }

    static Double parsePrice(String text) {
        Double value = number(text);
        return value == null || value <= 0 ? null : Money.round(value);
    }

    /** 250, 1.5k, 2m. */
    private static Double number(String text) {
        Matcher match = NUMBER.matcher(text.trim().replace(",", ""));
        if (!match.matches()) return null;
        double value = Double.parseDouble(match.group(1));
        switch (match.group(2).toLowerCase(Locale.ROOT)) {
            case "k" -> value *= 1_000;
            case "m" -> value *= 1_000_000;
            default -> { }
        }
        return Double.isFinite(value) ? value : null;
    }

    // ---------------------------------------------------------------- timers

    private void refreshMenus() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            var holder = player.getOpenInventory().getTopInventory().getHolder(false);
            if (holder instanceof MarketMenu || holder instanceof MineMenu) ((Menu) holder).render();
        }
    }

    private void everyMinute() {
        for (Orders.Expired expired : orders.expireDue()) {
            Player owner = Bukkit.getPlayer(expired.order().owner);
            if (owner != null) messages.send(owner, "expired", "item", Matching.name(expired.order().item), "refund", settings.money(expired.refund()));
        }
        orders.retryPayouts();
    }

    // ---------------------------------------------------------------- events

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof Menu menu)) return;

        event.setCancelled(true);
        if (event.getClickedInventory() == event.getView().getTopInventory()) {
            menu.click(event.getSlot(), event.getClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof Menu) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Consumer<String> then = prompts.remove(event.getPlayer().getUniqueId());
        if (then == null) return;

        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(this, () -> then.accept(text.equalsIgnoreCase("cancel") ? null : text));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            orders.retryPayouts();
            int waiting = orders.waiting(player.getUniqueId());
            if (waiting > 0) messages.send(player, "waiting", "count", Menu.count(waiting));
        }, 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        prompts.remove(event.getPlayer().getUniqueId());
    }
}
