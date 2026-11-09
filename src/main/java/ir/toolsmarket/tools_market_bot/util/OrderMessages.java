package ir.toolsmarket.tools_market_bot.util;

import ir.toolsmarket.tools_market_bot.model.*;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import java.util.*;

public final class OrderMessages {
    private OrderMessages() { }
    public static Map<String, Object> button(String text, String callback) {
        return Map.of("text", text, "callback_data", callback);
    }
    public static String items(List<OrderItem> items) {
        StringBuilder text = new StringBuilder();
        for (OrderItem item : items) {
            text.append(item.productName()).append("\nتعداد: ").append(BotFormatting.number(item.quantity()))
                    .append("\nقیمت واحد: ").append(item.unitPriceSnapshot() == null ? "ثبت نشده" : BotFormatting.price(item.unitPriceSnapshot()))
                    .append("\nجمع: ").append(item.unitPriceSnapshot() == null ? "نامشخص" : BotFormatting.price(item.total()))
                    .append("\n━━━━━━━━━━━━━━\n");
        }
        return text.toString();
    }
    public static String details(Order order) {
        return "🧾 ابزارمارکت\nشماره سفارش: " + order.orderId()
                + "\nمشتری: " + order.customerName() + "\nتلفن: " + order.phoneNumber()
                + "\n\n" + items(order.items()) + "\nمبلغ کل: " + (order.items().stream().anyMatch(item -> item.unitPriceSnapshot() == null) ? "نامشخص" : BotFormatting.price(order.totalAmount()))
                + "\nوضعیت: " + (order.legacy() ? "قدیمی؛ وضعیت نامشخص (" + Objects.toString(order.legacyStatus(), "") + ")" : order.status().title())
                + "\nایجاد — " + BotFormatting.dateTime(order.createdAt())
                + "\nارسال فاکتور — " + BotFormatting.dateTime(order.invoiceSentAt())
                + "\nپایان اعتبار — " + BotFormatting.dateTime(order.expiresAt())
                + "\nتأیید — " + BotFormatting.dateTime(order.confirmedAt())
                + "\nمسئول: " + Objects.toString(order.handledByName(), "—")
                + "\nآماده‌سازی — " + BotFormatting.dateTime(order.preparingStartedAt())
                + "\nتحویل — " + BotFormatting.dateTime(order.deliveredAt())
                + "\nتحویل‌دهنده: " + Objects.toString(order.deliveredByName(), "—")
                + "\nلغو — " + BotFormatting.dateTime(order.cancelledAt())
                + "\nانقضا — " + BotFormatting.dateTime(order.expiredAt());
    }
    /** Buttons are sent only with the final chunk, after all invoice lines have been delivered. */
    public static void send(BaleBotClient client, String chatId, String text, List<List<Map<String, Object>>> buttons) {
        while (text.length() > 3500) {
            int end = text.lastIndexOf('\n', 3500);
            if (end < 1) end = 3500;
            requireSuccess(client.sendMessage(chatId, text.substring(0, end)));
            text = text.substring(end);
        }
        requireSuccess(client.sendMessage(chatId, text, Map.of("inline_keyboard", buttons)));
    }
    public static void requireSuccess(tools.jackson.databind.JsonNode response) {
        if (response == null || !response.path("ok").asBoolean())
            throw new IllegalStateException("ارسال پیام ناموفق بود.");
    }
}
