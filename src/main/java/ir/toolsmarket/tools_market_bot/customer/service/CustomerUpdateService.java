package ir.toolsmarket.tools_market_bot.customer.service;

import ir.toolsmarket.tools_market_bot.bot.BotButton;
import ir.toolsmarket.tools_market_bot.bot.BotCallback;
import ir.toolsmarket.tools_market_bot.bot.BotCallback.Action;
import ir.toolsmarket.tools_market_bot.customer.menu.*;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.*;
import ir.toolsmarket.tools_market_bot.service.*;
import ir.toolsmarket.tools_market_bot.util.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static ir.toolsmarket.tools_market_bot.util.OrderMessages.button;

@Service
public class CustomerUpdateService {
    private static final Logger log = LoggerFactory.getLogger(CustomerUpdateService.class);
    private final BaleBotClient client;
    private final ProductCatalogService catalog;
    private final BotMenuService menu;
    private final OrderSessionService sessions;
    private final ShoppingCartService carts;
    private final CustomerService customers;
    private final OrderService orders;
    private enum ProfileInput {
        EDIT_NAME(true, false), EDIT_PHONE(false, false), CHECKOUT_NAME(true, true), CHECKOUT_PHONE(false, true);
        final boolean name, checkout;
        ProfileInput(boolean name, boolean checkout) { this.name = name; this.checkout = checkout; }
    }
    private final Map<String, ProfileInput> profileInput = new ConcurrentHashMap<>();
    private long nextUpdateId;

    public CustomerUpdateService(@Qualifier("customerBaleBotClient") BaleBotClient client,
            ProductCatalogService catalog, BotMenuService menu, OrderSessionService sessions,
            ShoppingCartService carts, CustomerService customers, OrderService orders) {
        this.client = client; this.catalog = catalog; this.menu = menu; this.sessions = sessions;
        this.carts = carts; this.customers = customers; this.orders = orders;
    }

    @Scheduled(fixedDelay = 1000)
    public void receiveUpdates() {
        if (!client.configured()) return;
        try {
            JsonNode response = client.getUpdates(nextUpdateId);
            if (response == null || !response.path("ok").asBoolean()) return;
            for (JsonNode update : response.path("result")) {
                try { processUpdate(update); }
                catch (Exception exception) { log.error("Customer update failed ({})", exception.getClass().getSimpleName()); }
                finally { nextUpdateId = update.path("update_id").asLong() + 1; }
            }
        } catch (Exception exception) { log.warn("Customer polling failed ({})", exception.getClass().getSimpleName()); }
    }

    // Both bots use private conversations: never use a group ID as the callback actor.
    synchronized void processUpdate(JsonNode update) {
        JsonNode callback = update.path("callback_query");
        JsonNode message = callback.isMissingNode() ? update.path("message") : callback.path("message");
        String chatId = message.path("chat").path("id").asText();
        String actor = (callback.isMissingNode() ? message : callback).path("from").path("id").asText();
        if (chatId.isBlank() || !chatId.equals(actor)) return;
        try {
            if (!callback.isMissingNode()) {
                try { client.answerCallbackQuery(callback.path("id").asText()); }
                catch (Exception exception) { log.debug("Callback acknowledgement failed"); }
                handleCallback(chatId, callback.path("data").asText());
            } else handleMessage(chatId, message.path("text").asText("").trim());
        } catch (IllegalArgumentException exception) { client.sendMessage(chatId, exception.getMessage()); }
    }

    private void handleMessage(String chatId, String text) {
        if (text.isBlank()) return;
        BotButton option = BotButton.fromText(text);
        if (option == BotButton.START || option == BotButton.CANCEL) {
            sessions.removeOrder(chatId); profileInput.remove(chatId); menu.showMainMenu(chatId); return;
        }
        if (option == BotButton.CART || option == BotButton.MY_ORDERS || option == BotButton.PROFILE) {
            sessions.removeOrder(chatId); profileInput.remove(chatId);
            switch (option) {
                case CART -> showCart(chatId);
                case PROFILE -> showProfile(chatId);
                case MY_ORDERS -> showMyOrders(chatId);
                default -> { }
            }
            return;
        }
        if (profileInput.containsKey(chatId)) { handleProfileInput(chatId, text); return; }
        OrderState selection = sessions.getOrder(chatId);
        if (selection != null) {
            carts.setQuantity(chatId, selection.getProductId(), BotFormatting.quantity(text));
            sessions.removeOrder(chatId); showCart(chatId); return;
        }
        if (option != null) {
            switch (option) {
                case VIEW_PRODUCTS, CREATE_ORDER, BACK_TO_CATEGORIES -> menu.showCategoriesMenu(chatId);
                case CONTACT_SELLER -> menu.showContactInfo(chatId);
                case BACK_TO_MAIN, BACK_TO_MAIN_FROM_CATEGORY -> menu.showMainMenu(chatId);
                default -> client.sendMessage(chatId, "لطفاً از منو استفاده کنید.");
            }
            return;
        }
        ProductCategory.fromTitle(text).ifPresentOrElse(category -> showProducts(chatId, category),
                () -> client.sendMessage(chatId, "لطفاً از منو استفاده کنید. برای انصراف «لغو» را ارسال کنید."));
    }

    private void handleCallback(String chatId, String data) {
        BotCallback callback = BotCallback.parse(data);
        switch (callback.action()) {
            case CUSTOMER_ORDER_VIEW -> OrderMessages.send(client, chatId,
                    OrderMessages.details(orders.findForCustomer(callback.value(), chatId)), List.of());
            case PRODUCT_DETAIL -> menu.showProductDetail(chatId, activeProduct(callback.productId()));
            case PRODUCT_ORDER -> {
                Product product = activeProduct(callback.productId());
                sessions.startOrder(chatId, product.id()); profileInput.remove(chatId);
                client.sendMessage(chatId, "تعداد «" + product.name() + "» را وارد کنید. تعداد جدید جایگزین تعداد قبلی سبد می‌شود. برای انصراف «لغو» را بفرستید.");
            }
            case INVOICE_CONFIRM, INVOICE_CANCEL -> {
                Order result = callback.action() == Action.INVOICE_CONFIRM
                        ? orders.confirm(callback.value(), chatId) : orders.cancel(callback.value(), chatId);
                client.sendMessage(chatId, "سفارش " + result.orderId() + "\nوضعیت: " + result.status().title());
            }
            case LEGACY_CONFIRM, LEGACY_CANCEL -> client.sendMessage(chatId, "این دکمه قدیمی است. از دکمه پیش‌فاکتور جدید استفاده کنید.");
            case CATEGORY -> showProducts(chatId, callback.category());
            case NAV_CATEGORIES, CART_ADD -> menu.showCategoriesMenu(chatId);
            case NAV_MAIN -> menu.showMainMenu(chatId);
            case CART_VIEW -> showCart(chatId);
            case CART_REMOVE -> { carts.remove(chatId, callback.productId()); showCart(chatId); }
            case CART_CHECKOUT -> { sessions.removeOrder(chatId); profileInput.remove(chatId); checkout(chatId); }
            case PROFILE_NAME, PROFILE_PHONE -> {
                sessions.removeOrder(chatId);
                boolean name = callback.action() == Action.PROFILE_NAME;
                profileInput.put(chatId, name ? ProfileInput.EDIT_NAME : ProfileInput.EDIT_PHONE);
                client.sendMessage(chatId, name ? "نام و نام خانوادگی را وارد کنید:" : "شماره موبایل را وارد کنید:");
            }
            default -> throw new IllegalArgumentException("درخواست نامعتبر است.");
        }
    }
    private Product activeProduct(int id) {
        return catalog.findById(id).orElseThrow(() -> new IllegalArgumentException("محصول پیدا نشد."));
    }

    private void showMyOrders(String chatId) {
        List<Order> history = orders.listForCustomer(chatId);
        if (history.isEmpty()) { client.sendMessage(chatId, "هنوز سفارشی ثبت نکرده‌اید."); return; }
        for (int from = 0; from < history.size(); from += 15) {
            List<List<Map<String, Object>>> buttons = new ArrayList<>();
            for (Order order : history.subList(from, Math.min(from + 15, history.size())))
                buttons.add(List.of(button(order.orderId() + " — " + OrderMessages.statusTitle(order), Action.CUSTOMER_ORDER_VIEW.callback(order.orderId()))));
            OrderMessages.send(client, chatId, "📦 سفارش‌های قبلی من؛ از جدیدترین به قدیمی‌ترین. برای جزئیات انتخاب کنید:", buttons);
        }
    }

    private void showProducts(String chatId, ProductCategory category) {
        menu.showProducts(chatId, category, catalog.findByCategory(category));
    }
    private void showCart(String chatId) {
        var items = carts.items(chatId);
        List<List<Map<String, Object>>> buttons = new ArrayList<>();
        for (OrderItem item : items) buttons.add(List.of(
                button(BotButton.EDIT_QUANTITY_PREFIX.text() + item.productName(), Action.PRODUCT_ORDER.callback(item.productId())),
                button(BotButton.REMOVE.text(), Action.CART_REMOVE.callback(item.productId()))));
        buttons.add(List.of(button(BotButton.ADD_PRODUCT.text(), Action.CART_ADD.callback()), button(BotButton.CHECKOUT.text(), Action.CART_CHECKOUT.callback())));
        OrderMessages.send(client, chatId, "🛒 سبد خرید\nقیمت‌های فعلی؛ قیمت نهایی هنگام صدور فاکتور ثبت می‌شود.\n\n"
                + (items.isEmpty() ? "سبد خرید خالی است." : OrderMessages.items(items)
                + "جمع اقلام دارای قیمت: " + BotFormatting.price(carts.total(chatId))), buttons);
    }
    private void showProfile(String chatId) {
        sessions.removeOrder(chatId);
        profileInput.remove(chatId);
        var profile = customers.find(chatId).orElse(null);
        OrderMessages.send(client, chatId, "👤 اطلاعات من\nنام: " + (profile == null ? "—" : profile.name())
                + "\nتلفن: " + (profile == null ? "—" : profile.phoneNumber()),
                List.of(List.of(button(BotButton.EDIT_NAME.text(), Action.PROFILE_NAME.callback()), button(BotButton.EDIT_PHONE.text(), Action.PROFILE_PHONE.callback()))));
    }
    private void handleProfileInput(String chatId, String text) {
        ProfileInput mode = profileInput.get(chatId);
        boolean name = mode.name;
        customers.update(chatId, name ? text : null, name ? null : text);
        profileInput.remove(chatId);
        if (mode.checkout) checkout(chatId); else showProfile(chatId);
    }
    private void checkout(String chatId) {
        if (carts.quantities(chatId).isEmpty()) throw new IllegalArgumentException("سبد خرید خالی است.");
        Customer profile = customers.find(chatId).orElse(null);
        if (profile == null || profile.name() == null || profile.name().isBlank()) {
            profileInput.put(chatId, ProfileInput.CHECKOUT_NAME); client.sendMessage(chatId, "نام و نام خانوادگی را وارد کنید:"); return;
        }
        if (profile.phoneNumber() == null || profile.phoneNumber().isBlank()) {
            profileInput.put(chatId, ProfileInput.CHECKOUT_PHONE); client.sendMessage(chatId, "شماره موبایل را وارد کنید:"); return;
        }
        Order invoice = orders.createInvoice(chatId, carts.quantities(chatId));
        if (invoice.invoiceSentAt() != null) {
            client.sendMessage(chatId, "یک پیش‌فاکتور فعال دارید. از دکمه‌های همان پیش‌فاکتور استفاده کنید.\nشماره: " + invoice.orderId());
            return;
        }
        try {
            OrderMessages.send(client, chatId, "🧾 پیش‌فاکتور ابزارمارکت\nشماره: " + invoice.orderId()
                    + "\nمشتری: " + invoice.customerName() + "\n\n" + OrderMessages.items(invoice.items())
                    + "\nمبلغ نهایی: " + BotFormatting.price(invoice.totalAmount())
                    + "\nاعتبار: ۲ ساعت پس از ارسال موفق این پیام",
                    List.of(List.of(button(BotButton.CONFIRM_ORDER.text(), Action.INVOICE_CONFIRM.callback(invoice.orderId())),
                            button(BotButton.CANCEL_ORDER.text(), Action.INVOICE_CANCEL.callback(invoice.orderId())))));
            orders.markInvoiceSent(invoice.orderId(), chatId);
            carts.clear(chatId);
        } catch (Exception exception) {
            orders.invoiceDeliveryFailed(invoice.orderId(), chatId);
            log.warn("Invoice delivery did not complete: {}", invoice.orderId());
            client.sendMessage(chatId, "ارسال پیش‌فاکتور تکمیل نشد. دوباره از سبد خرید اقدام کنید.");
        }
    }
}
