package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.*;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.*;
import java.util.*;

@Service
public class OrderService {
    private final OrderStorageService storage;
    private final ProductCatalogService catalog;
    private final ProductPriceService prices;
    private final UserAccessService access;
    private final Clock clock;

    @Autowired
    public OrderService(OrderStorageService storage, ProductCatalogService catalog,
                        ProductPriceService prices, UserAccessService access) {
        this(storage, catalog, prices, access, Clock.systemUTC());
    }
    public OrderService(OrderStorageService storage, ProductCatalogService catalog,
                        ProductPriceService prices, UserAccessService access, Clock clock) {
        this.storage = storage; this.catalog = catalog; this.prices = prices; this.access = access; this.clock = clock;
    }

    public Order createInvoice(String chatId, Map<Integer, Integer> cart) {
        if (cart == null || cart.isEmpty()) throw new IllegalArgumentException("سبد خرید خالی است.");
        Map<Integer, Integer> quantities = Map.copyOf(cart);
        var currentPrices = prices.findAllCurrentPrices();
        return storage.transact(state -> {
            expireWaiting(state);
            // One pending invoice per customer prevents double checkout from reserving twice.
            var existing = state.orders().values().stream().filter(order -> !order.legacy()
                    && chatId.equals(order.chatId()) && order.status() == OrderStatus.WAITING_CUSTOMER).findFirst();
            if (existing.isPresent()) return existing.get();
            Customer customer = state.customers().get(chatId);
            if (customer == null || !customer.complete()) throw new IllegalArgumentException("ابتدا نام و شماره تماس را ثبت کنید.");
            List<OrderItem> items = new ArrayList<>();
            for (var entry : quantities.entrySet()) {
                var product = catalog.findById(entry.getKey()).orElseThrow(() -> new IllegalArgumentException("محصول پیدا نشد."));
                int quantity = entry.getValue();
                if (quantity <= 0) throw new IllegalArgumentException("تعداد باید مثبت باشد.");
                var price = currentPrices.get(product.id());
                if (price == null || price.signum() <= 0) throw new IllegalArgumentException("قیمت معتبر برای «" + product.name() + "» ثبت نشده است.");
                var stock = state.inventory().get(product.id());
                if (stock == null || stock.availableQuantity() < quantity)
                    throw new IllegalArgumentException("موجودی «" + product.name() + "» کافی نیست.");
                items.add(new OrderItem(product.id(), product.name(), quantity, price));
            }
            Instant now = clock.instant();
            Order order = Order.invoice("ORD-" + UUID.randomUUID(), customer, items, now);
            changeInventory(state, order, InventoryOperation.RESERVE);
            state.orders().put(order.orderId(), order);
            addHistory(state, order, null, chatId, customer.name(), "ایجاد پیش‌فاکتور؛ در انتظار ارسال");
            return order;
        });
    }

    public Order markInvoiceSent(String id, String customer) {
        return storage.transact(state -> {
            Order order = owned(state, id, customer);
            if (order.status() != OrderStatus.WAITING_CUSTOMER) throw new IllegalArgumentException("پیش‌فاکتور فعال نیست.");
            if (order.invoiceSentAt() != null) return order;
            Order sent = order.sent(clock.instant());
            state.orders().put(id, sent);
            addHistory(state, sent, order.status(), "SYSTEM", "SYSTEM", "ارسال موفق پیش‌فاکتور؛ اعتبار دو ساعت");
            return sent;
        });
    }

    public void invoiceDeliveryFailed(String id, String customer) {
        storage.transact(state -> {
            Order order = owned(state, id, customer);
            if (order.status() == OrderStatus.WAITING_CUSTOMER && order.invoiceSentAt() == null)
                transition(state, order, OrderStatus.CANCELLED, "SYSTEM", "SYSTEM", "ارسال پیش‌فاکتور ناموفق بود");
            return null;
        });
    }

    public Order confirm(String id, String customer) { return customerDecision(id, customer, OrderStatus.CONFIRMED); }
    public Order cancel(String id, String customer) { return customerDecision(id, customer, OrderStatus.CANCELLED); }

    private Order customerDecision(String id, String customer, OrderStatus target) {
        Order result = storage.transact(state -> {
            Order order = owned(state, id, customer);
            if (order.status() == OrderStatus.WAITING_CUSTOMER && due(order))
                return transition(state, order, OrderStatus.EXPIRED, "SYSTEM", "SYSTEM", "پایان اعتبار پیش‌فاکتور");
            if (order.status() == target) return order;
            if (order.invoiceSentAt() == null) throw new IllegalArgumentException("پیش‌فاکتور هنوز ارسال نشده است.");
            Order updated = transition(state, order, target, customer, order.customerName(),
                    target == OrderStatus.CONFIRMED ? "تأیید مشتری" : "لغو مشتری");
            if (target == OrderStatus.CONFIRMED) {
                for (User user : access.orderNotificationRecipients()) {
                    state.notifications().add(new OrderNotification(id, user.chatId(), false));
                }
            }
            return updated;
        });
        if (result.status() == OrderStatus.EXPIRED) throw new IllegalArgumentException("اعتبار پیش‌فاکتور تمام شده است.");
        return result;
    }

    public Order startPreparation(String id, String actor) {
        User user = access.requireOperator(actor);
        return storage.transact(state -> {
            Order order = requireOrder(state, id);
            if (order.status() == OrderStatus.PREPARING) return order;
            return transition(state, order, OrderStatus.PREPARING, actor, user.name(), "شروع آماده‌سازی");
        });
    }

    public Order deliver(String id, String actor) {
        User user = access.requireOperator(actor);
        return storage.transact(state -> {
            Order order = requireOrder(state, id);
            if (!access.canComplete(user, order.handledByChatId()))
                throw new IllegalArgumentException("فقط مسئول سفارش یا مدیر می‌تواند تحویل را ثبت کند.");
            if (order.status() == OrderStatus.DELIVERED) return order;
            return transition(state, order, OrderStatus.DELIVERED, actor, user.name(), "تحویل سفارش");
        });
    }

    @Scheduled(fixedDelayString = "${tools-market.orders.expiration-check-ms:30000}")
    public void expireInvoices() {
        storage.transact(state -> { expireWaiting(state); return null; });
    }

    private void expireWaiting(OrderStore state) {
        for (Order order : List.copyOf(state.orders().values())) {
            if (order.legacy() || order.status() != OrderStatus.WAITING_CUSTOMER) continue;
            if (due(order)) transition(state, order, OrderStatus.EXPIRED, "SYSTEM", "SYSTEM", "پایان اعتبار پیش‌فاکتور");
            // A process crash during send cannot leave stock reserved indefinitely.
            else if (order.invoiceSentAt() == null && !order.createdAt().plus(Duration.ofMinutes(5)).isAfter(clock.instant()))
                transition(state, order, OrderStatus.CANCELLED, "SYSTEM", "SYSTEM", "ارسال پیش‌فاکتور تکمیل نشد");
        }
    }

    private boolean due(Order order) {
        return order.expiresAt() != null && !order.expiresAt().isAfter(clock.instant());
    }

    public List<Order> list(String actor, Set<OrderStatus> statuses) {
        access.requireOperator(actor);
        expireInvoices();
        return storage.snapshot().orders().values().stream()
                .filter(order -> statuses.isEmpty() || (!order.legacy() && statuses.contains(order.status())))
                .sorted(Comparator.comparing(Order::createdAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }
    public Order findForOperator(String id, String actor) {
        access.requireOperator(actor);
        expireInvoices();
        return requireOrder(storage.snapshot(), id);
    }
    public List<OrderStatusHistory> history(String id, String actor) {
        access.requireOperator(actor);
        return storage.snapshot().history().stream().filter(item -> item.orderId().equals(id))
                .sorted(Comparator.comparing(OrderStatusHistory::changedAt)).toList();
    }
    public List<OrderNotification> pendingNotifications() {
        return storage.snapshot().notifications().stream().filter(item -> !item.sent()).toList();
    }
    public void notificationSent(OrderNotification notification) {
        storage.transact(state -> {
            for (int i = 0; i < state.notifications().size(); i++) {
                OrderNotification item = state.notifications().get(i);
                if (item.orderId().equals(notification.orderId()) && item.recipientChatId().equals(notification.recipientChatId()))
                    state.notifications().set(i, new OrderNotification(item.orderId(), item.recipientChatId(), true));
            }
            return null;
        });
    }

    private Order owned(OrderStore state, String id, String customer) {
        Order order = requireOrder(state, id);
        if (!Objects.equals(customer, order.chatId())) throw new IllegalArgumentException("این پیش‌فاکتور متعلق به شما نیست.");
        if (order.legacy()) throw new IllegalArgumentException("سفارش قدیمی فقط در تاریخچه قابل مشاهده است.");
        return order;
    }
    private Order requireOrder(OrderStore state, String id) {
        Order order = state.orders().get(id);
        if (order == null) throw new IllegalArgumentException("سفارش پیدا نشد.");
        return order;
    }

    private Order transition(OrderStore state, Order order, OrderStatus target, String actor, String name, String description) {
        boolean allowed = !order.legacy() && switch (order.status()) {
            case WAITING_CUSTOMER -> target == OrderStatus.CONFIRMED || target == OrderStatus.CANCELLED || target == OrderStatus.EXPIRED;
            case CONFIRMED -> target == OrderStatus.PREPARING;
            case PREPARING -> target == OrderStatus.DELIVERED;
            default -> false;
        };
        if (!allowed) throw new IllegalArgumentException("تغییر وضعیت سفارش مجاز نیست.");
        if (order.status() == OrderStatus.WAITING_CUSTOMER)
            changeInventory(state, order, target == OrderStatus.CONFIRMED ? InventoryOperation.DEDUCT : InventoryOperation.RELEASE);
        Order updated = order.transition(target, actor, name, clock.instant());
        state.orders().put(order.orderId(), updated);
        addHistory(state, updated, order.status(), actor, name, description);
        return updated;
    }
    private void addHistory(OrderStore state, Order order, OrderStatus previous, String actor, String name, String description) {
        state.history().add(new OrderStatusHistory(UUID.randomUUID().toString(), order.orderId(),
                previous, order.status(), actor, name, clock.instant(), description));
    }
    private enum InventoryOperation { RESERVE, RELEASE, DEDUCT }
    private void changeInventory(OrderStore state, Order order, InventoryOperation operation) {
        for (OrderItem item : order.items()) {
            ProductInventory old = state.inventory().get(item.productId());
            int quantity = item.quantity();
            int total = old.totalQuantity() - (operation == InventoryOperation.DEDUCT ? quantity : 0);
            int reserved = old.reservedQuantity() + (operation == InventoryOperation.RESERVE ? quantity : -quantity);
            state.inventory().put(item.productId(), new ProductInventory(item.productId(), total, reserved, clock.instant()));
        }
    }
}
