package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProductManagementTest {
    @TempDir Path directory;
    TestFixture f;
    @BeforeEach void setup() { f = new TestFixture(directory); }

    @Test void createEditStockAndSoftDeletePersistWithoutChangingOrdersOrPrices() {
        Product created = f.catalog.create("seller", "HAND_TOOLS", "آچار", "برند اول", "توضیح", "", 8);
        f.prices.updatePrice(created.id(), BigDecimal.valueOf(15000), "seller", "فروشنده");
        Order order = f.orders.createInvoice("customer", Map.of(created.id(), 2));
        f.orders.markInvoiceSent(order.orderId(), "customer");
        f.catalog.update("seller", created.id(), "HAND_TOOLS", "آچار جدید", "برند دوم", "توضیح جدید", "");
        f.inventory.adjustQuantity("seller", created.id(), 4);
        f.inventory.adjustQuantity("seller", created.id(), -2);
        assertEquals(10, f.catalog.findById(created.id()).orElseThrow().stockQuantity());
        ProductCatalogService restarted = new ProductCatalogService(f.mapper, f.storage(), f.access);
        assertEquals("برند دوم", restarted.findById(created.id()).orElseThrow().brand());
        assertEquals(10, restarted.findById(created.id()).orElseThrow().stockQuantity());
        f.catalog.delete("seller", created.id());
        assertTrue(restarted.findById(created.id()).isEmpty());
        assertTrue(f.storage.snapshot().products().get(created.id()).deleted());
        assertEquals("آچار", f.orders.findForOperator(order.orderId(), "seller").items().getFirst().productName());
        f.orders.confirm(order.orderId(), "customer");
        assertEquals(8, f.inventory.find(created.id()).totalQuantity());
        assertEquals(1, f.prices.findHistory(created.id(), "super").size());
        assertEquals(BigDecimal.valueOf(15000), f.prices.findCurrentPrice(created.id()).orElseThrow());
        assertFalse(new ProductCatalogService(f.mapper, f.storage(), f.access).findAll().stream().anyMatch(p -> p.id().equals(created.id())));
        Product next = f.catalog.create("seller", "HAND_TOOLS", "بعدی", "برند", "", "", 0);
        assertTrue(next.id() > created.id());
    }
    @Test void deletedProductsCannotBeCheckedOutEvenFromAnExistingCart() {
        f.cart.setQuantity("customer", 1, 2);
        f.catalog.delete("seller", 1);
        assertThrows(IllegalArgumentException.class, () -> f.orders.createInvoice("customer", f.cart.quantities("customer")));
        assertEquals(0, f.inventory.find(1).reservedQuantity());
        assertThrows(IllegalArgumentException.class, () -> f.inventory.setQuantity("seller", 1, 10));
        assertThrows(IllegalArgumentException.class, () -> f.prices.updatePrice(1, BigDecimal.TEN, "seller", "seller"));
    }
    @Test void accessAndReservationBoundsAreEnforcedInServices() {
        f.access.registerPending("pending", "pending");
        for (String actor : List.of("customer", "pending")) {
            assertThrows(IllegalArgumentException.class, () -> f.catalog.create(actor, "HAND_TOOLS", "نام", "برند", "", "", 1));
            assertThrows(IllegalArgumentException.class, () -> f.catalog.update(actor, 1, "HAND_TOOLS", "نام", "برند", "", ""));
            assertThrows(IllegalArgumentException.class, () -> f.catalog.delete(actor, 1));
            assertThrows(IllegalArgumentException.class, () -> f.inventory.adjustQuantity(actor, 1, 1));
        }
        f.invoice("customer", 3);
        assertThrows(IllegalArgumentException.class, () -> f.inventory.adjustQuantity("seller", 1, -8));
        assertEquals(10, f.catalog.findById(1).orElseThrow().stockQuantity());
        f.inventory.setQuantity("seller", 1, 3);
        assertEquals(0, f.inventory.find(1).availableQuantity());
        assertThrows(IllegalArgumentException.class, () -> f.inventory.adjustQuantity("seller", 1, Integer.MAX_VALUE));
    }
    @Test void preProductAggregateAndOldProductSchemaAreReadWithoutDataLoss() throws Exception {
        Order order = f.invoice("customer", 2);
        var tree = (tools.jackson.databind.node.ObjectNode) f.mapper.readTree(directory.resolve("state.json").toFile());
        tree.remove("products");
        f.mapper.writeValue(directory.resolve("state.json").toFile(), tree);
        ProductCatalogService restarted = new ProductCatalogService(f.mapper, f.storage(), f.access);
        assertEquals(10, restarted.findById(1).orElseThrow().stockQuantity());
        assertEquals(2, f.inventory.find(1).reservedQuantity());
        assertEquals(order, f.orders.findForOperator(order.orderId(), "seller"));
        Product old = f.mapper.readValue("{\"id\":999,\"categoryCode\":\"HAND_TOOLS\",\"name\":\"old\"}", Product.class);
        assertEquals("", old.brand()); assertFalse(old.deleted()); assertEquals(0, old.stockQuantity());
    }
    @Test void failedProductWritesLeaveCatalogInventoryAndExistingOrdersUnchanged() throws Exception {
        var order = f.invoice("customer", 2);
        byte[] before = Files.readAllBytes(directory.resolve("state.json"));
        var mapper = org.mockito.Mockito.spy(f.mapper);
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated disk failure")).when(mapper).writerWithDefaultPrettyPrinter();
        var storage = new OrderStorageService(mapper, directory.resolve("state.json").toString(), directory.resolve("orders.json").toString());
        var catalog = new ProductCatalogService(mapper, storage, f.access);
        var inventory = new InventoryService(storage, f.access, catalog);
        assertThrows(IllegalStateException.class, () -> catalog.create("seller", "HAND_TOOLS", "نام", "برند", "", "", 5));
        assertThrows(IllegalStateException.class, () -> catalog.update("seller", 1, "HAND_TOOLS", "نام", "برند", "", ""));
        assertThrows(IllegalStateException.class, () -> catalog.delete("seller", 1));
        assertThrows(IllegalStateException.class, () -> inventory.adjustQuantity("seller", 1, 5));
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("state.json")));
        assertEquals(order, f.orders.findForCustomer(order.orderId(), "customer"));
    }
}
