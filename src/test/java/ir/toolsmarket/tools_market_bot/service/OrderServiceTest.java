package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderServiceTest {
    @TempDir Path directory;
    TestFixture f;
    @BeforeEach void setup() { f = new TestFixture(directory); }

    @Test void cartSupportsMultipleProductsUpdatesRemovalAndTotalsWithoutReservations() {
        f.cart.setQuantity("customer", 1, 2); f.cart.setQuantity("customer", 2, 3);
        assertEquals(new BigDecimal("800000"), f.cart.total("customer"));
        f.cart.setQuantity("customer", 1, 4);
        assertEquals(new BigDecimal("1000000"), f.cart.total("customer"));
        f.cart.remove("customer", 2);
        assertEquals(Map.of(1, 4), f.cart.quantities("customer"));
        assertEquals(0, f.inventory.find(1).reservedQuantity());
        assertThrows(IllegalArgumentException.class, () -> f.cart.setQuantity("customer", 1, 11));
        assertThrows(IllegalArgumentException.class, () -> f.cart.setQuantity("customer", 1, 0));
    }

    @Test void invoiceSnapshotsAllPricesAndCustomerInformation() {
        Order order = f.orders.createInvoice("customer", Map.of(1, 2, 2, 3));
        assertEquals(2, order.items().size());
        assertEquals(new BigDecimal("800000"), order.totalAmount());
        assertEquals(2, f.inventory.find(1).reservedQuantity());
        assertEquals(3, f.inventory.find(2).reservedQuantity());
        f.prices.updatePrice(1, new BigDecimal("999999"), "seller");
        f.customers.update("customer", "نام جدید مشتری", "09123456787");
        var saved = f.orders.findForOperator(order.orderId(), "seller");
        assertEquals(new BigDecimal("800000"), saved.totalAmount());
        assertEquals("مشتری اول", saved.customerName());
        assertEquals("09123456789", saved.phoneNumber());
        assertNull(saved.invoiceSentAt());
        assertThrows(IllegalArgumentException.class, () -> f.orders.confirm(order.orderId(), "customer"));
    }

    @Test void checkoutRevalidatesStockAndMissingPricesAtomically() {
        f.cart.setQuantity("customer", 1, 8);
        f.invoice("customer2", 4);
        assertThrows(IllegalArgumentException.class, () -> f.orders.createInvoice("customer", f.cart.quantities("customer")));
        assertEquals(4, f.inventory.find(1).reservedQuantity());
        f.inventory.setQuantity("seller", 4, 5);
        assertThrows(IllegalArgumentException.class, () -> f.orders.createInvoice("customer", Map.of(2, 1, 4, 1)));
        assertEquals(0, f.inventory.find(2).reservedQuantity());
        assertThrows(IllegalArgumentException.class, () -> f.orders.createInvoice("customer", Map.of(5, 1)));
    }

    @Test void confirmationDeductsExactlyOnceAndKeepsHistory() {
        var invoice = f.invoice("customer", 3);
        assertEquals(invoice.invoiceSentAt().plus(Duration.ofHours(2)), invoice.expiresAt());
        assertTrue(f.orders.pendingNotifications().isEmpty());
        assertEquals(7, f.inventory.find(1).availableQuantity());
        f.orders.confirm(invoice.orderId(), "customer"); f.orders.confirm(invoice.orderId(), "customer");
        assertEquals(7, f.inventory.find(1).totalQuantity());
        assertEquals(0, f.inventory.find(1).reservedQuantity());
        assertEquals(1, f.orders.history(invoice.orderId(), "seller").stream().filter(h -> h.newStatus() == OrderStatus.CONFIRMED).count());
        assertThrows(IllegalArgumentException.class, () -> f.orders.cancel(invoice.orderId(), "customer"));
        assertEquals(3, f.orders.pendingNotifications().size());
        assertFalse(f.orders.pendingNotifications().stream().anyMatch(n -> n.recipientChatId().equals("super")));
    }

    @Test void cancellationReleasesExactlyOnceAndPreservesRecord() {
        var order = f.invoice("customer", 3);
        f.orders.cancel(order.orderId(), "customer"); f.orders.cancel(order.orderId(), "customer");
        assertEquals(10, f.inventory.find(1).totalQuantity());
        assertEquals(0, f.inventory.find(1).reservedQuantity());
        assertNotNull(f.orders.findForOperator(order.orderId(), "seller").cancelledAt());
        assertThrows(IllegalArgumentException.class, () -> f.orders.confirm(order.orderId(), "customer"));
    }

    @Test void customerCannotActOnAnotherCustomersInvoice() {
        var invoice = f.invoice("customer", 3);
        assertThrows(IllegalArgumentException.class, () -> f.orders.confirm(invoice.orderId(), "customer2"));
        assertThrows(IllegalArgumentException.class, () -> f.orders.cancel(invoice.orderId(), "customer2"));
        assertEquals(3, f.inventory.find(1).reservedQuantity());
    }

    @Test void expirationAtDeadlineIsEnforcedByBothCustomerActions() {
        var first = f.invoice("customer", 3); var second = f.invoice("customer2", 2);
        f.clock.advance(Duration.ofHours(2));
        assertThrows(IllegalArgumentException.class, () -> f.orders.confirm(first.orderId(), "customer"));
        assertThrows(IllegalArgumentException.class, () -> f.orders.cancel(second.orderId(), "customer2"));
        assertEquals(0, f.inventory.find(1).reservedQuantity());
        assertEquals(2, f.orders.list("seller", Set.of(OrderStatus.EXPIRED)).size());
        assertEquals("SYSTEM", f.orders.history(first.orderId(), "seller").getLast().changedByChatId());
    }

    @Test void confirmationJustBeforeDeadlineWorks() {
        var order = f.invoice("customer", 3);
        f.clock.advance(Duration.ofHours(2).minusNanos(1));
        assertEquals(OrderStatus.CONFIRMED, f.orders.confirm(order.orderId(), "customer").status());
    }

    @Test void restartExpiresPersistedInvoicesOnce() {
        var order = f.invoice("customer", 3);
        f.clock.advance(Duration.ofHours(3));
        OrderService restarted = f.orders(f.storage());
        restarted.expireInvoices(); restarted.expireInvoices();
        assertEquals(10, f.inventory.find(1).availableQuantity());
        assertEquals(OrderStatus.EXPIRED, restarted.findForOperator(order.orderId(), "seller").status());
        assertEquals(1, restarted.history(order.orderId(), "seller").stream().filter(h -> h.newStatus() == OrderStatus.EXPIRED).count());
    }

    @Test void unsentInvoicesHaveNoTwoHourDeadlineAndAreRecoveredAfterCrash() {
        var order = f.orders.createInvoice("customer", Map.of(1, 3));
        assertNull(order.expiresAt());
        f.clock.advance(Duration.ofMinutes(5));
        f.orders(f.storage()).expireInvoices();
        assertEquals(10, f.inventory.find(1).availableQuantity());
        assertEquals(OrderStatus.CANCELLED, f.orders.findForOperator(order.orderId(), "seller").status());
    }

    @Test void failedDeliveryReleasesReservationButDoesNotTouchASentInvoice() {
        var order = f.orders.createInvoice("customer", Map.of(1, 3));
        f.orders.invoiceDeliveryFailed(order.orderId(), "customer");
        f.orders.invoiceDeliveryFailed(order.orderId(), "customer");
        assertEquals(10, f.inventory.find(1).availableQuantity());
        var sent = f.invoice("customer", 2);
        f.orders.invoiceDeliveryFailed(sent.orderId(), "customer");
        assertEquals(2, f.inventory.find(1).reservedQuantity());
    }

    @ParameterizedTest @ValueSource(strings = {"seller", "admin", "super"})
    void everyOperationalRoleCanPriceManageStockOrderPrepareDeliverAndReport(String actor) {
        assertTrue(f.access.hasPermission(actor, UserPermission.PRICE_MANAGER));
        f.prices.updatePrice(1, new BigDecimal("450000"), actor);
        f.inventory.setQuantity(actor, 1, 20);
        f.customers.update(actor, "مشتری همکار", "09123456786");
        var order = f.invoice(actor, 3);
        f.orders.confirm(order.orderId(), actor);
        assertEquals(1, f.orders.list(actor, Set.of(OrderStatus.CONFIRMED)).size());
        f.orders.startPreparation(order.orderId(), actor);
        assertEquals(OrderStatus.DELIVERED, f.orders.deliver(order.orderId(), actor).status());
        assertNotNull(new OrderReportService(f.storage, f.catalog, f.access).report(actor));
        assertEquals(17, f.inventory.find(1).totalQuantity());
    }

    @Test void preparationIsClaimedOnceAndOnlyResponsibleSellerOrManagersDeliver() {
        var order = f.invoice("customer", 3);
        assertThrows(IllegalArgumentException.class, () -> f.orders.startPreparation(order.orderId(), "seller"));
        f.orders.confirm(order.orderId(), "customer");
        assertThrows(IllegalArgumentException.class, () -> f.orders.deliver(order.orderId(), "super"));
        f.orders.startPreparation(order.orderId(), "seller");
        assertEquals("seller", f.orders.startPreparation(order.orderId(), "other").handledByChatId());
        assertThrows(IllegalArgumentException.class, () -> f.orders.deliver(order.orderId(), "other"));
        f.orders.deliver(order.orderId(), "super"); f.orders.deliver(order.orderId(), "admin");
        assertThrows(IllegalArgumentException.class, () -> f.orders.startPreparation(order.orderId(), "seller"));
        assertEquals(7, f.inventory.find(1).totalQuantity());
        assertEquals("super", f.orders.findForOperator(order.orderId(), "seller").deliveredByChatId());
    }

    @Test void unauthorizedAndUnapprovedUsersCannotOperateAndPriceHistoryIsSuperAdminOnly() {
        f.access.registerPending("pending", "کاربر");
        for (String actor : List.of("unknown", "pending")) {
            assertThrows(IllegalArgumentException.class, () -> f.inventory.setQuantity(actor, 1, 100));
            assertThrows(IllegalArgumentException.class, () -> f.prices.updatePrice(1, BigDecimal.TEN, actor));
            assertThrows(IllegalArgumentException.class, () -> f.orders.list(actor, Set.of()));
            assertThrows(IllegalArgumentException.class, () -> f.orders.startPreparation("missing", actor));
            assertThrows(IllegalArgumentException.class, () -> f.orders.deliver("missing", actor));
        }
        f.access.setPermission("seller", UserPermission.VIEW_PRICE_HISTORY, true);
        assertThrows(IllegalArgumentException.class, () -> f.prices.findHistory(1, "seller"));
        assertThrows(IllegalArgumentException.class, () -> f.prices.findHistory(1, "admin"));
        assertFalse(f.prices.findHistory(1, "super").isEmpty());
    }

    @Test void manualStockCannotFallBelowReservedAndChangesAreAudited() {
        f.invoice("customer", 3);
        assertThrows(IllegalArgumentException.class, () -> f.inventory.setQuantity("seller", 1, 2));
        assertThrows(IllegalArgumentException.class, () -> f.inventory.setQuantity("seller", 1, -1));
        f.inventory.setQuantity("super", 1, 3);
        assertEquals(0, f.inventory.find(1).availableQuantity());
        assertEquals("super", f.storage.snapshot().inventoryHistory().getLast().changedByChatId());
    }

    @Test void concurrentCheckoutCannotOversell() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (String customer : List.of("customer", "customer2")) results.add(executor.submit(() -> {
                start.await();
                try { f.orders.createInvoice(customer, Map.of(1, 7)); return true; }
                catch (IllegalArgumentException exception) { return false; }
            }));
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) if (result.get(5, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(7, f.inventory.find(1).reservedQuantity());
        } finally { executor.shutdownNow(); }
    }

    @Test void concurrentConfirmationAndExpirationMaintainStockConsistency() throws Exception {
        var order = f.invoice("customer", 3);
        f.clock.advance(Duration.ofHours(2));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> confirmation = executor.submit(() -> assertThrows(IllegalArgumentException.class,
                    () -> f.orders.confirm(order.orderId(), "customer")));
            Future<?> expiration = executor.submit(() -> f.orders.expireInvoices());
            confirmation.get(5, TimeUnit.SECONDS); expiration.get(5, TimeUnit.SECONDS);
        }
        assertEquals(10, f.inventory.find(1).totalQuantity());
        assertEquals(0, f.inventory.find(1).reservedQuantity());
        assertEquals(1, f.orders.history(order.orderId(), "seller").stream().filter(h -> h.newStatus() == OrderStatus.EXPIRED).count());
    }

    @Test void duplicateCheckoutReturnsSamePendingInvoice() {
        var first = f.invoice("customer", 3);
        var second = f.orders.createInvoice("customer", Map.of(1, 5));
        assertEquals(first.orderId(), second.orderId());
        assertEquals(3, f.inventory.find(1).reservedQuantity());
    }

    @Test void customerProfilesArePartialPersistentReusableAndEditable() {
        CustomerService service = new CustomerService(f.storage());
        assertTrue(service.find("new").isEmpty());
        assertFalse(service.update("new", "مشتری جدید", null).complete());
        assertTrue(service.update("new", null, "۰۹۱۲۳۴۵۶۷۸۹").complete());
        assertTrue(service.update("new", null, "+989123456789").complete());
        Instant registered = service.find("new").orElseThrow().registeredAt();
        service.update("new", "نام ویرایش شده", null);
        var saved = new CustomerService(f.storage()).find("new").orElseThrow();
        assertEquals("نام ویرایش شده", saved.name());
        assertEquals(registered, saved.registeredAt());
        assertThrows(IllegalArgumentException.class, () -> service.update("new", "x", null));
        assertThrows(IllegalArgumentException.class, () -> service.update("new", null, "123"));
    }

    @Test void legacyOrdersMigrateWithoutInventingStatusPriceOrInventory() throws Exception {
        Path legacyDirectory = directory.resolve("legacy"); Files.createDirectories(legacyDirectory);
        Path source = legacyDirectory.resolve("orders.json");
        String json = """
                [{"orderId":"old","chatId":"123","productId":1,"productName":"دریل",
                  "quantity":2,"customerName":"مشتری","phoneNumber":"09123456789",
                  "createdAt":"2020-01-01T00:00:00Z","unitPriceSnapshot":400000,"status":"UNKNOWN"},
                 {"orderId":"unpriced","chatId":"123","productId":1,"productName":"دریل",
                  "quantity":3,"customerName":"مشتری","createdAt":"2020-01-01T00:00:00Z"}]
                """;
        Files.writeString(source, json);
        OrderStorageService store = new OrderStorageService(f.mapper, legacyDirectory.resolve("state.json").toString(), source.toString());
        Order old = store.findAll().getFirst();
        assertTrue(old.legacy()); assertNull(old.status()); assertEquals("UNKNOWN", old.legacyStatus());
        assertEquals(new BigDecimal("800000"), old.totalAmount());
        assertEquals(1, old.items().size()); assertEquals(2, old.items().getFirst().quantity());
        assertNull(store.findAll().get(1).items().getFirst().unitPriceSnapshot());
        assertTrue(store.snapshot().inventory().isEmpty());
        assertEquals(json, Files.readString(source));
        assertEquals(json, Files.readString(source.resolveSibling("orders.json.pre-cart.bak")));
        assertEquals(2, new OrderStorageService(f.mapper, legacyDirectory.resolve("state.json").toString(), source.toString()).findAll().size());
    }

    @Test void reportsDoNotDoubleCountDeliveredOrdersAndRetainCancelledExpiredAndLegacyOrders() {
        var delivered = f.invoice("customer", 3); f.orders.confirm(delivered.orderId(), "customer");
        f.orders.startPreparation(delivered.orderId(), "seller"); f.orders.deliver(delivered.orderId(), "seller");
        var cancelled = f.invoice("customer", 2); f.orders.cancel(cancelled.orderId(), "customer");
        var expired = f.invoice("customer2", 1); f.clock.advance(Duration.ofHours(2)); f.orders.expireInvoices();
        String text = new OrderReportService(f.storage, f.catalog, f.access).report("super");
        assertTrue(text.contains("تأییدشده و در حال آماده‌سازی: ۰ تومان"));
        assertTrue(text.contains("تحویل‌شده: ۳۰۰٬۰۰۰ تومان"));
        assertEquals(1, f.orders.list("seller", Set.of(OrderStatus.CANCELLED)).size());
        assertEquals(1, f.orders.list("seller", Set.of(OrderStatus.EXPIRED)).size());
        assertTrue(f.orders.list("seller", Set.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING)).isEmpty());
    }
    @Test void failedPersistenceLeavesBothOrderAndInventoryUnchanged() {
        var invoice = f.invoice("customer", 3);
        var failingMapper = org.mockito.Mockito.spy(f.mapper);
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated write failure"))
                .when(failingMapper).writerWithDefaultPrettyPrinter();
        var failedStorage = new OrderStorageService(failingMapper, directory.resolve("state.json").toString(),
                directory.resolve("orders.json").toString());
        assertThrows(IllegalStateException.class, () -> f.orders(failedStorage).confirm(invoice.orderId(), "customer"));
        assertEquals(OrderStatus.WAITING_CUSTOMER, f.orders.findForOperator(invoice.orderId(), "seller").status());
        assertEquals(10, f.inventory.find(1).totalQuantity());
        assertEquals(3, f.inventory.find(1).reservedQuantity());
        assertTrue(f.orders.pendingNotifications().isEmpty());
        f.orders.confirm(invoice.orderId(), "customer");
        assertEquals(7, f.inventory.find(1).totalQuantity());
    }

    @Test void legacyOrdersRemainVisibleInAllOrdersAndDoNotBreakFilteredLists() throws Exception {
        f.storage.save(new Order("old", "customer", 1, "دریل", 2, "مشتری", "09123456789", Instant.EPOCH));
        assertEquals(1, f.orders.list("seller", Set.of()).size());
        assertTrue(f.orders.list("seller", Set.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING)).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> f.orders.confirm("old", "customer"));
        assertThrows(IllegalArgumentException.class, () -> f.orders.startPreparation("old", "super"));
    }

}
