package ir.toolsmarket.tools_market_bot.seller.service;

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
    private long nextUpdateId = 0;

    public SellerUpdateService(
            @Qualifier("sellerBaleBotClient") BaleBotClient baleBotClient,
            SellerMenuService sellerMenuService,
            UserAccessService userAccessService,
            BaleBotProperties properties,
            SellerAdminMenuService sellerAdminMenuService,
            PriceManagementMenuService priceManagementMenuService,
            ir.toolsmarket.tools_market_bot.seller.menu.InventoryMenuService inventoryMenu
    ) {
        this.baleBotClient = baleBotClient;
        this.sellerMenuService = sellerMenuService;
        this.userAccessService = userAccessService;
        this.properties = properties;
        this.sellerAdminMenuService = sellerAdminMenuService;
        this.priceManagementMenuService = priceManagementMenuService;
        this.inventoryMenu = inventoryMenu;
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

        if (chatId.isBlank() || text.isBlank()) {
            return;
        }

        if (!chatId.equals(message.path("from").path("id").asText())) return;
        try {
            if (handleOperationalMessage(chatId, text)) return;
        } catch (IllegalArgumentException exception) {
            baleBotClient.sendMessage(chatId, exception.getMessage()); return;
        }

        Integer pendingProductId = awaitingPriceInput.get(chatId);
        if (pendingProductId != null) {
            if ("لغو".equals(text)) {
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

        if ("💰 مدیریت قیمت محصولات".equals(text)) {
            priceManagementMenuService.showProductsForUpdate(chatId);
            return;
        }

        if ("📊 تاریخچه قیمت محصولات".equals(text)) {
            priceManagementMenuService.showProductsForHistory(chatId);
            return;
        }

        // شروع بات
        if ("/start".equals(text)) {

            String firstName = message.path("from")
                    .path("first_name")
                    .asText("کاربر");

            handleStart(chatId, firstName);
            return;
        }

        // نمایش سفارش‌های ثبت‌شده
        if ("📦 مشاهده سفارش‌ها".equals(text)) {

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
        if ("👥 درخواست‌های دسترسی".equals(text)) {

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

        if ("👤 مدیریت کاربران".equals(text)) {

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

        String callbackData = callbackQuery.path("data").asText();

        try {
            baleBotClient.answerCallbackQuery(callbackQueryId);
        } catch (Exception e) {
            log.warn("پاسخ به درخواست دکمه بله ناموفق بود ({})", e.getClass().getSimpleName());
        }

        if (!adminChatId.equals(callbackQuery.path("message").path("chat").path("id").asText())) return;
        try {
            if (handleOperationalCallback(adminChatId, callbackData)) return;
        } catch (IllegalArgumentException exception) {
            baleBotClient.sendMessage(adminChatId, exception.getMessage()); return;
        }

        // عملیات قیمت‌گذاری برای تمام نقش‌های عملیاتی فعال است
        if (callbackData.startsWith("price:edit:")) {
            String productIdText = callbackData.substring("price:edit:".length());
            try {
                Integer productId = Integer.parseInt(productIdText);
                if (priceManagementMenuService.requestPriceUpdate(adminChatId, productId)) {
                    awaitingInventoryInput.remove(adminChatId);
                    awaitingPriceInput.put(adminChatId, productId);
                }
            } catch (NumberFormatException exception) {
                baleBotClient.sendMessage(adminChatId, "شناسه محصول نامعتبر است.");
            }
            return;
        }

        if ("price:history:list".equals(callbackData)) {
            priceManagementMenuService.showProductsForHistory(adminChatId);
            return;
        }

        if (callbackData.startsWith("price:history:")) {
            String productIdText = callbackData.substring("price:history:".length());
            try {
                priceManagementMenuService.showHistory(adminChatId, Integer.parseInt(productIdText));
            } catch (NumberFormatException exception) {
                baleBotClient.sendMessage(adminChatId, "شناسه محصول نامعتبر است.");
            }
            return;
        }

        if ("price:menu".equals(callbackData)) {
            showUserMenu(adminChatId);
            return;
        }

        // بررسی دسترسی مدیر
        var adminOptional = userAccessService.findByChatId(adminChatId);

        if (adminOptional.isEmpty()
                || adminOptional.get().status() != UserStatus.APPROVED) {
            return;
        }

        var role = adminOptional.get().role();

        if (role != UserRole.SUPER_ADMIN
                && role != UserRole.ADMIN) {
            return;
        }

        if (callbackData.startsWith("access:price-grant:")
                || callbackData.startsWith("access:price-revoke:")) {
            if (role != UserRole.SUPER_ADMIN) {
                baleBotClient.sendMessage(adminChatId, "فقط مدیر اصلی اجازه تغییر مجوز قیمت‌گذاری را دارد.");
                return;
            }
            boolean enabled = callbackData.startsWith("access:price-grant:");
            String prefix = enabled ? "access:price-grant:" : "access:price-revoke:";
            String targetChatId = callbackData.substring(prefix.length());
            var target = userAccessService.findByChatId(targetChatId).orElse(null);
            if (target == null || target.role() != UserRole.SELLER
                    || target.status() != UserStatus.APPROVED) {
                baleBotClient.sendMessage(adminChatId, "مجوز قیمت‌گذاری فقط برای فروشنده تأییدشده قابل تغییر است.");
                return;
            }
            userAccessService.setPermission(targetChatId, UserPermission.PRICE_MANAGER, enabled);
            baleBotClient.sendMessage(targetChatId, enabled
                    ? "💰 مجوز مدیریت قیمت محصولات برای شما فعال شد."
                    : "🔒 مجوز مدیریت قیمت محصولات از حساب شما برداشته شد.");
            baleBotClient.sendMessage(adminChatId, enabled
                    ? "مجوز قیمت‌گذاری برای «" + target.name() + "» فعال شد."
                    : "مجوز قیمت‌گذاری برای «" + target.name() + "» لغو شد.");
            sellerAdminMenuService.showUsers(adminChatId);
            return;
        }

        // بررسی جزئیات درخواست
        if (callbackData.startsWith("access:review:")) {

            String targetChatId = callbackData.substring("access:review:".length());

            sellerAdminMenuService.showAccessRequestReview(
                    adminChatId,
                    targetChatId
            );

            return;
        }

        // بازگشت به فهرست درخواست‌ها
        if ("access:list".equals(callbackData)) {
            sellerAdminMenuService.showPendingUsers(adminChatId);
            return;
        }

        // بازگرداندن درخواست ردشده به حالت انتظار بررسی
        if (callbackData.startsWith("access:reopen:")) {

            if (role != UserRole.SUPER_ADMIN) {
                baleBotClient.sendMessage(
                        adminChatId,
                        "فقط مدیر اصلی اجازه بررسی مجدد درخواست‌ها را دارد."
                );
                return;
            }

            String targetChatId =
                    callbackData.substring("access:reopen:".length());

            reopenAccessRequest(adminChatId, targetChatId);
            return;
        }

        // ارتقای فروشنده به مدیر
        if (callbackData.startsWith("access:promote:")) {

            if (role != UserRole.SUPER_ADMIN) {
                baleBotClient.sendMessage(
                        adminChatId,
                        "فقط مدیر اصلی اجازه ارتقای نقش کاربران را دارد."
                );
                return;
            }

            String targetChatId =
                    callbackData.substring("access:promote:".length());

            promoteUser(adminChatId, targetChatId);
            return;
        }

        // تنزل مدیر به فروشنده
        if (callbackData.startsWith("access:demote:")) {

            if (role != UserRole.SUPER_ADMIN) {
                baleBotClient.sendMessage(
                        adminChatId,
                        "فقط مدیر اصلی اجازه تغییر نقش مدیران را دارد."
                );
                return;
            }

            String targetChatId =
                    callbackData.substring("access:demote:".length());

            demoteAdmin(adminChatId, targetChatId);
            return;
        }

        // غیرفعال‌کردن فروشنده
        if (callbackData.startsWith("access:deactivate:")) {

            String targetChatId =
                    callbackData.substring("access:deactivate:".length());

            deactivateSeller(adminChatId, targetChatId);
            return;
        }

        // غیرفعال‌کردن مدیر
        if (callbackData.startsWith("access:deactivate-admin:")) {

            if (role != UserRole.SUPER_ADMIN) {
                baleBotClient.sendMessage(
                        adminChatId,
                        "فقط مدیر اصلی اجازه غیرفعال‌کردن مدیران را دارد."
                );
                return;
            }

            String targetChatId =
                    callbackData.substring("access:deactivate-admin:".length());

            deactivateAdmin(adminChatId, targetChatId);
            return;
        }

        // تأیید درخواست
        if (callbackData.startsWith("access:approve:")) {

            String targetChatId =
                    callbackData.substring("access:approve:".length());

            updateAccessRequest(
                    adminChatId,
                    targetChatId,
                    UserStatus.APPROVED
            );

            return;
        }

        // رد درخواست
        if (callbackData.startsWith("access:reject:")) {

            String targetChatId =
                    callbackData.substring("access:reject:".length());

            updateAccessRequest(
                    adminChatId,
                    targetChatId,
                    UserStatus.REJECTED
            );
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
        if ("/start".equals(text)) {
            awaitingInventoryInput.remove(actor); awaitingPriceInput.remove(actor); return false;
        }
        Integer id = awaitingInventoryInput.get(actor);
        if (id != null) {
            if ("لغو".equals(text)) { awaitingInventoryInput.remove(actor); showUserMenu(actor); return true; }
            inventoryMenu.save(actor, id, text);
            awaitingInventoryInput.remove(actor);
            return true;
        }
        if ("📦 مدیریت موجودی محصولات".equals(text)) { inventoryMenu.showProducts(actor); return true; }
        if ("📊 گزارش سفارش‌ها".equals(text)) { sellerMenuService.showReports(actor); return true; }
        return false;
    }

    private boolean handleOperationalCallback(String actor, String data) {
        if (data.startsWith("inventory:edit:")) {
            int id = ir.toolsmarket.tools_market_bot.util.BotFormatting.quantity(data.substring(15));
            inventoryMenu.requestQuantity(actor, id);
            awaitingPriceInput.remove(actor);
            awaitingInventoryInput.put(actor, id);
            return true;
        }
        if (!data.startsWith("orders:")) return false;
        String[] parts = data.split(":", 3);
        if (parts.length != 3) throw new IllegalArgumentException("درخواست نامعتبر است.");
        switch (parts[1]) {
            case "list" -> sellerMenuService.showOrderList(actor, parts[2]);
            case "view" -> sellerMenuService.showOrder(actor, parts[2]);
            case "prepare" -> sellerMenuService.prepare(actor, parts[2]);
            case "deliver" -> sellerMenuService.deliver(actor, parts[2]);
            case "history" -> sellerMenuService.showHistory(actor, parts[2]);
            default -> throw new IllegalArgumentException("درخواست نامعتبر است.");
        }
        return true;
    }
}
