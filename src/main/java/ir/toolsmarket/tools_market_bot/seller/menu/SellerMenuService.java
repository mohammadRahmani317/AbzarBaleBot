package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.bot.BotCallback.Action;
import ir.toolsmarket.tools_market_bot.bot.BotButton;
import ir.toolsmarket.tools_market_bot.bot.BotCallback;
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
                List.of(BotButton.ORDERS.text()), List.of(BotButton.MANAGE_PRICES.text()),
                List.of(BotButton.MANAGE_PRODUCTS.text()), List.of(BotButton.MANAGE_INVENTORY.text()), List.of(BotButton.REPORTS.text())), "resize_keyboard", true));
    }
    public void showOrders(String actor) {
        access.requireOperator(actor);
        OrderMessages.send(client, actor, "📋 مدیریت سفارش‌ها", List.of(
                List.of(button(BotButton.ACTIVE_ORDERS.text(), Action.ORDER_LIST.callback(BotCallback.OrderGroup.ACTIVE))),
                List.of(button(BotButton.DELIVERED_ORDERS.text(), Action.ORDER_LIST.callback(BotCallback.OrderGroup.DELIVERED))),
                List.of(button(BotButton.CANCELLED_ORDERS.text(), Action.ORDER_LIST.callback(BotCallback.OrderGroup.CANCELLED))),
                List.of(button(BotButton.EXPIRED_ORDERS.text(), Action.ORDER_LIST.callback(BotCallback.OrderGroup.EXPIRED))),
                List.of(button(BotButton.ALL_ORDERS.text(), Action.ORDER_LIST.callback(BotCallback.OrderGroup.ALL)))));
    }
    public void showOrderList(String actor, String group) {
        showOrderList(actor, BotCallback.OrderGroup.parse(group));
    }
    public void showOrderList(String actor, BotCallback.OrderGroup group) {
        Set<OrderStatus> statuses = group.statuses();
        List<Order> list = orders.list(actor, statuses);
        if (list.isEmpty()) { client.sendMessage(actor, "سفارشی در این فهرست نیست."); return; }
        // Small pages avoid Bale message and keyboard limits for a growing history.
        for (int from = 0; from < list.size(); from += 15) {
            List<List<Map<String, Object>>> buttons = new ArrayList<>();
            for (Order order : list.subList(from, Math.min(from + 15, list.size()))) {
                buttons.add(List.of(button(order.customerName() + " — " + (order.legacy() ? "قدیمی" : order.status().title())
                        + " — " + order.orderId(), Action.ORDER_VIEW.callback(order.orderId()))));
            }
            OrderMessages.send(client, actor, "📋 سفارش‌ها؛ برای جزئیات انتخاب کنید:", buttons);
        }
    }
    public void showOrder(String actor, String id) {
        Order order = orders.findForOperator(id, actor);
        List<List<Map<String, Object>>> buttons = new ArrayList<>();
        if (!order.legacy() && order.status() == OrderStatus.CONFIRMED)
            buttons.add(List.of(button(BotButton.PREPARE.text(), Action.ORDER_PREPARE.callback(id))));
        if (!order.legacy() && order.status() == OrderStatus.PREPARING
                && access.canComplete(access.requireOperator(actor), order.handledByChatId()))
            buttons.add(List.of(button(BotButton.DELIVER.text(), Action.ORDER_DELIVER.callback(id))));
        buttons.add(List.of(button(BotButton.STATUS_HISTORY.text(), Action.ORDER_HISTORY.callback(id))));
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
