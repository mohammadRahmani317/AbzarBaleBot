package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.*;
import ir.toolsmarket.tools_market_bot.util.BotFormatting;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.*;

@Service
public class OrderReportService {
    private final OrderStorageService storage;
    private final ProductCatalogService catalog;
    private final UserAccessService access;
    public OrderReportService(OrderStorageService storage, ProductCatalogService catalog, UserAccessService access) {
        this.storage = storage; this.catalog = catalog; this.access = access;
    }
    public String report(String actor) {
        access.requireOperator(actor);
        OrderStore state = storage.snapshot();
        List<Order> orders = List.copyOf(state.orders().values());
        StringBuilder text = new StringBuilder("📊 گزارش سفارش‌ها\nکل: " + BotFormatting.number(orders.size()) + "\n");
        for (OrderStatus status : OrderStatus.values()) {
            long count = orders.stream().filter(order -> !order.legacy() && order.status() == status).count();
            text.append(status.title()).append(": ").append(BotFormatting.number(count)).append("\n");
        }
        text.append("قدیمی با وضعیت نامشخص: ").append(BotFormatting.number(orders.stream().filter(Order::legacy).count()));
        text.append("\n\nارزش سفارش‌ها (مبلغ دریافت‌شده نیست)\n");
        // Disjoint current-state buckets: delivered value never also contributes to confirmed value.
        for (var entry : Map.of("تأییدشده و در حال آماده‌سازی", Set.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING),
                "تحویل‌شده", Set.of(OrderStatus.DELIVERED), "لغوشده", Set.of(OrderStatus.CANCELLED),
                "منقضی‌شده", Set.of(OrderStatus.EXPIRED)).entrySet()) {
            BigDecimal value = orders.stream().filter(order -> !order.legacy() && entry.getValue().contains(order.status()))
                    .map(Order::totalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            text.append(entry.getKey()).append(": ").append(BotFormatting.price(value)).append("\n");
        }
        text.append("\n📦 گزارش محصولات\n");
        for (Product product : catalog.findAll()) {
            ProductInventory stock = state.inventory().getOrDefault(product.id(), new ProductInventory(product.id(), 0, 0, null));
            text.append(product.name()).append("\nکل: ").append(BotFormatting.number(stock.totalQuantity()))
                    .append(" | رزرو: ").append(BotFormatting.number(stock.reservedQuantity()))
                    .append(" | قابل فروش: ").append(BotFormatting.number(stock.availableQuantity()))
                    .append("\nتعداد تأییدشده و آماده‌سازی: ")
                    .append(BotFormatting.number(productQuantity(orders, product.id(), Set.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING))))
                    .append(" | تحویل‌شده: ")
                    .append(BotFormatting.number(productQuantity(orders, product.id(), Set.of(OrderStatus.DELIVERED))))
                    .append("\n\n");
        }
        return text.toString();
    }
    private long productQuantity(List<Order> orders, Integer id, Set<OrderStatus> statuses) {
        return orders.stream().filter(order -> !order.legacy() && statuses.contains(order.status()))
                .flatMap(order -> order.items().stream()).filter(item -> id.equals(item.productId()))
                .mapToLong(OrderItem::quantity).sum();
    }
}
