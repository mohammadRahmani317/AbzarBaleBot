package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.Product;
import ir.toolsmarket.tools_market_bot.model.ProductPriceHistory;
import ir.toolsmarket.tools_market_bot.model.User;
import ir.toolsmarket.tools_market_bot.model.UserPermission;
import ir.toolsmarket.tools_market_bot.service.ProductCatalogService;
import ir.toolsmarket.tools_market_bot.service.ProductPriceService;
import ir.toolsmarket.tools_market_bot.service.UserAccessService;
import ir.toolsmarket.tools_market_bot.util.PersianDateTimeFormatter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PriceManagementMenuService {

    private static final int MAX_MESSAGE_LENGTH = 3500;
    private static final int MAX_CHART_POINTS = 10;
    private static final int MAX_CHART_BAR_LENGTH = 12;

    private final BaleBotClient baleBotClient;
    private final ProductCatalogService productCatalogService;
    private final ProductPriceService productPriceService;
    private final UserAccessService userAccessService;

    public PriceManagementMenuService(
            @Qualifier("sellerBaleBotClient")
            BaleBotClient baleBotClient,
            ProductCatalogService productCatalogService,
            ProductPriceService productPriceService,
            UserAccessService userAccessService
    ) {
        this.baleBotClient = baleBotClient;
        this.productCatalogService = productCatalogService;
        this.productPriceService = productPriceService;
        this.userAccessService = userAccessService;
    }

    /**
     * نمایش محصولات برای مدیریت قیمت
     */
    public void showProductsForUpdate(String chatId) {

        if (!userAccessService.hasPermission(
                chatId,
                UserPermission.PRICE_MANAGER
        )) {
            baleBotClient.sendMessage(
                    chatId,
                    "شما مجوز مدیریت قیمت محصولات را ندارید."
            );
            return;
        }

        List<List<Map<String, Object>>> keyboard =
                new ArrayList<>();

        StringBuilder message = new StringBuilder(
                "💰 مدیریت قیمت محصولات\n\n" +
                        "محصول موردنظر را انتخاب کنید:\n\n"
        );

        Map<Integer, BigDecimal> currentPrices =
                productPriceService.findAllCurrentPrices();

        for (Product product : productCatalogService.findAll()) {

            BigDecimal currentPrice =
                    currentPrices.get(product.id());

            String price = currentPrice == null
                    ? "استعلام قیمت"
                    : formatPrice(currentPrice);

            message.append("🔧 ")
                    .append(product.name())
                    .append(" — ")
                    .append(price)
                    .append('\n');

            keyboard.add(List.of(Map.of(
                    "text", "✏️ تغییر قیمت " + product.name(),
                    "callback_data", "price:edit:" + product.id()
            )));
        }

        keyboard.add(List.of(Map.of(
                "text", "🔙 منوی اصلی",
                "callback_data", "price:menu"
        )));

        baleBotClient.sendMessage(
                chatId,
                message.toString(),
                Map.of("inline_keyboard", keyboard)
        );
    }

    /**
     * درخواست ثبت قیمت جدید
     */
    public boolean requestPriceUpdate(
            String chatId,
            Integer productId
    ) {

        if (!userAccessService.hasPermission(
                chatId,
                UserPermission.PRICE_MANAGER
        )) {
            baleBotClient.sendMessage(
                    chatId,
                    "شما مجوز مدیریت قیمت محصولات را ندارید."
            );
            return false;
        }

        Product product = productCatalogService
                .findById(productId)
                .orElse(null);

        if (product == null) {
            baleBotClient.sendMessage(
                    chatId,
                    "محصول موردنظر پیدا نشد."
            );
            return false;
        }

        String currentPrice = productPriceService
                .findCurrentPrice(productId)
                .map(PriceManagementMenuService::formatPrice)
                .orElse("ثبت نشده است");

        baleBotClient.sendMessage(chatId, """
                🔧 محصول: %s
                💰 قیمت فعلی: %s

                لطفاً قیمت جدید را به تومان و بدون اعشار ارسال کنید.
                برای انصراف، «لغو» را ارسال کنید.
                """.formatted(product.name(), currentPrice));

        return true;
    }

    /**
     * ثبت قیمت جدید
     */
    public void savePrice(
            String chatId,
            Integer productId,
            BigDecimal price
    ) {

        if (!userAccessService.hasPermission(
                chatId,
                UserPermission.PRICE_MANAGER
        )) {
            baleBotClient.sendMessage(
                    chatId,
                    "مجوز قیمت‌گذاری شما فعال نیست."
            );
            return;
        }

        Product product = productCatalogService
                .findById(productId)
                .orElse(null);

        if (product == null) {
            baleBotClient.sendMessage(
                    chatId,
                    "محصول موردنظر پیدا نشد."
            );
            return;
        }

        // دریافت نام کاربر ثبت‌کننده
        String changedByName = userAccessService
                .findByChatId(chatId)
                .map(User::name)
                .filter(name -> name != null && !name.isBlank())
                .orElse("نامشخص");

        boolean changed = productPriceService.updatePrice(
                productId,
                price,
                chatId,
                changedByName
        );

        if (!changed) {
            baleBotClient.sendMessage(
                    chatId,
                    "قیمت واردشده با قیمت فعلی یکسان است؛ تغییری ثبت نشد."
            );
            return;
        }

        baleBotClient.sendMessage(chatId, """
                ✅ قیمت محصول با موفقیت ثبت شد.

                محصول: %s
                قیمت جدید: %s
                ثبت‌کننده: %s
                """.formatted(
                product.name(),
                formatPrice(price),
                changedByName
        ));

        showProductsForUpdate(chatId);
    }

    /**
     * نمایش فهرست محصولات برای مشاهده تاریخچه
     */
    public void showProductsForHistory(String chatId) {

        if (!userAccessService.hasPermission(
                chatId,
                UserPermission.VIEW_PRICE_HISTORY
        )) {
            baleBotClient.sendMessage(
                    chatId,
                    "فقط مدیر اصلی اجازه مشاهده تاریخچه قیمت‌ها را دارد."
            );
            return;
        }

        List<List<Map<String, Object>>> keyboard =
                new ArrayList<>();

        for (Product product : productCatalogService.findAll()) {

            keyboard.add(List.of(Map.of(
                    "text", "📊 " + product.name(),
                    "callback_data", "price:history:" + product.id()
            )));
        }

        keyboard.add(List.of(Map.of(
                "text", "🔙 منوی مدیریت",
                "callback_data", "price:menu"
        )));

        baleBotClient.sendMessage(
                chatId,
                "📊 تاریخچه قیمت محصولات\n\nمحصول موردنظر را انتخاب کنید:",
                Map.of("inline_keyboard", keyboard)
        );
    }

    /**
     * نمایش تاریخچه قیمت یک محصول
     */
    public void showHistory(
            String chatId,
            Integer productId
    ) {

        if (!userAccessService.hasPermission(
                chatId,
                UserPermission.VIEW_PRICE_HISTORY
        )) {
            baleBotClient.sendMessage(
                    chatId,
                    "فقط مدیر اصلی اجازه مشاهده تاریخچه قیمت‌ها را دارد."
            );
            return;
        }

        Product product = productCatalogService
                .findById(productId)
                .orElse(null);

        if (product == null) {
            baleBotClient.sendMessage(
                    chatId,
                    "محصول موردنظر پیدا نشد."
            );
            return;
        }

        List<ProductPriceHistory> history = productPriceService.findHistory(productId);

        if (history.isEmpty()) {
            baleBotClient.sendMessage(
                    chatId,
                    "برای این محصول هنوز تاریخچه قیمتی ثبت نشده است."
            );
            return;
        }

        // ابتدا نمودار قیمت نمایش داده می‌شود
        String chart = buildPriceChart(product.name(), history);

        baleBotClient.sendMessage(chatId, chart);

        // سپس جزئیات تغییرات نمایش داده می‌شود
        showHistoryDetails(chatId, product.name(), history);
    }

    /**
     * ساخت نمودار متنی قیمت
     */
    private String buildPriceChart(
            String productName,
            List<ProductPriceHistory> history
    ) {

        List<ProductPriceHistory> chronologicalHistory =
                history.stream()
                        .sorted(
                                Comparator.comparing(
                                        ProductPriceHistory::changedAt
                                )
                        )
                        .toList();

        // فقط ۱۰ تغییر آخر در نمودار نمایش داده می‌شود
        int startIndex = Math.max(
                0,
                chronologicalHistory.size() - MAX_CHART_POINTS
        );

        List<ProductPriceHistory> chartPoints =
                chronologicalHistory.subList(
                        startIndex,
                        chronologicalHistory.size()
                );

        BigDecimal minPrice = chartPoints.stream()
                .map(ProductPriceHistory::newPrice)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal maxPrice = chartPoints.stream()
                .map(ProductPriceHistory::newPrice)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);

        BigDecimal priceRange = maxPrice.subtract(minPrice);

        StringBuilder chart = new StringBuilder();

        chart.append("📈 نمودار تغییرات قیمت\n")
                .append("🔧 محصول: ")
                .append(productName)
                .append("\n\n")
                .append("قدیمی‌تر ⬅️ جدیدتر\n\n");

        for (ProductPriceHistory item : chartPoints) {

            BigDecimal price = item.newPrice();

            int barLength;

            if (priceRange.compareTo(BigDecimal.ZERO) == 0) {
                barLength = 6;
            } else {
                barLength = price.subtract(minPrice)
                        .multiply(BigDecimal.valueOf(
                                MAX_CHART_BAR_LENGTH - 1
                        ))
                        .divide(
                                priceRange,
                                0,
                                RoundingMode.HALF_UP
                        )
                        .intValue() + 1;
            }

            chart.append("▰".repeat(barLength))
                    .append("\n")
                    .append("💰 ")
                    .append(formatPrice(price))
                    .append("\n")
                    .append("📅 ")
                    .append(
                            PersianDateTimeFormatter.formatDate(
                                    item.changedAt()
                            )
                    )
                    .append("\n\n");
        }

        chart.append("━━━━━━━━━━━━━━");

        return chart.toString();
    }

    /**
     * نمایش جزئیات تاریخچه قیمت
     */
    private void showHistoryDetails(
            String chatId,
            String productName,
            List<ProductPriceHistory> history
    ) {

        String header =
                "📊 جزئیات تاریخچه قیمت\n" +
                        "🔧 محصول: " + productName + "\n\n";

        StringBuilder message = new StringBuilder(header);

        for (int i = 0; i < history.size(); i++) {

            ProductPriceHistory item = history.get(i);

            String changedByName = resolveChangedByName(item);

            String entry =
                    "🔹 ردیف: " + formatNumber(i + 1) + "\n" +
                            "📅 تاریخ: " +
                            PersianDateTimeFormatter.formatDate(
                                    item.changedAt()
                            ) + "\n" +

                            "🕒 ساعت: " +
                            PersianDateTimeFormatter.formatTime(
                                    item.changedAt()
                            ) + "\n" +

                            "💰 قیمت جدید: " +
                            formatPrice(item.newPrice()) + "\n" +

                            "⬅️ قیمت قبلی: " +
                            (item.previousPrice() == null
                                    ? "اولین ثبت"
                                    : formatPrice(item.previousPrice())) + "\n" +

                            "👤 ثبت‌کننده: " +
                            changedByName + "\n" +

                            "━━━━━━━━━━━━━━\n";

            if (message.length() + entry.length() > MAX_MESSAGE_LENGTH) {

                baleBotClient.sendMessage(
                        chatId,
                        message.toString()
                );

                message = new StringBuilder(
                        header + "ادامه تاریخچه:\n\n"
                );
            }

            message.append(entry);
        }

        if (message.length() > 0) {
            baleBotClient.sendMessage(
                    chatId,
                    message.toString()
            );
        }
    }

    /**
     * دریافت نام ثبت‌کننده
     *
     * برای تاریخچه‌های قدیمی که نام در آن‌ها ذخیره نشده،
     * نام از فایل کاربران بازیابی می‌شود.
     */
    private String resolveChangedByName(
            ProductPriceHistory item
    ) {

        if (item.changedByName() != null
                && !item.changedByName().isBlank()) {
            return item.changedByName();
        }

        return userAccessService
                .findByChatId(item.changedByChatId())
                .map(User::name)
                .filter(name -> name != null && !name.isBlank())
                .orElse("نامشخص");
    }

    /**
     * نمایش قیمت با جداکننده هزارگان
     */
    private static String formatPrice(BigDecimal price) {

        return NumberFormat
                .getNumberInstance(Locale.forLanguageTag("fa-IR"))
                .format(price) + " تومان";
    }

    /**
     * نمایش اعداد فارسی
     */
    private static String formatNumber(int number) {

        return NumberFormat
                .getIntegerInstance(Locale.forLanguageTag("fa-IR"))
                .format(number);
    }
}