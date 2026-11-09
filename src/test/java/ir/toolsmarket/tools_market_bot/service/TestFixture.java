package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;

public class TestFixture {
    public final ObjectMapper mapper = JsonMapper.builder().build();
    public final Path directory;
    public final UserAccessService access;
    public final ProductCatalogService catalog;
    public final ProductPriceService prices;
    public final OrderStorageService storage;
    public final InventoryService inventory;
    public final CustomerService customers;
    public final ShoppingCartService cart;
    public final MutableClock clock = new MutableClock();
    public final OrderService orders;

    public TestFixture(Path directory) {
        this.directory = directory;
        access = new UserAccessService(mapper, directory.resolve("users.json").toString());
        access.ensureSuperAdmin("super");
        addUser("seller", UserRole.SELLER); addUser("other", UserRole.SELLER); addUser("admin", UserRole.ADMIN);
        catalog = new ProductCatalogService(mapper);
        prices = new ProductPriceService(mapper, directory.resolve("prices.json").toString(), access, catalog);
        storage = storage();
        inventory = new InventoryService(storage, access, catalog);
        customers = new CustomerService(storage);
        cart = new ShoppingCartService(catalog, prices, inventory);
        orders = orders(storage);
        for (int id : List.of(1, 2, 3)) {
            prices.updatePrice(id, BigDecimal.valueOf(id * 100_000L), "seller", "فروشنده");
            inventory.setQuantity("seller", id, 10);
        }
        customers.update("customer", "مشتری اول", "09123456789");
        customers.update("customer2", "مشتری دوم", "09123456788");
    }
    private void addUser(String id, UserRole role) {
        access.registerPending(id, id);
        access.updateStatus(id, UserStatus.APPROVED);
        access.updateRole(id, role);
    }
    public OrderStorageService storage() {
        return new OrderStorageService(mapper, directory.resolve("state.json").toString(), directory.resolve("orders.json").toString());
    }
    public OrderService orders(OrderStorageService store) { return new OrderService(store, catalog, prices, access, clock); }
    public Order invoice(String customer, int quantity) {
        Order order = orders.createInvoice(customer, Map.of(1, quantity));
        return orders.markInvoiceSent(order.orderId(), customer);
    }
    public static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-11-09T10:00:00Z");
        public void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
