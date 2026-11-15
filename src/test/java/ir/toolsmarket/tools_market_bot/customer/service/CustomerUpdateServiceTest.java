package ir.toolsmarket.tools_market_bot.customer.service;

import ir.toolsmarket.tools_market_bot.customer.menu.BotMenuService;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.*;
import ir.toolsmarket.tools_market_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerUpdateServiceTest {
    @TempDir Path directory;
    TestFixture f;
    BaleBotClient client;
    CustomerUpdateService handler;
    @BeforeEach void setup() {
        f = new TestFixture(directory);
        client = mock(BaleBotClient.class);
        JsonNode success = f.mapper.readTree("{\"ok\":true}");
        when(client.sendMessage(anyString(), anyString())).thenReturn(success);
        when(client.sendMessage(anyString(), anyString(), anyMap())).thenReturn(success);
        handler = new CustomerUpdateService(client, f.catalog, new BotMenuService(client, f.prices, f.inventory),
                new OrderSessionService(), f.cart, f.customers, f.orders);
    }
    void message(String id, String text) {
        handler.processUpdate(f.mapper.valueToTree(Map.of("message", Map.of("chat", Map.of("id", id),
                "from", Map.of("id", id), "text", text))));
    }
    void callback(String id, String data) {
        handler.processUpdate(f.mapper.valueToTree(Map.of("callback_query", Map.of("id", "callback-id",
                "from", Map.of("id", id), "message", Map.of("chat", Map.of("id", id)), "data", data))));
    }

    @Test void existingProductCallbacksSupportMultiProductCartAndCheckoutWithoutAskingSavedProfileAgain() {
        callback("customer", "product:detail:1");
        callback("customer", "product:order:1"); message("customer", "۲");
        callback("customer", "product:order:2"); message("customer", "3");
        assertEquals(Map.of(1, 2, 2, 3), f.cart.quantities("customer"));
        callback("customer", "cart:checkout");
        var order = f.orders.list("seller", Set.of()).getFirst();
        assertNotNull(order.invoiceSentAt());
        assertEquals(2, order.items().size());
        assertTrue(f.cart.quantities("customer").isEmpty());
        verify(client, never()).sendMessage("customer", "نام و نام خانوادگی را وارد کنید:");
        verify(client, never()).sendMessage("customer", "شماره موبایل را وارد کنید:");
        callback("customer", "invoice:confirm:" + order.orderId());
        assertEquals(OrderStatus.CONFIRMED, f.orders.findForOperator(order.orderId(), "seller").status());
    }

    @Test void firstCheckoutRequestsOnlyMissingInformationAndProfileCanBeEdited() {
        f.cart.setQuantity("new", 1, 1);
        callback("new", "cart:checkout"); message("new", "مشتری جدید");
        assertEquals("مشتری جدید", f.customers.find("new").orElseThrow().name());
        message("new", "۰۹۱۲۳۴۵۶۷۸۹");
        var order = f.orders.list("seller", Set.of()).getFirst();
        callback("new", "profile:name"); message("new", "نام جدید مشتری");
        callback("new", "profile:phone"); message("new", "09123456788");
        assertEquals("نام جدید مشتری", f.customers.find("new").orElseThrow().name());
        assertEquals("مشتری جدید", f.orders.findForOperator(order.orderId(), "seller").customerName());
        assertEquals("09123456789", f.orders.findForOperator(order.orderId(), "seller").phoneNumber());
    }

    @Test void failedInvoiceApiResponseReleasesStockAndKeepsCartForRetry() {
        f.cart.setQuantity("customer", 1, 3);
        when(client.sendMessage(eq("customer"), anyString(), anyMap())).thenReturn(f.mapper.readTree("{\"ok\":false}"));
        callback("customer", "cart:checkout");
        assertEquals(0, f.inventory.find(1).reservedQuantity());
        assertEquals(OrderStatus.CANCELLED, f.orders.list("seller", Set.of()).getFirst().status());
        assertEquals(Map.of(1, 3), f.cart.quantities("customer"));
    }

    @Test void anotherCustomerAndGroupCallbackCannotConfirmAnInvoice() {
        var order = f.invoice("customer", 3);
        callback("customer2", "invoice:confirm:" + order.orderId());
        handler.processUpdate(f.mapper.valueToTree(Map.of("callback_query", Map.of("id", "group",
                "from", Map.of("id", "customer"), "message", Map.of("chat", Map.of("id", "group")),
                "data", "invoice:confirm:" + order.orderId()))));
        assertEquals(OrderStatus.WAITING_CUSTOMER, f.orders.findForOperator(order.orderId(), "seller").status());
    }

    @Test void staleLegacyConfirmationDoesNotConfirmANewInvoice() {
        var order = f.invoice("customer", 3);
        callback("customer", "order:confirm");
        assertEquals(OrderStatus.WAITING_CUSTOMER, f.orders.findForOperator(order.orderId(), "seller").status());
    }
    @Test void customerHistoryMenuAndCallbacksNeverExposeOtherCustomersOrders() {
        var own = f.invoice("customer", 1);
        var foreign = f.invoice("customer2", 1);
        message("customer", "📦 سفارش‌های قبلی من");
        verify(client).sendMessage(eq("customer"), contains("سفارش‌های قبلی"), argThat(markup ->
                markup.toString().contains(own.orderId()) && !markup.toString().contains(foreign.orderId())));
        callback("customer", "customer-orders:view:" + own.orderId());
        verify(client).sendMessage(eq("customer"), contains("قیمت واحد"), anyMap());
        clearInvocations(client);
        callback("customer", "customer-orders:view:" + foreign.orderId());
        verify(client).sendMessage("customer", "سفارش پیدا نشد.");
        verify(client, never()).sendMessage(eq("customer"), contains("قیمت واحد"), anyMap());
    }
    @Test void malformedAndSellerOnlyCallbacksCannotMutateCustomerData() {
        callback("customer", "product:order:1:extra");
        callback("customer", "cart:remove:-1");
        callback("customer", "catalog:delete-confirm:1");
        assertTrue(f.catalog.findById(1).isPresent());
        assertTrue(f.cart.quantities("customer").isEmpty());
        assertTrue(f.orders.listForCustomer("customer").isEmpty());
    }
    @Test void switchingFromQuantityToProfileUsesTheTypedProfileInput() {
        callback("customer", "product:order:1");
        callback("customer", "profile:name"); message("customer", "نام ویرایش‌شده");
        assertEquals("نام ویرایش‌شده", f.customers.find("customer").orElseThrow().name());
        assertTrue(f.cart.quantities("customer").isEmpty());
        callback("customer", "product:order:1"); message("customer", "لغو");
        message("customer", "2"); assertTrue(f.cart.quantities("customer").isEmpty());
    }
    @Test void productDetailsShowBrandCurrentPriceAndAvailableStock() {
        var product = f.catalog.create("seller", "HAND_TOOLS", "محصول نمایشی", "برند نمایشی", "توضیح", "", 7);
        f.prices.updatePrice(product.id(), java.math.BigDecimal.valueOf(50000), "seller", "فروشنده");
        callback("customer", "product:detail:" + product.id());
        verify(client).sendMessage(eq("customer"), argThat(text -> text.contains("برند نمایشی")
                && text.contains("تومان") && text.contains("موجودی قابل فروش") && text.contains("۷")), anyMap());
    }
}
