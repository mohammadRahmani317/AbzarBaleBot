package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.Order;
import ir.toolsmarket.tools_market_bot.service.OrderStorageService;
import ir.toolsmarket.tools_market_bot.service.UserAccessService;
import ir.toolsmarket.tools_market_bot.model.UserPermission;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

@Service
public class SellerMenuService {

    private final BaleBotClient baleBotClient;
    private final OrderStorageService orderStorageService;
    private final UserAccessService userAccessService;

    public SellerMenuService(
            @Qualifier("sellerBaleBotClient") BaleBotClient baleBotClient,
            OrderStorageService orderStorageService,
            UserAccessService userAccessService
    ) {
        this.baleBotClient = baleBotClient;
        this.orderStorageService = orderStorageService;
        this.userAccessService = userAccessService;
    }

    public void showMainMenu(String chatId) {

        String message = """
                سلام، به پنل مدیریت ابزارمارکت خوش آمدید.

                لطفاً یکی از گزینه‌های زیر را انتخاب کنید:
                """;

        List<List<Map<String, Object>>> keyboard = new java.util.ArrayList<>();
        keyboard.add(List.of(Map.of("text", "📦 مشاهده سفارش‌ها")));
        if (userAccessService.hasPermission(chatId, UserPermission.PRICE_MANAGER)) {
            keyboard.add(List.of(Map.of("text", "💰 مدیریت قیمت محصولات")));
        }
        Map<String, Object> replyMarkup = Map.of(
                "keyboard", keyboard,
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
                        .append("\n");

                if (order.unitPriceSnapshot() != null) {
                    message.append("💰 قیمت زمان ثبت سفارش: ")
                            .append(NumberFormat.getNumberInstance(Locale.forLanguageTag("fa-IR"))
                                    .format(order.unitPriceSnapshot()))
                            .append(" تومان\n");
                }

                message.append("━━━━━━━━━━━━━━")
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