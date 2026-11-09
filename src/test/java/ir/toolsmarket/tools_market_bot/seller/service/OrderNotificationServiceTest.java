package ir.toolsmarket.tools_market_bot.seller.service;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.*;
import ir.toolsmarket.tools_market_bot.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrderNotificationServiceTest {
    @TempDir Path directory;
    TestFixture f;
    BaleBotClient client;
    OrderNotificationService service;
    @BeforeEach void setup() {
        f = new TestFixture(directory); client = mock(BaleBotClient.class);
        when(client.configured()).thenReturn(true);
        when(client.sendMessage(anyString(), anyString(), anyMap())).thenReturn(f.mapper.readTree("{\"ok\":true}"));
        service = new OrderNotificationService(f.orders, f.access, client);
    }
    @Test void onlyConfirmedOrdersNotifyApprovedSellersAndAdminsOnceAndNeverSuperAdmin() {
        var order = f.invoice("customer", 3);
        service.sendPending();
        verify(client, never()).sendMessage(anyString(), anyString(), anyMap());
        f.orders.confirm(order.orderId(), "customer"); f.orders.confirm(order.orderId(), "customer");
        service.sendPending(); service.sendPending();
        verify(client, times(1)).sendMessage(eq("seller"), contains(order.orderId()), anyMap());
        verify(client, times(1)).sendMessage(eq("other"), contains(order.orderId()), anyMap());
        verify(client, times(1)).sendMessage(eq("admin"), contains(order.orderId()), anyMap());
        verify(client, never()).sendMessage(eq("super"), anyString(), anyMap());
        assertTrue(f.orders.pendingNotifications().isEmpty());
    }
    @Test void failedNotificationsRetryAfterRestartWithoutRollingBackConfirmationOrStock() {
        var order = f.invoice("customer", 3); f.orders.confirm(order.orderId(), "customer");
        when(client.sendMessage(eq("seller"), anyString(), anyMap())).thenThrow(new IllegalStateException("offline"));
        service.sendPending();
        assertEquals(1, f.orders.pendingNotifications().size());
        assertEquals(OrderStatus.CONFIRMED, f.orders.findForOperator(order.orderId(), "super").status());
        assertEquals(7, f.inventory.find(1).totalQuantity());
        when(client.sendMessage(eq("seller"), anyString(), anyMap())).thenReturn(f.mapper.readTree("{\"ok\":true}"));
        new OrderNotificationService(f.orders(f.storage()), f.access, client).sendPending();
        assertTrue(f.orders.pendingNotifications().isEmpty());
        assertEquals(7, f.inventory.find(1).totalQuantity());
    }
    @Test void promotionToSuperAdminBeforeSendingSuppressesNotification() {
        var order = f.invoice("customer", 1); f.orders.confirm(order.orderId(), "customer");
        f.access.updateRole("seller", UserRole.SUPER_ADMIN);
        f.access.updateStatus("other", UserStatus.REJECTED);
        service.sendPending();
        verify(client, never()).sendMessage(eq("seller"), anyString(), anyMap());
        verify(client, never()).sendMessage(eq("other"), anyString(), anyMap());
        verify(client, times(1)).sendMessage(eq("admin"), anyString(), anyMap());
    }
}
