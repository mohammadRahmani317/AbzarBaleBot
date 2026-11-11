package ir.toolsmarket.tools_market_bot.seller.service;

import ir.toolsmarket.tools_market_bot.config.BaleBotProperties;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.seller.menu.*;
import ir.toolsmarket.tools_market_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SellerUpdateServiceTest {
    @TempDir Path directory;
    TestFixture f;
    BaleBotClient client;
    SellerUpdateService handler;
    PriceManagementMenuService priceMenu;
    int updateId;
    @BeforeEach void setup() {
        f = new TestFixture(directory); client = mock(BaleBotClient.class);
        when(client.configured()).thenReturn(true);
        when(client.sendMessage(anyString(), anyString())).thenReturn(f.mapper.readTree("{\"ok\":true}"));
        when(client.sendMessage(anyString(), anyString(), anyMap())).thenReturn(f.mapper.readTree("{\"ok\":true}"));
        priceMenu = new PriceManagementMenuService(client, f.catalog, f.prices, f.access);
        handler = new SellerUpdateService(client,
                new SellerMenuService(client, f.orders, f.access, new OrderReportService(f.storage, f.catalog, f.access)),
                f.access, new BaleBotProperties("http://localhost", "", "", "super"),
                new SellerAdminMenuService(client, f.access), priceMenu,
                new InventoryMenuService(client, f.inventory, f.catalog, f.access),
                new ProductManagementMenuService(client, f.catalog, f.access));
    }
    void update(Map<String, Object> update) {
        update.put("update_id", updateId++);
        when(client.getUpdates(anyLong())).thenReturn(f.mapper.valueToTree(Map.of("ok", true, "result", List.of(update))));
        handler.receiveUpdates();
    }
    void message(String actor, String text) {
        update(new HashMap<>(Map.of("message", Map.of("chat", Map.of("id", actor), "from", Map.of("id", actor), "text", text))));
    }
    void callback(String actor, String data) {
        update(new HashMap<>(Map.of("callback_query", Map.of("id", "callback", "from", Map.of("id", actor),
                "message", Map.of("chat", Map.of("id", actor)), "data", data))));
    }
    @ParameterizedTest @ValueSource(strings = {"seller", "admin", "super"})
    void operationalMenusAndCallbacksWorkForAllApprovedRoles(String actor) {
        message(actor, "/start");
        verify(client).sendMessage(eq(actor), anyString(), argThat(markup -> markup.toString().contains("مدیریت قیمت محصولات")
                && markup.toString().contains("مدیریت موجودی محصولات") && markup.toString().contains("گزارش سفارش‌ها")));
        callback(actor, "inventory:edit:1"); message(actor, "۱۵");
        assertEquals(15, f.inventory.find(1).totalQuantity());
        callback(actor, "price:edit:1"); message(actor, "۴۵۰٬۰۰۰");
        assertEquals(new java.math.BigDecimal("450000"), f.prices.findCurrentPrice(1).orElseThrow());
        var invoice = f.invoice("customer", 3); f.orders.confirm(invoice.orderId(), "customer");
        message(actor, "📦 مشاهده سفارش‌ها"); callback(actor, "orders:list:active");
        callback(actor, "orders:view:" + invoice.orderId());
        callback(actor, "orders:prepare:" + invoice.orderId());
        callback(actor, "orders:deliver:" + invoice.orderId());
        callback(actor, "orders:history:" + invoice.orderId());
        message(actor, "📊 گزارش سفارش‌ها");
        assertEquals(ir.toolsmarket.tools_market_bot.model.OrderStatus.DELIVERED,
                f.orders.findForOperator(invoice.orderId(), actor).status());
    }
    @Test void priceHistoryAndExistingChartAreAvailableOnlyToSuperAdmin() {
        priceMenu.showHistory("super", 1);
        verify(client).sendMessage(eq("super"), contains("نمودار تغییرات قیمت"));
        verify(client).sendMessage(eq("super"), contains("جزئیات تاریخچه قیمت"));
        priceMenu.showHistory("seller", 1); priceMenu.showHistory("admin", 1);
        verify(client, never()).sendMessage(eq("seller"), contains("نمودار تغییرات قیمت"));
        verify(client, never()).sendMessage(eq("admin"), contains("نمودار تغییرات قیمت"));
    }
    @Test void productWizardEditsDeletesAndAdjustsStockThroughBotUpdates() {
        message("seller", "🛠 مدیریت محصولات");
        callback("seller", "catalog:add");
        message("seller", "محصول جدید"); message("seller", "برند تست"); message("seller", "توضیح محصول");
        message("seller", "HAND_TOOLS"); message("seller", "۱۲");
        var product = f.catalog.findAll().stream().filter(p -> p.name().equals("محصول جدید")).findFirst().orElseThrow();
        callback("seller", "catalog:edit:" + product.id());
        message("seller", "نام ویرایش‌شده"); message("seller", "برند جدید"); message("seller", "-"); message("seller", "-");
        assertEquals("برند جدید", f.catalog.findById(product.id()).orElseThrow().brand());
        callback("seller", "inventory:edit:" + product.id()); message("seller", "+۳");
        callback("seller", "inventory:edit:" + product.id()); message("seller", "-2");
        assertEquals(13, f.catalog.findById(product.id()).orElseThrow().stockQuantity());
        callback("seller", "catalog:delete:" + product.id());
        assertTrue(f.catalog.findById(product.id()).isPresent());
        callback("seller", "catalog:delete-confirm:" + product.id());
        assertTrue(f.catalog.findById(product.id()).isEmpty());
    }
    @Test void revokedAccessDuringProductFormCannotCommitAndCancelLeavesNoProduct() {
        callback("seller", "catalog:add"); message("seller", "نام"); message("seller", "لغو");
        assertTrue(f.catalog.findAll().stream().noneMatch(p -> p.name().equals("نام")));
        callback("seller", "catalog:add"); message("seller", "نام"); message("seller", "برند");
        message("seller", "توضیح"); message("seller", "HAND_TOOLS");
        f.access.updateStatus("seller", ir.toolsmarket.tools_market_bot.model.UserStatus.REJECTED);
        message("seller", "5");
        assertTrue(f.catalog.findAll().stream().noneMatch(p -> p.name().equals("نام")));
    }
    @Test void existingAccessCallbacksKeepRoleBoundariesAfterRefactor() {
        f.access.registerPending("applicant", "متقاضی");
        callback("seller", "access:approve:applicant");
        assertEquals(ir.toolsmarket.tools_market_bot.model.UserStatus.PENDING, f.access.findByChatId("applicant").orElseThrow().status());
        callback("admin", "access:review:applicant"); callback("admin", "access:approve:applicant");
        assertEquals(ir.toolsmarket.tools_market_bot.model.UserStatus.APPROVED, f.access.findByChatId("applicant").orElseThrow().status());
        callback("admin", "access:promote:applicant");
        assertEquals(ir.toolsmarket.tools_market_bot.model.UserRole.SELLER, f.access.findByChatId("applicant").orElseThrow().role());
        callback("super", "access:promote:applicant");
        assertEquals(ir.toolsmarket.tools_market_bot.model.UserRole.ADMIN, f.access.findByChatId("applicant").orElseThrow().role());
        callback("super", "access:demote:applicant");
        callback("admin", "access:deactivate:applicant");
        assertEquals(ir.toolsmarket.tools_market_bot.model.UserStatus.REJECTED, f.access.findByChatId("applicant").orElseThrow().status());
        callback("super", "access:reopen:applicant");
        assertEquals(ir.toolsmarket.tools_market_bot.model.UserStatus.PENDING, f.access.findByChatId("applicant").orElseThrow().status());
    }
    @Test void deletedProductPriceHistoryRemainsAvailableToSuperAdmin() {
        f.catalog.delete("seller", 1);
        callback("super", "price:history:list"); callback("super", "price:history:1");
        verify(client).sendMessage(eq("super"), contains("نمودار تغییرات قیمت"));
        clearInvocations(client);
        callback("seller", "price:history:1");
        verify(client, never()).sendMessage(eq("seller"), contains("نمودار تغییرات قیمت"));
    }
}
