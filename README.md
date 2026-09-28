# OrderBoard

Player to player buy requests for Paper 1.21.11. A player posts what they want to buy and what they pay for each one. The money is held. Anyone who has the item clicks the request, hands it over and is paid on the spot. The buyer collects what was delivered whenever they like, and gets the money back for whatever was never delivered.

Needs Vault and an economy plugin.

## Using it

| | |
| --- | --- |
| `/orderboard` (`/orders`, `/order`) | Opens the market: every open request, newest first. Buttons sort them, search them, open your own requests or post a new one. |
| `/orders <words>` | Opens the market already searched, for example `/orders netherite`. |
| `/orders new` | Opens the form. Hold the item, click the paper, then click the amount and the price and type them in chat (`64`, `2k`, `12.5`). |
| `/orders new <amount> <price>` | Posts a request for the item in your hand. |
| `/orders new <item> <amount> <price>` | Posts a request for a plain item you do not have, for example `/orders new diamond 64 5`. |
| `/orders mine` | Your requests. Left click to collect what was delivered, right click to cancel and get the rest of the money back. |
| `/orders collect` | Collects everything delivered to you. |
| `/orders remove <id>`, `/orders list [player]`, `/orders reload` | Admin: remove any request, list every open request (or one player's), reload the config. All `orderboard.admin`. |

To deliver, click a request in the market. Everything you carry that matches is taken, up to what is still wanted, and you are paid at once. You cannot deliver to your own request.

## What counts as the wanted item

Exactly the same item: same name, lore, enchantments, potion, book, everything. The one difference is wear. A tool that is no more worn than the one asked for is accepted, a more worn one is not. The buyer receives that same item, not a plain one of the same kind.

## Money

- Posting takes `amount x price` from the buyer, plus `tax-percent` if set. The tax is not paid back.
- Each delivery pays `count x price` from that held money, so the plugin never makes or loses money.
- Cancelling, removing or running out of time pays back `(amount - delivered) x price`.
- Money owed is written down before it is paid and paid straight after. If the economy refuses, it stays written down and is tried again every minute and when the player joins, so an offline player still gets paid.

## Safe by design

- Nothing is ever put into or taken out of a window. Every click is a button, so there is nothing to duplicate with shift clicks, drags, number keys or closing the window.
- Everything runs on the main thread and every change is one database transaction that is committed before items or money are handed out. A crash can lose an item and, in the few milliseconds between paying and writing that it was paid, pay one payout twice. It cannot duplicate items.
- Items are taken from the player and recorded together. If recording fails the items are put back.
- The item of a request is stored as it was and compared with the exact same rule when delivering, so a worse item can never be handed in as a better one.
- Price and amount are checked, rounded to cents, and limited. A request whose item is larger than 8 KB is refused.
- Nothing is dropped on the ground. If your inventory is full, the rest waits in the request.

## Config

`config.yml` has the currency symbol, tax, limits, how many requests a player can have open (`orderboard.max.<number>` gives more), how long a request lasts and a list of items nobody can ask for. `messages.yml` has every text and the windows, in MiniMessage or `&` codes. Requests are kept in `orderboard.db`.

## Permissions

| | |
| --- | --- |
| `orderboard.use` | Use the market (everyone) |
| `orderboard.max.<number>` | Open requests at once, for example `orderboard.max.10` |
| `orderboard.admin` | Remove any request, reload (op) |

MIT license.
