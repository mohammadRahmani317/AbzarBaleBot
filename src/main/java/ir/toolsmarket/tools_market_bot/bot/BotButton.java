package ir.toolsmarket.tools_market_bot.bot;

import java.util.Arrays;

/** Presentation labels are translated here; handlers compare enum values. */
public enum BotButton {
    START("/start"),
    CANCEL("لغو"),
    VIEW_PRODUCTS("🛒 مشاهده محصولات"),
    CREATE_ORDER("📦 ثبت سفارش"),
    CONTACT_SELLER("📞 ارتباط با فروشنده"),
    BACK_TO_CATEGORIES("🔙 بازگشت به دسته‌بندی‌ها"),
    BACK_TO_MAIN("🏠 منوی اصلی"),
    BACK_TO_MAIN_FROM_CATEGORY("🔙 بازگشت به منوی اصلی"),
    CART("🛒 سبد خرید"),
    PROFILE("👤 اطلاعات من"),
    MY_ORDERS("📦 سفارش‌های قبلی من"),
    MANAGE_PRODUCTS("🛠 مدیریت محصولات"),
    MANAGE_PRICES("💰 مدیریت قیمت محصولات"),
    PRICE_HISTORY("📊 تاریخچه قیمت محصولات"),
    MANAGE_INVENTORY("📦 مدیریت موجودی محصولات"),
    REPORTS("📊 گزارش سفارش‌ها"),
    ORDERS("📦 مشاهده سفارش‌ها"),
    ACCESS_REQUESTS("👥 درخواست‌های دسترسی"),
    MANAGE_USERS("👤 مدیریت کاربران"),
    ORDER_PRODUCT("📦 ثبت سفارش این محصول"),
    BACK_TO_PRODUCTS("🔙 بازگشت به محصولات"),
    REMOVE("❌ حذف"),
    CHECKOUT("🧾 دریافت پیش‌فاکتور"),
    EDIT_QUANTITY_PREFIX("✏️ تعداد "),
    EDIT_PRODUCT_PREFIX("✏️ "),
    DELETE_PRODUCT_PREFIX("🗑 حذف "),
    EDIT_NAME("✏️ نام"),
    EDIT_PHONE("✏️ تلفن"),
    CONFIRM_ORDER("✅ تأیید سفارش"),
    CANCEL_ORDER("❌ لغو سفارش"),
    VIEW_ORDER("📋 مشاهده سفارش"),
    ADD_PRODUCT("➕ افزودن محصول"),
    CONFIRM_DELETE("✅ تأیید حذف"),
    BACK("🔙 بازگشت"),
    MANAGEMENT_MENU("🔙 منوی مدیریت"),
    ACTIVE_ORDERS("📋 سفارش‌های فعال"),
    DELIVERED_ORDERS("✅ تحویل‌شده"),
    CANCELLED_ORDERS("❌ لغوشده"),
    EXPIRED_ORDERS("⌛ منقضی‌شده"),
    ALL_ORDERS("📊 همه سفارش‌ها"),
    PREPARE("🔧 شروع آماده‌سازی"),
    DELIVER("📦 ثبت تحویل"),
    STATUS_HISTORY("📜 تاریخچه وضعیت"),
    CHANGE_PRICE("✏️ تغییر قیمت "),
    BACK_MAIN_SHORT("🔙 منوی اصلی"),
    PRICE_CHART_PREFIX("📊 "),
    REVIEW_ACCESS("بررسی درخواست "),
    APPROVE_ACCESS("✅ تأیید درخواست"),
    REJECT_ACCESS("❌ رد درخواست"),
    BACK_TO_ACCESS_REQUESTS("🔙 بازگشت به درخواست‌ها"),
    REOPEN_ACCESS("🔄 بررسی مجدد "),
    PROMOTE_USER("⬆️ ارتقا به مدیر "),
    DEACTIVATE_SELLER("🔴 غیرفعال‌کردن "),
    DEMOTE_USER("⬇️ تنزل به فروشنده "),
    DEACTIVATE_ADMIN("🔴 غیرفعال‌کردن مدیر ");
    private final String text;
    BotButton(String text) { this.text = text; }
    public String text() { return text; }
    public static BotButton fromText(String text) {
        return Arrays.stream(values()).filter(button -> button.text.equals(text)).findFirst().orElse(null);
    }
}
