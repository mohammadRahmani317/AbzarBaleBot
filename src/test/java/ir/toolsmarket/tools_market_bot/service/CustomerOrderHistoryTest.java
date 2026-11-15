package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.Order;
import ir.toolsmarket.tools_market_bot.model.OrderStatus;
import ir.toolsmarket.tools_market_bot.util.OrderMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CustomerOrderHistoryTest {
    @TempDir Path directory;
    TestFixture f;
    @BeforeEach void setup() { f = new TestFixture(directory); }
    @Test void customerHistoryIsPrivateSortedPersistentAndUsesSnapshots() {
        Order first = f.invoice("customer", 2); f.orders.cancel(first.orderId(), "customer");
        f.clock.advance(Duration.ofMinutes(1));
        Order second = f.invoice("customer", 1); f.orders.confirm(second.orderId(), "customer");
        Order foreign = f.invoice("customer2", 1);
        f.catalog.delete("seller", 1);
        var restarted = f.orders(f.storage());
        assertEquals(List.of(second.orderId(), first.orderId()), restarted.listForCustomer("customer").stream().map(Order::orderId).toList());
        assertEquals(OrderStatus.CANCELLED, restarted.findForCustomer(first.orderId(), "customer").status());
        assertEquals(first.items(), restarted.findForCustomer(first.orderId(), "customer").items());
        assertThrows(IllegalArgumentException.class, () -> restarted.findForCustomer(foreign.orderId(), "customer"));
        assertThrows(IllegalArgumentException.class, () -> restarted.findForCustomer("missing", "customer"));
        assertTrue(restarted.listForCustomer("unknown").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> restarted.listForCustomer(""));
    }
    @Test void allStatusesAndLegacyLabelsAreReadableWithoutRewritingHistoricalData() {
        Order invoice = f.invoice("customer", 1);
        for (OrderStatus status : OrderStatus.values()) {
            Order order = invoice.transition(status, "seller", "فروشنده", f.clock.instant());
            f.storage.transact(state -> { state.orders().put(order.orderId(), order); return null; });
            assertTrue(OrderMessages.details(f.orders.findForCustomer(order.orderId(), "customer")).contains(status.title()));
        }
        f.storage.transact(state -> {
            var node = (tools.jackson.databind.node.ObjectNode) f.mapper.valueToTree(invoice);
            node.putNull("status"); node.put("legacy", true); node.put("legacyStatus", "REJECTED");
            state.orders().put(invoice.orderId(), f.mapper.treeToValue(node, Order.class)); return null;
        });
        Order legacy = f.orders.findForCustomer(invoice.orderId(), "customer");
        assertTrue(OrderMessages.details(legacy).contains("رد شده"));
        assertTrue(legacy.legacy()); assertNull(legacy.status()); assertEquals("REJECTED", legacy.legacyStatus());
        assertThrows(IllegalArgumentException.class, () -> f.orders.confirm(legacy.orderId(), "customer"));
    }
    @Test void legacyFileImportRemainsVisibleOnlyToItsOwner() throws Exception {
        Path separate = directory.resolve("legacy-state.json");
        Order old = new Order("OLD-1", "customer", 1, "نام تاریخی", 2, "نام مشتری", "09123456789", f.clock.instant());
        f.mapper.writeValue(directory.resolve("old-orders.json").toFile(), List.of(old));
        var storage = new OrderStorageService(f.mapper, separate.toString(), directory.resolve("old-orders.json").toString());
        var orders = f.orders(storage);
        assertEquals("نام تاریخی", orders.listForCustomer("customer").getFirst().items().getFirst().productName());
        assertTrue(orders.listForCustomer("customer2").isEmpty());
        assertTrue(OrderMessages.details(orders.findForCustomer("OLD-1", "customer")).contains("نامشخص"));
        assertTrue(Files.exists(directory.resolve("old-orders.json.pre-cart.bak")));
    }
}
