package ir.toolsmarket.tools_market_bot.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

/** Legacy single-product properties remain readable and are never discarded. */
public record Order(
        String orderId, String chatId, Integer productId, String productName,
        Integer quantity, String customerName, String phoneNumber, Instant createdAt,
        BigDecimal unitPriceSnapshot, List<OrderItem> items, BigDecimal totalAmount,
        OrderStatus status, Instant invoiceSentAt, Instant expiresAt, Instant confirmedAt,
        Instant preparingStartedAt, Instant deliveredAt, String handledByChatId,
        String handledByName, Instant cancelledAt, Instant expiredAt,
        String deliveredByChatId, String deliveredByName, boolean legacy, String legacyStatus
) {
    public Order {
        if (items == null || items.isEmpty()) {
            items = productId == null ? List.of() : List.of(
                    new OrderItem(productId, productName, quantity, unitPriceSnapshot));
        }
        items = List.copyOf(items);
        totalAmount = items.stream().map(OrderItem::total).reduce(BigDecimal.ZERO, BigDecimal::add);
        legacy = legacy || status == null;
    }

    public Order(String orderId, String chatId, Integer productId, String productName,
                 Integer quantity, String customerName, String phoneNumber, Instant createdAt,
                 BigDecimal unitPriceSnapshot) {
        this(orderId, chatId, productId, productName, quantity, customerName, phoneNumber,
                createdAt, unitPriceSnapshot, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, true, null);
    }

    public Order(String orderId, String chatId, Integer productId, String productName,
                 Integer quantity, String customerName, String phoneNumber, Instant createdAt) {
        this(orderId, chatId, productId, productName, quantity, customerName, phoneNumber, createdAt, null);
    }

    public static Order invoice(String id, Customer customer, List<OrderItem> items, Instant now) {
        return new Order(id, customer.chatId(), null, null, null, customer.name(),
                customer.phoneNumber(), now, null, items, null, OrderStatus.WAITING_CUSTOMER,
                null, null, null, null, null, null, null, null, null, null, null, false, null);
    }

    public String customerChatId() { return chatId; }

    public Order sent(Instant at) {
        return new Order(orderId, chatId, productId, productName, quantity, customerName,
                phoneNumber, createdAt, unitPriceSnapshot, items, totalAmount, status,
                at, at.plus(Duration.ofHours(2)), confirmedAt, preparingStartedAt, deliveredAt,
                handledByChatId, handledByName, cancelledAt, expiredAt,
                deliveredByChatId, deliveredByName, legacy, legacyStatus);
    }

    public Order transition(OrderStatus next, String actor, String name, Instant at) {
        return new Order(orderId, chatId, productId, productName, quantity, customerName,
                phoneNumber, createdAt, unitPriceSnapshot, items, totalAmount, next,
                invoiceSentAt, expiresAt, next == OrderStatus.CONFIRMED ? at : confirmedAt,
                next == OrderStatus.PREPARING ? at : preparingStartedAt,
                next == OrderStatus.DELIVERED ? at : deliveredAt,
                next == OrderStatus.PREPARING ? actor : handledByChatId,
                next == OrderStatus.PREPARING ? name : handledByName,
                next == OrderStatus.CANCELLED ? at : cancelledAt,
                next == OrderStatus.EXPIRED ? at : expiredAt,
                next == OrderStatus.DELIVERED ? actor : deliveredByChatId,
                next == OrderStatus.DELIVERED ? name : deliveredByName, legacy, legacyStatus);
    }
}
