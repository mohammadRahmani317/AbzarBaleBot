package ir.toolsmarket.tools_market_bot.model;

public record OrderNotification(String orderId, String recipientChatId, boolean sent) { }
