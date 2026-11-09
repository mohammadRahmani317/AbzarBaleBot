package ir.toolsmarket.tools_market_bot.model;

import java.time.Instant;

public record OrderStatusHistory(String id, String orderId, OrderStatus previousStatus,
                                 OrderStatus newStatus, String changedByChatId,
                                 String changedByName, Instant changedAt, String description) { }
