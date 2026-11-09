package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.*;
import ir.toolsmarket.tools_market_bot.service.*;
import ir.toolsmarket.tools_market_bot.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.*;
import static ir.toolsmarket.tools_market_bot.util.OrderMessages.button;

@Service
public class SellerMenuService {
    private final BaleBotClient client;
    private final OrderService orders;
    private final UserAccessService access;
    private final OrderReportService reports;
    public SellerMenuService(@Qualifier("sellerBaleBotClient") BaleBotClient client,
                            OrderService orders, UserAccessService access, OrderReportService reports) {
        this.client = client; this.orders = orders; this.access = access; this.reports = reports;
    }
    public void showMainMenu(String actor) {
        access.requireOperator(actor);
        client.sendMessage(actor, "به پنل ابزارمارکت خوش آمدید.", Map.of("keyboard", List.of(
                List.of("📦 مشاهده سفارش‌ها"), List.of("💰 مدیریت قیمت محصولات"),
                List.of("📦 مدیریت موجودی محصولات"), List.of("📊 گزارش سفارش‌ها")), "resize_keyboard", true));
    }
    public void showOrders(String actor) {
        access.requireOperator(actor);
        OrderMessages.send(client, actor, "📋 مدیریت سفارش‌ها", List.of(
                List.of(button("📋 سفارش‌های فعال", "orders:list:active")),
                List.of(button("✅ تحویل‌شده", "orders:list:delivered")),
                List.of(button("❌ لغوشده", "orders:list:cancelled")),
                List.of(button("⌛ منقضی‌شده", "orders:list:expired")),
                List.of(button("📊 همه سفارش‌ها", "orders:list:all"))));
    }
    public void showOrderList(String actor, String group) {
        Set<OrderStatus> statuses = switch (group) {
            case "active" -> Set.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING);
            case "delivered" -> Set.of(OrderStatus.DELIVERED);
            case "cancelled" -> Set.of(OrderStatus.CANCELLED);
            case "expired" -> Set.of(OrderStatus.EXPIRED);
            case "all" -> Set.of();
            default -> throw new IllegalArgumentException("فهرست نامعتبر است.");
        };
        List<Order> list = orders.list(actor, statuses);
        if (list.isEmpty()) { client.sendMessage(actor, "سفارشی در این فهرست نیست."); return; }
        // Small pages avoid Bale message and keyboard limits for a growing history.
        for (int from = 0; from < list.size(); from += 15) {
            List<List<Map<String, Object>>> buttons = new ArrayList<>();
            for (Order order : list.subList(from, Math.min(from + 15, list.size()))) {
                buttons.add(List.of(button(order.customerName() + " — " + (order.legacy() ? "قدیمی" : order.status().title())
                        + " — " + order.orderId(), "orders:view:" + order.orderId())));
            }
            OrderMessages.send(client, actor, "📋 سفارش‌ها؛ برای جزئیات انتخاب کنید:", buttons);
        }
    }
    public void showOrder(String actor, String id) {
        Order order = orders.findForOperator(id, actor);
        List<List<Map<String, Object>>> buttons = new ArrayList<>();
        if (!order.legacy() && order.status() == OrderStatus.CONFIRMED)
            buttons.add(List.of(button("🔧 شروع آماده‌سازی", "orders:prepare:" + id)));
        if (!order.legacy() && order.status() == OrderStatus.PREPARING
                && access.canComplete(access.requireOperator(actor), order.handledByChatId()))
            buttons.add(List.of(button("📦 ثبت تحویل", "orders:deliver:" + id)));
        buttons.add(List.of(button("📜 تاریخچه وضعیت", "orders:history:" + id)));
        OrderMessages.send(client, actor, OrderMessages.details(order), buttons);
    }
    public void showHistory(String actor, String id) {
        StringBuilder text = new StringBuilder("📜 تاریخچه سفارش " + id + "\n\n");
        for (var event : orders.history(id, actor)) {
            text.append(event.description()).append("\n")
                    .append(event.previousStatus() == null ? "—" : event.previousStatus().title()).append(" ← ")
                    .append(event.newStatus().title()).append("\nعامل: ").append(event.changedByName())
                    .append("\n").append(BotFormatting.dateTime(event.changedAt())).append("\n\n");
        }
        OrderMessages.send(client, actor, text.toString(), List.of());
    }
    public void showReports(String actor) { orders.expireInvoices(); OrderMessages.send(client, actor, reports.report(actor), List.of()); }
    public void showHelp(String actor) { access.requireOperator(actor); client.sendMessage(actor, "برای مدیریت سفارش‌ها از منو استفاده کنید."); }
    public void prepare(String actor, String id) { orders.startPreparation(id, actor); showOrder(actor, id); }
    public void deliver(String actor, String id) { orders.deliver(id, actor); showOrder(actor, id); }
}
