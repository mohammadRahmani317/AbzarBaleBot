# Tools Market Bot

Java 21 / Spring Boot 4 application with separate Bale customer and seller bots, Maven, Jackson 3 and JSON persistence. The package structure, pricing/history/chart implementation, user approval policy and callback wire formats are retained. Products are now editable and persisted alongside order and inventory state.

## Implemented behavior

- Persistent customer profiles keyed by private Bale chat ID. Checkout requests only missing name/phone details; customers can edit either from `👤 اطلاعات من`. Orders retain their own customer snapshots.
- Multi-product carts with quantity replacement, removal, current-price previews and automatic invoice totals in Toman. Carts do not reserve stock.
- Inventory management from `📦 مدیریت موجودی محصولات`. Missing stock means zero available. Manual totals must be nonnegative and at least the reserved quantity. Changes record actor, old/new totals and timestamp.
- Checkout revalidates every product, price and quantity, then saves immutable price snapshots and reserves stock. One pending invoice per customer prevents repeated checkout from reserving twice.
- Successful invoice API delivery starts the two-hour deadline. Failed delivery cancels the provisional invoice and releases stock. If the process stops during delivery, an unsent reservation is cancelled after five minutes by recovery checks.
- The original six lifecycle statuses: `WAITING_CUSTOMER`, `CANCELLED`, `EXPIRED`, `CONFIRMED`, `PREPARING`, `DELIVERED`. The additional `REJECTED` status is readable/displayable for historical records; no rejection transition is introduced. Unknown legacy statuses are stored separately, not mapped to confirmed orders.
- Customer-only confirmation/cancellation, expiration checks on both decisions and every 30 seconds, atomic deduction/release, idempotent callbacks, and chronological status history.
- First preparation claim records the responsible user. That seller or any approved admin/super-admin can deliver. Preparation and delivery never deduct stock again.
- Seller lists for active, delivered, cancelled, expired and all orders. Details include products, snapshots, customer information, status, actors and Persian dates/times.
- Persisted notification retries every 15 seconds after confirmation only. Approved sellers and admins receive notifications; super-admins are excluded, including users promoted before delivery. A failed notification does not undo confirmation.
- Persisted reports for all supported statuses, legacy counts, disjoint order-value buckets, stock/reservations/availability and confirmed/preparing versus delivered product quantities. Values are not labelled received revenue.
- All approved `SELLER`, `ADMIN`, `SUPER_ADMIN` users can price products, manage stock, manage orders, and view reports. Only `SUPER_ADMIN` can see price history, even if a different role has a legacy history permission flag. Customer ordering is independent of staff roles.


## Product management, customer history and typed bot actions

- Approved users authorized by `UserAccessService.hasPermission(..., PRICE_MANAGER)` can open `🛠 مدیریت محصولات`. The existing policy continues to authorize approved seller/admin/super-admin roles. Access is checked again in catalog and inventory service mutations, including when an input form outlives the user's access.
- Add a product by entering name, brand, description, category code and initial stock. Edit existing metadata with the same guided form; `-` retains an existing field. Price remains managed through the existing price menu. New products may have no image; they are shown as text. Existing images are preserved by the edit form.
- Product fields now include `brand`, `stockQuantity` and `deleted`. Old product JSON without these fields remains readable with empty brand, zero stock and `deleted=false`. Existing inventory takes precedence over seed stock during migration.
- Deleting a product requires a confirmation button and marks it deleted. New browsing/cart additions/checkout and price/stock edits exclude deleted products. Existing issued invoices can still complete against their snapshots and reservations. Product IDs are not reused; price history and order records remain. Super-admin price history and inventory/product reports still include archived products.
- In `📦 مدیریت موجودی محصولات`, enter an unsigned number for an absolute total, `+5` to add five, or `-2` to remove two. Persian digits are accepted. Updates are atomic, audited, reject overflow and cannot reduce total below reserved stock.
- Customer menu `📦 سفارش‌های قبلی من` lists all that customer's orders, newest first, in batches of 15 buttons. Details show immutable product/price snapshots, quantities, total, dates and status. Ownership is checked in `OrderService.listForCustomer` and `findForCustomer`; missing and foreign IDs receive the same response. Historical records with unknown prices remain marked unknown. Known legacy status labels, including rejected, are translated for display without changing persisted legacy status or enabling transitions.
- `bot/BotCallback.java` centralizes callback actions, validated parsing/encoding and typed order-list groups. It retains old wire values, rejects missing/extra/invalid payloads and enforces Bale's 64-byte callback limit. `OrderMessages.button` accepts a typed callback. `bot/BotButton.java` centralizes fixed labels/commands; handlers compare enums. Customer profile input and product form steps also use enums. Names, descriptions and other free text remain strings.

Additional tests cover product persistence/restart, old JSON compatibility, soft deletion, stock bounds and write failures, private customer history, legacy display, old callback compatibility, malformed callbacks and access-management routing.

In environments where Mockito cannot self-attach, load the project's resolved Mockito jar as a test JVM agent. For the current dependencies, verification can use:

```bash
mvn -s /tmp/tools-market-maven-settings.xml -o -B \
  -DargLine=-javaagent:$HOME/.m2/repository/org/mockito/mockito-core/5.20.0/mockito-core-5.20.0.jar \
  clean verify
```

The temporary Maven settings file is environment-specific; create it using the example below if needed. The agent jar version must match the resolved Mockito dependency. This command runs isolated tests and creates the executable JAR without starting live bots.

## Changed files for the product/history/callback tasks

Paths below are relative to `src/main/java/ir/toolsmarket/tools_market_bot/`, except the final two rows.

| Area | Added or updated files |
| --- | --- |
| Product model and persistence | `model/Product.java`, `model/OrderStore.java`, `service/ProductCatalogService.java`, `service/OrderStorageService.java` |
| Inventory and access | `service/InventoryService.java`, `service/UserAccessService.java`, `seller/menu/InventoryMenuService.java`, new `seller/menu/ProductManagementMenuService.java` |
| Customer order history | `service/OrderService.java`, `model/OrderStatus.java`, `util/OrderMessages.java`, `customer/service/CustomerUpdateService.java`, `customer/menu/BotMenuService.java`, `customer/menu/BotMenuOption.java` |
| Central bot contracts | new `bot/BotCallback.java`, new `bot/BotButton.java` |
| Seller menus and routing | `seller/service/SellerUpdateService.java`, `seller/menu/SellerMenuService.java`, `seller/menu/SellerAdminMenuService.java`, `seller/menu/PriceManagementMenuService.java`, `seller/service/OrderNotificationService.java`, `service/OrderReportService.java` |
| Tests (under `src/test/java/ir/toolsmarket/tools_market_bot/`) | new `bot/BotCallbackTest.java`, new `service/ProductManagementTest.java`, new `service/CustomerOrderHistoryTest.java`; updated `service/TestFixture.java`, `customer/service/CustomerUpdateServiceTest.java`, `seller/service/SellerUpdateServiceTest.java` |
| Repository documentation | `README.md`, `SKILL.md` |

Validation for these changes: **71 tests passed; zero failures, errors or skips**. Full `clean verify` completed successfully with the temporary Central settings and Mockito agent command above. The executable artifact is `target/tools-market-bot-0.0.1-SNAPSHOT.jar`. Existing tracked JSON data, seed resources, application connection configuration and dependency versions were not changed. Live Bale acceptance was not run.

## Configure and run

Set the following environment variables through your deployment secret configuration:

```text
BALE_CUSTOMER_TOKEN
BALE_SELLER_TOKEN
BALE_SUPER_ADMIN_CHAT_ID
```

The current `application.yml` contains nonempty fallback values for bot credentials and the super-admin ID. Supply your own environment configuration. Explicitly empty tokens disable the corresponding polling client; do not assume unset environment variables disable polling. The existing initializer creates/updates the configured super-admin at startup.

```bash
mvn clean verify
java -jar target/tools-market-bot-0.0.1-SNAPSHOT.jar
```

The HTTP port remains `8081`. Both bots use polling and must have webhooks disabled. The existing customer `POST /webhook/disable` endpoint is retained. Disable a seller webhook through the existing Bale bot configuration before using seller polling.

Optional configuration:

| Environment variable / Spring property | Default |
| --- | --- |
| `BALE_BASE_URL` | `https://tapi.bale.ai` |
| `USERS_FILE` | `data/users.json` |
| `PRODUCT_PRICES_FILE` | `data/product-prices.json` |
| `LEGACY_ORDERS_FILE` | `data/orders.json` |
| `ORDER_STATE_FILE` | `data/order-state.json` |
| `tools-market.orders.expiration-check-ms` | `30000` |
| `tools-market.orders.notification-check-ms` | `15000` |
| `tools-market.scheduling.enabled` | `true` |

## Persistence and compatibility

`src/main/resources/data/products.json` is the initial seed catalog. On the first catalog load without persisted products, it is copied into the `products` map of `data/order-state.json`; subsequent starts use the persisted catalog. Existing inventory and orders are preserved during this additive migration. Existing `data/users.json` and `data/product-prices.json` retain their schemas and contents, including permission flags and price history. Writes now use the shared atomic JSON writer.

`data/order-state.json` is the authoritative aggregate for products, orders, inventory, customer profiles, status events, manual stock history and notification delivery records. Product `stockQuantity` is synchronized from inventory totals on every commit; it is not a second independent stock balance. Order and stock operations execute under one service lock and commit through a single atomically renamed, flushed file. A failed pre-commit write leaves both unchanged. Restart recovery reads this persisted snapshot, never guesses or recalculates historical stock deductions. Use a persistent writable local filesystem supporting atomic rename and fsync, and run **one application instance per data directory**.

On first access with no aggregate file, the application backs up `orders.json` to `orders.json.pre-cart.bak`, copies every old order into the aggregate, and leaves the source untouched. Subsequent starts read the aggregate, so import is idempotent. Preserve both the aggregate and its source backup in operational backups; do not delete the aggregate to “reset” an application that has accepted orders.

Each legacy single-product order receives one `OrderItem`; IDs, chat IDs, customer snapshots, quantities, timestamps and prices are preserved. Missing historical prices remain missing and display as unknown. Orders without a trustworthy lifecycle are marked `legacy=true`, with `status=null` and the original status text in `legacyStatus`. They appear in all-order history and counts, but cannot enter operational queues, reserve/deduct stock, or contribute guessed financial values. No historical order or price-history entry is deleted.

The old `Order` constructors and JSON properties remain. Existing product/category/navigation, access-management and price callbacks remain routed. Old session-only `order:confirm` / `order:cancel` buttons receive an explicit stale-button message, so they cannot accidentally act on a new invoice. New invoice callbacks carry an order ID and are usable after restart. `ProductPriceService.findHistory` now requires the actor chat ID; its menu caller was updated to enforce authorization at the service boundary.

## Validation

The automated suite uses temporary directories and mocked Bale clients. It does not alter repository data or send live messages. Tests cover carts, profiles, snapshots, all lifecycle transitions, invalid transitions, inventory bounds, duplicate callbacks, concurrent checkout, confirmation/expiration races, restart recovery, write failures, legacy migration, price-history charts, all three staff roles, actual bot update routing, notification retries and super-admin exclusion.

The development environment's global Maven settings point at an unavailable private mirror. Verification used an external temporary settings file mapping repositories to Maven Central, without changing the POM or global settings:

```bash
mvn -s /tmp/tools-market-maven-settings.xml -B clean verify
```

If needed, create your own temporary Maven settings containing:

```xml
<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">
  <mirrors>
    <mirror>
      <id>central-only</id>
      <mirrorOf>*</mirrorOf>
      <url>https://repo.maven.apache.org/maven2</url>
    </mirror>
  </mirrors>
</settings>
```

The earlier baseline recorded 38 passing tests. For the product/history/callback changes, use the current validation result below. See `target/surefire-reports` for individual results. Live Bale delivery requires configured bots and remains a manual check.

## Manual Bale acceptance guide

1. Configure both bot tokens and the super-admin ID, start one application instance, and open `/start` in both bots. Have a seller request access and approve it using the existing admin menus. Repeat with an admin account.
2. In the seller bot, set prices for at least two products via `💰 مدیریت قیمت محصولات`; set stock to 10 for each via `📦 مدیریت موجودی محصولات`. Confirm the same options work for seller, admin and super-admin. Only super-admin should see price history and its chart.
3. In the customer bot, open products and select `📦 ثبت سفارش این محصول`. Enter quantity 2. Add a second product with quantity 3; inspect `🛒 سبد خرید`, change a quantity, remove an item and add it again. Verify current stock has not been reserved by cart edits.
4. Request `🧾 دریافت پیش‌فاکتور`. A new customer supplies only missing name and phone; a returning customer receives the invoice immediately. Verify line totals and final total in Toman. Check the seller inventory: total stays 10, reserved reflects each invoice quantity and available equals total minus reserved. The invoice is in all orders but absent from active orders.
5. Change a catalog price and edit `👤 اطلاعات من`. Verify the already-issued invoice and its customer details do not change.
6. Confirm the invoice. Verify inventory totals decrease once, reservations return to zero, and approved sellers/admins receive an order-view button. Super-admin receives no automatic new-order notification but can open the order manually. Click confirmation repeatedly and verify stock and notification counts stay unchanged.
7. Open active orders, inspect details/history and click `🔧 شروع آماده‌سازی`. A second seller must see the original responsible person and cannot take the claim or deliver the order. Verify an admin or super-admin can intervene and click `📦 ثبت تحویل`. The order moves from active to delivered.
8. Issue another invoice and cancel it before confirmation. Repeated cancellation must not add stock, and the record must remain in the cancelled list/history. Confirmation of the cancelled invoice must fail.
9. Issue another invoice and leave it unanswered for two hours. Check the expired list and released stock. Try both old confirmation and cancellation buttons: neither may revive it. Repeat by stopping the application before the deadline and restarting after it; stock must be released once.
10. Attempt quantity zero, a negative or fractional quantity, quantity above stock, checkout without a price, and setting inventory below reserved stock. All must fail without partial stock or order changes. Two customers trying to reserve the last units must not oversell.
11. Verify an unauthorized/pending seller account cannot change prices, stock or orders. Staff accounts must still place customer orders in the customer bot. Check reports and legacy all-order history; cancelled/expired records remain and delivered values are not added again to confirmed values.
12. On a test deployment, simulate an invoice send failure: the invoice must cancel and release reservations, with the cart retained for retry. Simulate a seller notification failure: the confirmed order and stock must stay valid, and notification delivery must retry after recovery.

## Focused cleanups

Critical authorization lives in `UserAccessService`; lifecycle and inventory mutations live in `OrderService`; catalog and existing pricing responsibilities remain separate. Reusable Persian amount/input formatting, order presentation and atomic JSON writing replace duplication. Customer update handling is split into message, callback, cart, profile and checkout methods. Menu services render messages; service methods validate actions. Unused manual-order instructions and commented-out code were removed; resource streams are closed. Failed updates no longer block a polling batch, and network errors are logged without token-bearing URLs.

## File inventory

Modified existing files (under `src/main/java/ir/toolsmarket/tools_market_bot/` unless noted):

- `ToolsMarketBotApplication.java`
- `config/SchedulingConfiguration.java`
- `customer/menu/BotMenuService.java`
- `customer/service/CustomerUpdateService.java`
- `integration/bale/BaleBotClient.java`
- `model/Order.java`
- `seller/menu/PriceManagementMenuService.java`
- `seller/menu/SellerAdminMenuService.java`
- `seller/menu/SellerMenuService.java`
- `seller/service/SellerUpdateService.java`
- `service/OrderStorageService.java`
- `service/ProductCatalogService.java`
- `service/ProductPriceService.java`
- `service/UserAccessService.java`
- `src/main/resources/application.yml`
- `src/test/java/ir/toolsmarket/tools_market_bot/ToolsMarketBotApplicationTests.java`
- `.gitignore`, `README.md`

New production files:

- `model/Customer.java`, `model/InventoryChange.java`, `model/OrderItem.java`, `model/OrderNotification.java`
- `model/OrderStatus.java`, `model/OrderStatusHistory.java`, `model/OrderStore.java`, `model/ProductInventory.java`
- `service/CustomerService.java`, `service/InventoryService.java`, `service/OrderReportService.java`
- `service/OrderService.java`, `service/ShoppingCartService.java`
- `seller/menu/InventoryMenuService.java`, `seller/service/OrderNotificationService.java`
- `util/AtomicJsonFile.java`, `util/BotFormatting.java`, `util/OrderMessages.java`

New tests under `src/test/java/ir/toolsmarket/tools_market_bot/`:

- `service/TestFixture.java`, `service/OrderServiceTest.java`
- `customer/service/CustomerUpdateServiceTest.java`
- `seller/service/SellerUpdateServiceTest.java`, `seller/service/OrderNotificationServiceTest.java`

The pre-existing local modification to `data/orders.json` was not changed by this implementation. The POM and dependency versions are unchanged.

## Operational limits

- File persistence supports a single running process per data directory; it is not shared multi-instance storage.
- Carts and input sessions are temporary and reset on restart; issued invoices, reservations, profiles, history and retry records persist.
- Bale does not participate in the local JSON transaction. Notifications are deduplicated for acknowledged sends, but a crash after Bale accepts a message and before the local acknowledgment is saved can cause a retry/duplicate. Invoice sends with an uncertain outcome are cancelled/recovered safely, never left permanently reserved.
- The five-minute unsent-invoice cleanup is a technical recovery rule, separate from the two-hour validity that starts only after a successful send is recorded.
- No online payment or settlement is implemented. Live bot behavior must be checked with the manual guide above.
