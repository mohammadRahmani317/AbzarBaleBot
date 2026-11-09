package ir.toolsmarket.tools_market_bot.seller.service;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.service.*;
import ir.toolsmarket.tools_market_bot.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.List;
import static ir.toolsmarket.tools_market_bot.util.OrderMessages.button;

@Service
public class OrderNotificationService {
    private static final Logger log = LoggerFactory.getLogger(OrderNotificationService.class);
    private final OrderService orders;
    private final UserAccessService access;
    private final BaleBotClient client;
    public OrderNotificationService(OrderService orders, UserAccessService access,
                                    @Qualifier("sellerBaleBotClient") BaleBotClient client) {
        this.orders = orders; this.access = access; this.client = client;
    }
    @Scheduled(fixedDelayString = "${tools-market.orders.notification-check-ms:15000}")
    public synchronized void sendPending() {
        if (!client.configured()) return;
        for (var notification : orders.pendingNotifications()) {
            try {
                // Recheck at delivery: a recipient may have been disabled or promoted.
                boolean eligible = access.orderNotificationRecipients().stream()
                        .anyMatch(user -> user.chatId().equals(notification.recipientChatId()));
                if (!eligible) { orders.notificationSent(notification); continue; }
                var order = orders.findForOperator(notification.orderId(), notification.recipientChatId());
                OrderMessages.send(client, notification.recipientChatId(), "🔔 سفارش جدید تأیید شد\nشماره: "
                        + order.orderId() + "\nمشتری: " + order.customerName() + "\nمبلغ: " + BotFormatting.price(order.totalAmount()),
                        List.of(List.of(button("📋 مشاهده سفارش", "orders:view:" + order.orderId()))));
                orders.notificationSent(notification);
            } catch (Exception exception) {
                log.warn("Order notification will retry: order={}, recipient={}, error={}", notification.orderId(),
                        notification.recipientChatId(), exception.getClass().getSimpleName());
            }
        }
    }
}
