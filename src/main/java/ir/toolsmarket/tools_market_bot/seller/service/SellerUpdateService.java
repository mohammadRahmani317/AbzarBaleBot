package ir.toolsmarket.tools_market_bot.seller.service;

import ir.toolsmarket.tools_market_bot.bot.BotButton;
import ir.toolsmarket.tools_market_bot.bot.BotCallback;
import ir.toolsmarket.tools_market_bot.bot.BotCallback.Action;
import ir.toolsmarket.tools_market_bot.config.BaleBotProperties;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.User;
import ir.toolsmarket.tools_market_bot.model.UserRole;
import ir.toolsmarket.tools_market_bot.model.UserStatus;
import ir.toolsmarket.tools_market_bot.seller.menu.SellerAdminMenuService;
import ir.toolsmarket.tools_market_bot.seller.menu.SellerMenuService;
import ir.toolsmarket.tools_market_bot.seller.menu.PriceManagementMenuService;
import ir.toolsmarket.tools_market_bot.model.UserPermission;
import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import ir.toolsmarket.tools_market_bot.service.UserAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class SellerUpdateService {

    private static final Logger log = LoggerFactory.getLogger(SellerUpdateService.class);

    private final BaleBotClient baleBotClient;
    private final SellerMenuService sellerMenuService;
    private final UserAccessService userAccessService;
    private final BaleBotProperties properties;
    private final SellerAdminMenuService sellerAdminMenuService;
    private final PriceManagementMenuService priceManagementMenuService;
    private final Map<String, Integer> awaitingPriceInput = new ConcurrentHashMap<>();

    private final ir.toolsmarket.tools_market_bot.seller.menu.InventoryMenuService inventoryMenu;
    private final Map<String, Integer> awaitingInventoryInput = new ConcurrentHashMap<>();
    private final ir.toolsmarket.tools_market_bot.seller.menu.ProductManagementMenuService productMenu;
    private long nextUpdateId = 0;

    public SellerUpdateService(
            @Qualifier("sellerBaleBotClient") BaleBotClient baleBotClient,
            SellerMenuService sellerMenuService,
            UserAccessService userAccessService,
            BaleBotProperties properties,
            SellerAdminMenuService sellerAdminMenuService,
            PriceManagementMenuService priceManagementMenuService,
            ir.toolsmarket.tools_market_bot.seller.menu.InventoryMenuService inventoryMenu,
            ir.toolsmarket.tools_market_bot.seller.menu.ProductManagementMenuService productMenu
    ) {
        this.baleBotClient = baleBotClient;
        this.sellerMenuService = sellerMenuService;
        this.userAccessService = userAccessService;
        this.properties = properties;
        this.sellerAdminMenuService = sellerAdminMenuService;
        this.priceManagementMenuService = priceManagementMenuService;
        this.inventoryMenu = inventoryMenu;
        this.productMenu = productMenu;
    }

    @Scheduled(fixedDelay = 1000)
    public void receiveUpdates() {
        if (!baleBotClient.configured()) return;
        try {
            JsonNode response = baleBotClient.getUpdates(nextUpdateId);

            if (response == null || !response.path("ok").asBoolean()) {
                log.warn("دریافت آپدیت از بات فروشنده ناموفق بود");
                return;
            }

            for (JsonNode update : response.path("result")) {

                try { processUpdate(update); }
                catch (Exception exception) { log.error("Seller update failed ({})", exception.getClass().getSimpleName()); }
                finally { nextUpdateId = update.path("update_id").asLong() + 1; }
            }

        } catch (Exception exception) {
            log.warn("Seller polling failed ({})", exception.getClass().getSimpleName());
        }
    }

    private void processUpdate(JsonNode update) {

        // بررسی کلیک روی دکمه‌های شیشه‌ای
        JsonNode callbackQuery = update.path("callback_query");

        if (!callbackQuery.isMissingNode()) {
            handleCallbackQuery(callbackQuery);
            return;
        }

        JsonNode message = update.path("message");

        if (message.isMissingNode()) {
            return;
        }

        String chatId = message.path("chat")
                .path("id")
                .asText();

        String text = message.path("text")
                .asText("")
                .trim();

        BotButton option = BotButton.fromText(text);
        if (chatId.isBlank() || text.isBlank()) {
            return;
        }

        if (!chatId.equals(message.path("from").path("id").asText())) return;
        try {
            if (productMenu.handleMessage(chatId, text)) { awaitingPriceInput.remove(chatId); awaitingInventoryInput.remove(chatId); return; }
            if (handleOperationalMessage(chatId, text)) return;
        } catch (IllegalArgumentException exception) {
            baleBotClient.sendMessage(chatId, exception.getMessage()); return;
        }

        Integer pendingProductId = awaitingPriceInput.get(chatId);
        if (pendingProductId != null) {
            if (option == BotButton.CANCEL) {
                awaitingPriceInput.remove(chatId);
                baleBotClient.sendMessage(chatId, "عملیات تغییر قیمت لغو شد.");
                return;
            }
            try {
                BigDecimal price = parseTomanPrice(text);
                if (price.signum() <= 0 || price.stripTrailingZeros().scale() > 0) {
                    throw new NumberFormatException();
                }
                awaitingPriceInput.remove(chatId);
                priceManagementMenuService.savePrice(chatId, pendingProductId, price);
            } catch (NumberFormatException exception) {
                baleBotClient.sendMessage(chatId, "قیمت معتبر نیست. مبلغ را به تومان و به‌صورت عدد صحیح وارد کنید؛ مثال: ۲۵۰۰۰۰۰");
            }
            return;
        }

        if (option == BotButton.MANAGE_PRICES) {
            priceManagementMenuService.showProductsForUpdate(chatId);
            return;
        }

        if (option == BotButton.PRICE_HISTORY) {
            priceManagementMenuService.showProductsForHistory(chatId);
            return;
        }

        // شروع بات
        if (option == BotButton.START) {

            String firstName = message.path("from")
                    .path("first_name")
                    .asText("کاربر");

            handleStart(chatId, firstName);
            return;
        }

        // نمایش سفارش‌های ثبت‌شده
        if (option == BotButton.ORDERS) {

            var userOptional = userAccessService.findByChatId(chatId);

            if (userOptional.isEmpty()
                    || userOptional.get().status() != UserStatus.APPROVED
            ) {

                return;
            }

            sellerMenuService.showOrders(chatId);
            return;
        }

        // نمایش درخواست‌های دسترسی
        if (option == BotButton.ACCESS_REQUESTS) {

            var userOptional = userAccessService.findByChatId(chatId);

            // بررسی وجود کاربر و تأیید دسترسی
            if (userOptional.isEmpty()
                    || userOptional.get().status() != UserStatus.APPROVED) {

                baleBotClient.sendMessage(
                        chatId,
                        "شما اجازه دسترسی به این بخش را ندارید."
                );
                return;
            }

            // فقط مدیر اصلی و مدیر اجازه مشاهده درخواست‌ها را دارند
            var role = userOptional.get().role();

            if (role != ir.toolsmarket.tools_market_bot.model.UserRole.SUPER_ADMIN
                    && role != ir.toolsmarket.tools_market_bot.model.UserRole.ADMIN) {

                baleBotClient.sendMessage(
                        chatId,
                        "شما اجازه دسترسی به این بخش را ندارید."
                );
                return;
            }

            sellerAdminMenuService.showPendingUsers(chatId);
            return;
        }

        if (option == BotButton.MANAGE_USERS) {

            var userOptional = userAccessService.findByChatId(chatId);

            if (userOptional.isEmpty()
                    || userOptional.get().status() != UserStatus.APPROVED) {

                baleBotClient.sendMessage(
                        chatId,
                        "شما اجازه دسترسی به این بخش را ندارید."
                );
                return;
            }

            var role = userOptional.get().role();

            if (role != UserRole.SUPER_ADMIN && role != UserRole.ADMIN) {
                baleBotClient.sendMessage(
                        chatId,
                        "شما اجازه دسترسی به این بخش را ندارید."
                );
                return;
            }

            sellerAdminMenuService.showUsers(chatId);
            return;
        }
    }

    private void handleStart(String chatId, String name) {

        var user = userAccessService.findByChatId(chatId);

        if (user.isEmpty()) {

            User newUser = userAccessService.registerPending(chatId, name);

            // اطلاع‌رسانی درخواست جدید به مدیر اصلی
            notifySuperAdmin(newUser);

            baleBotClient.sendMessage(
                    chatId,
                    """
                            درخواست دسترسی شما با موفقیت ثبت شد.
                            
                            پس از بررسی و تأیید مدیر، امکان استفاده از پنل را خواهید داشت.
                            """
            );

            return;
        }

        var existingUser = user.get();

        if (existingUser.status() == UserStatus.APPROVED) {
            showUserMenu(chatId);
            return;
        }

        if (existingUser.status() == UserStatus.PENDING) {
            baleBotClient.sendMessage(
                    chatId,
                    "درخواست شما هنوز توسط مدیر بررسی نشده است."
            );
            return;
        }

        baleBotClient.sendMessage(
                chatId,
                "درخواست دسترسی شما تأیید نشده است. برای پیگیری با مدیر تماس بگیرید."
        );
    }

    private void notifySuperAdmin(User user) {

        String message = """
                🔔 درخواست عضویت جدید
                
                نام کاربر: %s
                شناسه کاربر: %s
                
                وضعیت: در انتظار بررسی
                """.formatted(
                user.name(),
                user.chatId()
        );

        baleBotClient.sendMessage(
                properties.superAdminChatId(),
                message
        );
    }

    private void showUserMenu(String chatId) {

        var user = userAccessService.findByChatId(chatId);

        if (user.isEmpty()) {
            baleBotClient.sendMessage(
                    chatId,
                    "شما به پنل دسترسی ندارید."
            );
            return;
        }

        if (user.get().status() != UserStatus.APPROVED) {
            baleBotClient.sendMessage(
                    chatId,
                    "دسترسی شما هنوز تأیید نشده است."
            );
            return;
        }

        switch (user.get().role()) {

            case SUPER_ADMIN, ADMIN -> sellerAdminMenuService.showMainMenu(chatId);

            case SELLER -> sellerMenuService.showMainMenu(chatId);
        }
    }


    private void handleCallbackQuery(JsonNode callbackQuery) {

        String callbackQueryId = callbackQuery.path("id").asText();

        String adminChatId = callbackQuery.path("from")
                .path("id")
                .asText();

        try {
            baleBotClient.answerCallbackQuery(callbackQueryId);
        } catch (Exception exception) { log.debug("Callback acknowledgement failed"); }
        if (adminChatId.isBlank() || !adminChatId.equals(callbackQuery.path("message").path("chat").path("id").asText())) return;
        try {
            BotCallback callback = BotCallback.parse(callbackQuery.path("data").asText());
            if (productMenu.handleCallback(adminChatId, callback)) {
                awaitingPriceInput.remove(adminChatId); awaitingInventoryInput.remove(adminChatId); return;
            }
            productMenu.reset(adminChatId);
            if (handleOperationalCallback(adminChatId, callback)) return;
            switch (callback.action()) {
                case PRICE_EDIT -> {
                    if (priceManagementMenuService.requestPriceUpdate(adminChatId, callback.productId())) {
                        awaitingInventoryInput.remove(adminChatId);
                        awaitingPriceInput.put(adminChatId, callback.productId());
                    }
                }
                case PRICE_HISTORY_LIST -> priceManagementMenuService.showProductsForHistory(adminChatId);
                case PRICE_HISTORY -> priceManagementMenuService.showHistory(adminChatId, callback.productId());
                case PRICE_MENU -> showUserMenu(adminChatId);
                default -> handleAccessCallback(adminChatId, callback);
            }
        } catch (IllegalArgumentException exception) { baleBotClient.sendMessage(adminChatId, exception.getMessage()); }
    }

    private void handleAccessCallback(String actor, BotCallback callback) {
        User user = userAccessService.findByChatId(actor).orElse(null);
        if (user == null || user.status() != UserStatus.APPROVED
                || (user.role() != UserRole.ADMIN && user.role() != UserRole.SUPER_ADMIN)) return;
        switch (callback.action()) {
            case ACCESS_REVIEW -> sellerAdminMenuService.showAccessRequestReview(actor, callback.value());
            case ACCESS_LIST -> sellerAdminMenuService.showPendingUsers(actor);
            case ACCESS_APPROVE -> updateAccessRequest(actor, callback.value(), UserStatus.APPROVED);
            case ACCESS_REJECT -> updateAccessRequest(actor, callback.value(), UserStatus.REJECTED);
            case ACCESS_DEACTIVATE -> deactivateSeller(actor, callback.value());
            case ACCESS_REOPEN, ACCESS_PROMOTE, ACCESS_DEMOTE, ACCESS_DEACTIVATE_ADMIN, ACCESS_PRICE_GRANT, ACCESS_PRICE_REVOKE -> {
                if (user.role() != UserRole.SUPER_ADMIN) {
                    baleBotClient.sendMessage(actor, "فقط مدیر اصلی اجازه انجام این عملیات را دارد."); return;
                }
                switch (callback.action()) {
                    case ACCESS_REOPEN -> reopenAccessRequest(actor, callback.value());
                    case ACCESS_PROMOTE -> promoteUser(actor, callback.value());
                    case ACCESS_DEMOTE -> demoteAdmin(actor, callback.value());
                    case ACCESS_DEACTIVATE_ADMIN -> deactivateAdmin(actor, callback.value());
                    case ACCESS_PRICE_GRANT, ACCESS_PRICE_REVOKE -> {
                        boolean enabled = callback.action() == Action.ACCESS_PRICE_GRANT;
                        User target = userAccessService.findByChatId(callback.value()).orElse(null);
                        if (target == null || target.role() != UserRole.SELLER || target.status() != UserStatus.APPROVED)
                            throw new IllegalArgumentException("مجوز قیمت‌گذاری فقط برای فروشنده تأییدشده قابل تغییر است.");
                        userAccessService.setPermission(target.chatId(), UserPermission.PRICE_MANAGER, enabled);
                        baleBotClient.sendMessage(target.chatId(), enabled ? "💰 مجوز مدیریت قیمت محصولات برای شما فعال شد."
                                : "🔒 مجوز مدیریت قیمت محصولات از حساب شما برداشته شد.");
                        baleBotClient.sendMessage(actor, enabled ? "مجوز قیمت‌گذاری برای «" + target.name() + "» فعال شد."
                                : "مجوز قیمت‌گذاری برای «" + target.name() + "» لغو شد.");
                        sellerAdminMenuService.showUsers(actor);
                    }
                    default -> { }
                }
            }
            default -> throw new IllegalArgumentException("درخواست نامعتبر است.");
        }
    }

    private void updateAccessRequest(
            String adminChatId,
            String targetChatId,
            UserStatus newStatus
    ) {

        var targetOptional = userAccessService.findByChatId(targetChatId);

        if (targetOptional.isEmpty()
                || targetOptional.get().status() != UserStatus.PENDING) {

            baleBotClient.sendMessage(
                    adminChatId,
                    "این درخواست وجود ندارد یا قبلاً بررسی شده است."
            );

            sellerAdminMenuService.showPendingUsers(adminChatId);
            return;
        }

        var targetUser = targetOptional.get();

        // جلوگیری از تغییر وضعیت مدیر اصلی
        if (targetUser.role() == ir.toolsmarket.tools_market_bot.model.UserRole.SUPER_ADMIN) {
            baleBotClient.sendMessage(
                    adminChatId,
                    "امکان تغییر وضعیت مدیر اصلی وجود ندارد."
            );
            return;
        }

        userAccessService.updateStatus(targetChatId, newStatus);

        if (newStatus == UserStatus.APPROVED) {

            baleBotClient.sendMessage(
                    targetChatId,
                    """
                            ✅ درخواست دسترسی شما تأیید شد.
    
                            اکنون می‌توانید از پنل ابزارمارکت استفاده کنید.
                            برای ورود دستور /start را ارسال کنید.
                            """
            );

            baleBotClient.sendMessage(
                    adminChatId,
                    "درخواست کاربر " + targetUser.name() + " با موفقیت تأیید شد."
            );

        } else {

            baleBotClient.sendMessage(
                    targetChatId,
                    """
                            ❌ درخواست دسترسی شما تأیید نشد.
    
                            برای پیگیری با مدیر ابزارمارکت تماس بگیرید.
                            """
            );

            baleBotClient.sendMessage(
                    adminChatId,
                    "درخواست کاربر " + targetUser.name() + " رد شد."
            );
        }

        // نمایش مجدد درخواست‌های باقی‌مانده
        sellerAdminMenuService.showPendingUsers(adminChatId);
    }

    private void reopenAccessRequest(
            String adminChatId,
            String targetChatId
    ) {

        var targetOptional = userAccessService.findByChatId(targetChatId);

        if (targetOptional.isEmpty()
                || targetOptional.get().status() != UserStatus.REJECTED) {

            baleBotClient.sendMessage(
                    adminChatId,
                    "این کاربر وجود ندارد یا درخواست او در وضعیت ردشده نیست."
            );
            return;
        }

        var targetUser = targetOptional.get();

        // تغییر وضعیت از ردشده به در انتظار بررسی
        userAccessService.updateStatus(
                targetChatId,
                UserStatus.PENDING
        );

        baleBotClient.sendMessage(
                targetChatId,
                """
                        📋 درخواست دسترسی شما مجدداً برای بررسی ارسال شد.
    
                        لطفاً منتظر بررسی مدیر ابزارمارکت باشید.
                        """
        );

        baleBotClient.sendMessage(
                adminChatId,
                "درخواست دسترسی «" + targetUser.name()
                        + "» مجدداً در انتظار بررسی قرار گرفت."
        );

        // نمایش مجدد فهرست کاربران
        sellerAdminMenuService.showUsers(adminChatId);

    }

    // ارتقای فروشنده به مدیر
    private void promoteUser(String adminChatId, String targetChatId) {

        var targetOptional = userAccessService.findByChatId(targetChatId);

        if (targetOptional.isEmpty()) {
            baleBotClient.sendMessage(
                    adminChatId,
                    "کاربر موردنظر پیدا نشد."
            );
            return;
        }

        var targetUser = targetOptional.get();

        // جلوگیری از تغییر نقش مدیر اصلی
        if (targetUser.role() == UserRole.SUPER_ADMIN) {
            baleBotClient.sendMessage(
                    adminChatId,
                    "امکان تغییر نقش مدیر اصلی وجود ندارد."
            );
            return;
        }

        // فقط فروشنده تأییدشده قابلیت ارتقا دارد
        if (targetUser.status() != UserStatus.APPROVED
                || targetUser.role() != UserRole.SELLER) {

            baleBotClient.sendMessage(
                    adminChatId,
                    "فقط فروشنده‌های تأییدشده را می‌توان به مدیر ارتقا داد."
            );
            return;
        }

        userAccessService.updateRole(targetChatId, UserRole.ADMIN);

        baleBotClient.sendMessage(
                targetChatId,
                """
                        🎉 نقش کاربری شما ارتقا پیدا کرد.
    
                        نقش جدید شما: مدیر
    
                        برای ورود به پنل مدیریت، دستور /start را ارسال کنید.
                        """
        );

        baleBotClient.sendMessage(
                adminChatId,
                "نقش کاربر «" + targetUser.name()
                        + "» با موفقیت به مدیر تغییر کرد."
        );

        // به‌روزرسانی فهرست کاربران
        sellerAdminMenuService.showUsers(adminChatId);
    }

    private void deactivateSeller(
            String adminChatId,
            String targetChatId
    ) {

        var adminOptional = userAccessService.findByChatId(adminChatId);

        if (adminOptional.isEmpty()) {
            return;
        }

        var admin = adminOptional.get();

        // بررسی دسترسی مدیر
        if (admin.status() != UserStatus.APPROVED
                || (admin.role() != UserRole.SUPER_ADMIN
                && admin.role() != UserRole.ADMIN)) {

            baleBotClient.sendMessage(
                    adminChatId,
                    "شما اجازه انجام این عملیات را ندارید."
            );
            return;
        }

        var targetOptional = userAccessService.findByChatId(targetChatId);

        if (targetOptional.isEmpty()) {
            baleBotClient.sendMessage(
                    adminChatId,
                    "کاربر موردنظر پیدا نشد."
            );
            return;
        }

        var target = targetOptional.get();

        // مدیر فقط اجازه غیرفعال‌کردن فروشنده را دارد
        if (target.role() != UserRole.SELLER
                || target.status() != UserStatus.APPROVED) {

            baleBotClient.sendMessage(
                    adminChatId,
                    "فقط فروشنده‌های فعال را می‌توانید غیرفعال کنید."
            );
            return;
        }

        userAccessService.updateStatus(
                targetChatId,
                UserStatus.REJECTED
        );

        baleBotClient.sendMessage(
                targetChatId,
                """
                        🔴 دسترسی شما به پنل ابزارمارکت غیرفعال شد.
    
                        برای پیگیری با مدیر ابزارمارکت تماس بگیرید.
                        """
        );

        baleBotClient.sendMessage(
                adminChatId,
                "فروشنده «" + target.name() + "» با موفقیت غیرفعال شد."
        );

        sellerAdminMenuService.showUsers(adminChatId);
    }




    private void demoteAdmin(String adminChatId, String targetChatId) {

        var adminOptional = userAccessService.findByChatId(adminChatId);

        if (adminOptional.isEmpty()
                || adminOptional.get().status() != UserStatus.APPROVED
                || adminOptional.get().role() != UserRole.SUPER_ADMIN) {

            baleBotClient.sendMessage(
                    adminChatId,
                    "فقط مدیر اصلی اجازه تغییر نقش مدیران را دارد."
            );
            return;
        }

        // جلوگیری از تغییر حساب خود مدیر اصلی
        if (adminChatId.equals(targetChatId)) {
            baleBotClient.sendMessage(
                    adminChatId,
                    "امکان تغییر نقش حساب مدیر اصلی وجود ندارد."
            );
            return;
        }

        var targetOptional = userAccessService.findByChatId(targetChatId);

        if (targetOptional.isEmpty()) {
            baleBotClient.sendMessage(
                    adminChatId,
                    "کاربر موردنظر پیدا نشد."
            );
            return;
        }

        var target = targetOptional.get();

        // فقط مدیر تأییدشده قابلیت تنزل دارد
        if (target.role() != UserRole.ADMIN
                || target.status() != UserStatus.APPROVED) {

            baleBotClient.sendMessage(
                    adminChatId,
                    "فقط مدیران فعال را می‌توانید به فروشنده تنزل دهید."
            );
            return;
        }

        userAccessService.updateRole(targetChatId, UserRole.SELLER);

        baleBotClient.sendMessage(
                targetChatId,
                """
                        📢 نقش کاربری شما تغییر کرد.
    
                        نقش جدید شما: فروشنده
    
                        برای ورود به پنل فروشنده، دستور /start را ارسال کنید.
                        """
        );

        baleBotClient.sendMessage(
                adminChatId,
                "نقش مدیر «" + target.name()
                        + "» با موفقیت به فروشنده تغییر کرد."
        );

        sellerAdminMenuService.showUsers(adminChatId);
    }


    private void deactivateAdmin(String adminChatId, String targetChatId) {

        var adminOptional = userAccessService.findByChatId(adminChatId);

        if (adminOptional.isEmpty()
                || adminOptional.get().status() != UserStatus.APPROVED
                || adminOptional.get().role() != UserRole.SUPER_ADMIN) {

            baleBotClient.sendMessage(
                    adminChatId,
                    "فقط مدیر اصلی اجازه غیرفعال‌کردن مدیران را دارد."
            );
            return;
        }

        // جلوگیری از غیرفعال‌کردن حساب خود مدیر اصلی
        if (adminChatId.equals(targetChatId)) {
            baleBotClient.sendMessage(
                    adminChatId,
                    "امکان غیرفعال‌کردن حساب مدیر اصلی وجود ندارد."
            );
            return;
        }

        var targetOptional = userAccessService.findByChatId(targetChatId);

        if (targetOptional.isEmpty()) {
            baleBotClient.sendMessage(
                    adminChatId,
                    "کاربر موردنظر پیدا نشد."
            );
            return;
        }

        var target = targetOptional.get();

        // فقط مدیر تأییدشده قابلیت غیرفعال‌شدن دارد
        if (target.role() != UserRole.ADMIN
                || target.status() != UserStatus.APPROVED) {

            baleBotClient.sendMessage(
                    adminChatId,
                    "فقط مدیران فعال را می‌توانید غیرفعال کنید."
            );
            return;
        }

        userAccessService.updateStatus(
                targetChatId,
                UserStatus.REJECTED
        );

        baleBotClient.sendMessage(
                targetChatId,
                """
                        🔴 دسترسی شما به پنل مدیریت ابزارمارکت غیرفعال شد.
    
                        برای پیگیری با مدیر اصلی ابزارمارکت تماس بگیرید.
                        """
        );

        baleBotClient.sendMessage(
                adminChatId,
                "دسترسی مدیر «" + target.name()
                        + "» با موفقیت غیرفعال شد."
        );

        sellerAdminMenuService.showUsers(adminChatId);
    }


    private BigDecimal parseTomanPrice(String rawValue) {
        String normalized = ir.toolsmarket.tools_market_bot.util.BotFormatting.digits(rawValue)
                .replace("٬", "").replace(",", "").replace("،", "").replace(" ", "");
        if (!normalized.matches("\\d+")) {
            throw new NumberFormatException("قیمت باید عدد صحیح باشد");
        }
        return new BigDecimal(normalized);
    }


    private boolean handleOperationalMessage(String actor, String text) {
        BotButton option = BotButton.fromText(text);
        if (option == BotButton.START) {
            awaitingInventoryInput.remove(actor); awaitingPriceInput.remove(actor); return false;
        }
        Integer id = awaitingInventoryInput.get(actor);
        if (id != null) {
            if (option == BotButton.CANCEL) { awaitingInventoryInput.remove(actor); showUserMenu(actor); return true; }
            inventoryMenu.save(actor, id, text);
            awaitingInventoryInput.remove(actor);
            return true;
        }
        if (option == BotButton.MANAGE_INVENTORY) { inventoryMenu.showProducts(actor); return true; }
        if (option == BotButton.REPORTS) { sellerMenuService.showReports(actor); return true; }
        return false;
    }

    private boolean handleOperationalCallback(String actor, BotCallback callback) {
        switch (callback.action()) {
            case INVENTORY_EDIT -> {
                inventoryMenu.requestQuantity(actor, callback.productId());
                awaitingPriceInput.remove(actor);
                awaitingInventoryInput.put(actor, callback.productId());
            }
            case ORDER_LIST -> sellerMenuService.showOrderList(actor, callback.group());
            case ORDER_VIEW -> sellerMenuService.showOrder(actor, callback.value());
            case ORDER_PREPARE -> sellerMenuService.prepare(actor, callback.value());
            case ORDER_DELIVER -> sellerMenuService.deliver(actor, callback.value());
            case ORDER_HISTORY -> sellerMenuService.showHistory(actor, callback.value());
            default -> { return false; }
        }
        return true;
    }
}
