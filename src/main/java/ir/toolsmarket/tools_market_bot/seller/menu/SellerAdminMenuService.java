package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.UserRole;
import ir.toolsmarket.tools_market_bot.model.UserStatus;
import ir.toolsmarket.tools_market_bot.service.UserAccessService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SellerAdminMenuService {

    private final BaleBotClient baleBotClient;
    private final UserAccessService userAccessService;

    public SellerAdminMenuService(
            @Qualifier("sellerBaleBotClient") BaleBotClient baleBotClient,
            UserAccessService userAccessService
    ) {
        this.baleBotClient = baleBotClient;
        this.userAccessService = userAccessService;
    }

    // نمایش منوی مدیریت
    public void showMainMenu(String chatId) {

        int pendingCount = userAccessService.findAllPending().size();

        String message = """
                سلام، به پنل مدیریت ابزارمارکت خوش آمدید.

                درخواست‌های در انتظار بررسی: %d

                لطفاً یکی از گزینه‌های زیر را انتخاب کنید:
                """.formatted(pendingCount);

        Map<String, Object> replyMarkup = Map.of(
                "keyboard", List.of(
                        List.of(
                                Map.of("text", "👥 درخواست‌های دسترسی")
                        ),
                        List.of(
                                Map.of("text", "👤 مدیریت کاربران")
                        ),
                        List.of(
                                Map.of("text", "📦 مشاهده سفارش‌ها")
                        )
                ),
                "resize_keyboard", true
        );

        baleBotClient.sendMessage(chatId, message, replyMarkup);
    }
    // نمایش فهرست درخواست‌های در انتظار بررسی
    public void showPendingUsers(String chatId) {

        var pendingUsers = userAccessService.findAllPending();

        if (pendingUsers.isEmpty()) {
            baleBotClient.sendMessage(
                    chatId,
                    "در حال حاضر هیچ درخواست دسترسی در انتظاری وجود ندارد."
            );
            return;
        }

        StringBuilder message = new StringBuilder(
                "📋 فهرست درخواست‌های دسترسی\n\n"
        );

        List<List<Map<String, Object>>> keyboard = new java.util.ArrayList<>();

        for (var user : pendingUsers) {

            String roleTitle = getRoleTitle(user.role());

            message.append("👤 نام: ")
                    .append(user.name())
                    .append("\n")
                    .append("🔑 نقش درخواستی: ")
                    .append(roleTitle)
                    .append("\n")
                    .append("🆔 شناسه: ")
                    .append(user.chatId())
                    .append("\n\n");

            keyboard.add(List.of(
                    Map.of(
                            "text", "بررسی درخواست " + user.name(),
                            "callback_data", "access:review:" + user.chatId()
                    )
            ));
        }

        Map<String, Object> replyMarkup = Map.of(
                "inline_keyboard", keyboard
        );

        baleBotClient.sendMessage(
                chatId,
                message.toString(),
                replyMarkup
        );
    }

    // تبدیل نقش فنی به عنوان فارسی
    private String getRoleTitle(UserRole role) {
        return switch (role) {
            case SUPER_ADMIN -> "مدیر اصلی";
            case ADMIN -> "مدیر";
            case SELLER -> "فروشنده";
        };
    }

    // نمایش جزئیات درخواست دسترسی
    public void showAccessRequestReview(String adminChatId, String targetChatId) {

        var userOptional = userAccessService.findByChatId(targetChatId);

        if (userOptional.isEmpty()
                || userOptional.get().status() != UserStatus.PENDING) {

            baleBotClient.sendMessage(
                    adminChatId,
                    "این درخواست وجود ندارد یا قبلاً بررسی شده است."
            );
            return;
        }

        var user = userOptional.get();

        String message = """
            📋 بررسی درخواست دسترسی

            👤 نام: %s
            🆔 شناسه: %s
            🔑 نقش درخواستی: %s
            📌 وضعیت: در انتظار بررسی
            """.formatted(
                user.name(),
                user.chatId(),
                getRoleTitle(user.role())
        );

        List<List<Map<String, Object>>> keyboard = List.of(
                List.of(
                        Map.of(
                                "text", "✅ تأیید درخواست",
                                "callback_data", "access:approve:" + targetChatId
                        ),
                        Map.of(
                                "text", "❌ رد درخواست",
                                "callback_data", "access:reject:" + targetChatId
                        )
                ),
                List.of(
                        Map.of(
                                "text", "🔙 بازگشت به درخواست‌ها",
                                "callback_data", "access:list"
                        )
                )
        );

        baleBotClient.sendMessage(
                adminChatId,
                message,
                Map.of("inline_keyboard", keyboard)
        );
    }

    // نمایش فهرست تمام کاربران

    public void showUsers(String chatId) {

        var currentUser = userAccessService.findByChatId(chatId);

        if (currentUser.isEmpty()
                || currentUser.get().status() != UserStatus.APPROVED
                || (currentUser.get().role() != UserRole.SUPER_ADMIN
                && currentUser.get().role() != UserRole.ADMIN)) {

            baleBotClient.sendMessage(chatId, "شما اجازه مدیریت کاربران را ندارید.");
            return;
        }

        boolean isSuperAdmin = currentUser.get().role() == UserRole.SUPER_ADMIN;

        var users = userAccessService.findAll();

        // مدیر عادی فقط فروشنده‌ها را مشاهده می‌کند
        if (!isSuperAdmin) {
            users = users.stream()
                    .filter(user -> user.role() == UserRole.SELLER)
                    .toList();
        }

        if (users.isEmpty()) {
            baleBotClient.sendMessage(chatId, "هنوز هیچ کاربری ثبت نشده است.");
            return;
        }

        StringBuilder message = new StringBuilder("👥 مدیریت کاربران\n\n");

        List<List<Map<String, Object>>> keyboard = new java.util.ArrayList<>();

        for (var user : users) {

            message.append("👤 نام: ")
                    .append(user.name())
                    .append("\n")
                    .append("🔑 نقش: ")
                    .append(getRoleTitle(user.role()))
                    .append("\n")
                    .append("📌 وضعیت: ")
                    .append(getStatusTitle(user.status()))
                    .append("\n")
                    .append("🆔 شناسه: ")
                    .append(user.chatId())
                    .append("\n\n");

            // مدیر اصلی نمی‌تواند حساب خودش را تغییر دهد
            if (user.chatId().equals(chatId)) {
                continue;
            }

            // بررسی مجدد کاربران ردشده فقط توسط مدیر اصلی
            if (isSuperAdmin && user.status() == UserStatus.REJECTED) {
                keyboard.add(List.of(
                        Map.of(
                                "text", "🔄 بررسی مجدد " + user.name(),
                                "callback_data", "access:reopen:" + user.chatId()
                        )
                ));
            }

            // عملیات روی فروشنده تأییدشده
            if (user.status() == UserStatus.APPROVED
                    && user.role() == UserRole.SELLER) {

                // ارتقا فقط توسط مدیر اصلی
                if (isSuperAdmin) {
                    keyboard.add(List.of(
                            Map.of(
                                    "text", "⬆️ ارتقا به مدیر " + user.name(),
                                    "callback_data", "access:promote:" + user.chatId()
                            )
                    ));
                }

                // غیرفعال‌سازی فروشنده توسط مدیر یا مدیر اصلی
                keyboard.add(List.of(
                        Map.of(
                                "text", "🔴 غیرفعال‌کردن " + user.name(),
                                "callback_data", "access:deactivate:" + user.chatId()
                        )
                ));
            }

            // عملیات روی مدیر تأییدشده فقط توسط مدیر اصلی
            if (isSuperAdmin
                    && user.status() == UserStatus.APPROVED
                    && user.role() == UserRole.ADMIN) {

                keyboard.add(List.of(
                        Map.of(
                                "text", "⬇️ تنزل به فروشنده " + user.name(),
                                "callback_data", "access:demote:" + user.chatId()
                        )
                ));

                keyboard.add(List.of(
                        Map.of(
                                "text", "🔴 غیرفعال‌کردن مدیر " + user.name(),
                                "callback_data", "access:deactivate-admin:" + user.chatId()
                        )
                ));
            }
        }

        if (keyboard.isEmpty()) {
            baleBotClient.sendMessage(chatId, message.toString());
            return;
        }

        baleBotClient.sendMessage(
                chatId,
                message.toString(),
                Map.of("inline_keyboard", keyboard)
        );
    }


    // تبدیل وضعیت فنی به عنوان فارسی
    private String getStatusTitle(UserStatus status) {
        return switch (status) {
            case PENDING -> "🟡 در انتظار بررسی";
            case APPROVED -> "🟢 تأیید شده";
            case REJECTED -> "🔴 رد شده";
        };
    }
}