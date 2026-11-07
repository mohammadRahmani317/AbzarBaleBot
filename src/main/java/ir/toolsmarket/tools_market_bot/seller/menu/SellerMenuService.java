
package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.Order;
import ir.toolsmarket.tools_market_bot.service.OrderStorageService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
public class SellerMenuService {

    private final BaleBotClient baleBotClient;
    private final OrderStorageService orderStorageService;

    public SellerMenuService(
            @Qualifier("sellerBaleBotClient") BaleBotClient baleBotClient,
            OrderStorageService orderStorageService
    ) {
        this.baleBotClient = baleBotClient;
        this.orderStorageService = orderStorageService;
    }

    public void showMainMenu(String chatId) {

        String message = """
                سلام، به پنل مدیریت ابزارمارکت خوش آمدید.

                لطفاً یکی از گزینه‌های زیر را انتخاب کنید:
                """;

        Map<String, Object> replyMarkup = Map.of(
                "keyboard", List.of(
                        List.of(
                                Map.of("text", "📦 مشاهده سفارش‌ها")
                        )
                ),
                "resize_keyboard", true
        );

        baleBotClient.sendMessage(chatId, message, replyMarkup);
    }

    // نمایش فهرست سفارش‌ها برای فروشنده
    public void showOrders(String chatId) {

        try {

            List<Order> orders = orderStorageService.findAll();

            if (orders.isEmpty()) {
                baleBotClient.sendMessage(
                        chatId,
                        "📭 هنوز هیچ سفارشی ثبت نشده است."
                );
                return;
            }

            StringBuilder message = new StringBuilder(
                    "📦 فهرست سفارش‌های ثبت‌شده\n\n"
            );

            for (Order order : orders) {

                message.append("🧾 شماره سفارش: ")
                        .append(order.orderId())
                        .append("\n")
                        .append("📦 محصول: ")
                        .append(order.productName())
                        .append("\n")
                        .append("🔢 تعداد: ")
                        .append(order.quantity())
                        .append("\n")
                        .append("👤 مشتری: ")
                        .append(order.customerName())
                        .append("\n")
                        .append("📞 تماس: ")
                        .append(order.phoneNumber())
                        .append("\n")
                        .append("━━━━━━━━━━━━━━")
                        .append("\n\n");
            }

            baleBotClient.sendMessage(chatId, message.toString());

        } catch (IOException e) {

            baleBotClient.sendMessage(
                    chatId,
                    "در دریافت فهرست سفارش‌ها مشکلی پیش آمد. لطفاً دوباره تلاش کنید."
            );
        }
    }

    public void showHelp(String chatId) {
        baleBotClient.sendMessage(
                chatId,
                "برای مدیریت سفارش‌های مشتریان از گزینه «📦 مشاهده سفارش‌ها» استفاده کنید."
        );
    }
}
