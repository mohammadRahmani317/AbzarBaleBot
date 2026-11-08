# کدهای قابلیت قیمت‌گذاری ابزارمارکت

این سند شامل نسخه کامل فایل‌هایی است که باید به‌صورت جایگزین در پروژه قرار بگیرند یا به پروژه اضافه شوند. پکیج پایه همه فایل‌ها `ir.toolsmarket.tools_market_bot` است.

## روش اعمال

1. از پروژه فعلی یک کپی پشتیبان بگیر.
2. فایل‌های موجود را دقیقاً در مسیرهای درج‌شده جایگزین کن.
3. فایل‌هایی که در پروژه وجود ندارند را با همان مسیر و نام ایجاد کن.
4. `application.yml` را مطابق بخش انتهایی سند اصلاح کن؛ توکن واقعی را داخل سند یا سورس قرار نده.
5. بعد از کپی، Build و تست‌های انتهای سند را اجرا کن.

## `model/User.java`

```java
package ir.toolsmarket.tools_market_bot.model;

import java.util.Set;

public record User(
        String chatId,
        String name,
        UserRole role,
        UserStatus status,
        Set<UserPermission> permissions
) {

    public User {
        // سازگاری با کاربران قدیمی که فیلد permissions ندارند
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    public User(String chatId, String name, UserRole role, UserStatus status) {
        this(chatId, name, role, status, Set.of());
    }

    public boolean hasPermission(UserPermission permission) {
        return permissions.contains(permission);
    }
}
```

## `model/UserPermission.java`

```java
package ir.toolsmarket.tools_market_bot.model;

public enum UserPermission {
    PRICE_MANAGER,
    VIEW_PRICE_HISTORY
}
```

## `model/ProductPriceStore.java`

```java
package ir.toolsmarket.tools_market_bot.model;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ProductPriceStore(
        Map<Integer, BigDecimal> prices,
        List<ProductPriceHistory> history
) {
    public ProductPriceStore {
        prices = prices == null ? Map.of() : Map.copyOf(prices);
        history = history == null ? List.of() : List.copyOf(history);
    }

    public static ProductPriceStore empty() {
        return new ProductPriceStore(Map.of(), List.of());
    }
}
```

## `model/ProductPriceHistory.java`

```java
package ir.toolsmarket.tools_market_bot.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record ProductPriceHistory(
        String id,
        Integer productId,
        BigDecimal previousPrice,
        BigDecimal newPrice,
        String changedByChatId,
        LocalDateTime changedAt
) {
}
```

## `model/Order.java`

```java
package ir.toolsmarket.tools_market_bot.model;

import java.math.BigDecimal;
import java.time.Instant;

public record Order(
        String orderId,
        String chatId,
        Integer productId,
        String productName,
        Integer quantity,
        String customerName,
        String phoneNumber,
        Instant createdAt,
        BigDecimal unitPriceSnapshot
) {

    // سازگاری با سفارش‌های قدیمی و سازنده قبلی
    public Order(
            String orderId, String chatId, Integer productId, String productName,
            Integer quantity, String customerName, String phoneNumber, Instant createdAt
    ) {
        this(orderId, chatId, productId, productName, quantity, customerName,
                phoneNumber, createdAt, null);
    }
}
```

## `service/ProductPriceService.java`

```java
package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.ProductPriceHistory;
import ir.toolsmarket.tools_market_bot.model.ProductPriceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class ProductPriceService {

    private final ObjectMapper objectMapper;
    private final Path filePath;

    public ProductPriceService(
            ObjectMapper objectMapper,
            @Value("${tools-market.storage.product-prices-file:data/product-prices.json}") String filePath
    ) {
        this.objectMapper = objectMapper;
        this.filePath = Path.of(filePath);
    }

    public synchronized Optional<BigDecimal> findCurrentPrice(Integer productId) {
        return Optional.ofNullable(readStore().prices().get(productId));
    }

    public synchronized Map<Integer, BigDecimal> findAllCurrentPrices() {
        return readStore().prices();
    }

    public synchronized List<ProductPriceHistory> findHistory(Integer productId) {
        return readStore().history().stream()
                .filter(item -> item.productId().equals(productId))
                .sorted(Comparator.comparing(ProductPriceHistory::changedAt).reversed())
                .toList();
    }

    // هر تغییر واقعی قیمت، یک رویداد غیرقابل‌ویرایش در تاریخچه ایجاد می‌کند
    public synchronized boolean updatePrice(
            Integer productId,
            BigDecimal newPrice,
            String changedByChatId
    ) {
        if (productId == null || newPrice == null || newPrice.signum() <= 0) {
            throw new IllegalArgumentException("قیمت باید عددی بزرگ‌تر از صفر باشد.");
        }
        if (newPrice.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException("قیمت باید به تومان کامل وارد شود.");
        }

        ProductPriceStore current = readStore();
        BigDecimal previousPrice = current.prices().get(productId);
        if (newPrice.compareTo(previousPrice == null ? BigDecimal.ZERO : previousPrice) == 0) {
            return false;
        }

        Map<Integer, BigDecimal> prices = new HashMap<>(current.prices());
        prices.put(productId, newPrice.setScale(0));

        List<ProductPriceHistory> history = new ArrayList<>(current.history());
        history.add(new ProductPriceHistory(
                UUID.randomUUID().toString(),
                productId,
                previousPrice,
                newPrice.setScale(0),
                changedByChatId,
                LocalDateTime.now(ZoneId.of("Asia/Tehran"))
        ));

        writeStore(new ProductPriceStore(prices, history));
        return true;
    }

    private ProductPriceStore readStore() {
        if (Files.notExists(filePath)) {
            return ProductPriceStore.empty();
        }
        try {
            ProductPriceStore store = objectMapper.readValue(filePath.toFile(), ProductPriceStore.class);
            return store == null ? ProductPriceStore.empty() : store;
        } catch (IOException exception) {
            throw new IllegalStateException("خطا در خواندن اطلاعات قیمت محصولات", exception);
        }
    }

    private void writeStore(ProductPriceStore store) {
        Path parent = filePath.toAbsolutePath().getParent();
        Path temporaryFile = filePath.resolveSibling(filePath.getFileName() + ".tmp");
        try {
            Files.createDirectories(parent);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporaryFile.toFile(), store);
            try {
                Files.move(temporaryFile, filePath, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporaryFile, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            try { Files.deleteIfExists(temporaryFile); } catch (IOException ignored) { }
            throw new IllegalStateException("خطا در ذخیره اطلاعات قیمت محصولات", exception);
        }
    }
}
```

## `service/UserAccessService.java`

```java

package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.User;
import ir.toolsmarket.tools_market_bot.model.UserRole;
import ir.toolsmarket.tools_market_bot.model.UserStatus;
import ir.toolsmarket.tools_market_bot.model.UserPermission;
import java.util.HashSet;
import java.util.Set;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class UserAccessService {

    private final ObjectMapper objectMapper;

    private final Path filePath = Path.of("data/users.json");

    public UserAccessService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // دریافت تمام کاربران
    public synchronized List<User> findAll() {
        return readUsers();
    }

    // پیدا کردن کاربر با شناسه بله
    public synchronized Optional<User> findByChatId(String chatId) {
        return readUsers().stream()
                .filter(user -> user.chatId().equals(chatId))
                .findFirst();
    }

    // ثبت درخواست دسترسی جدید
    public synchronized User registerPending(String chatId, String name) {

        List<User> users = readUsers();

        Optional<User> existingUser = users.stream()
                .filter(user -> user.chatId().equals(chatId))
                .findFirst();

        if (existingUser.isPresent()) {
            return existingUser.get();
        }

        User user = new User(
                chatId,
                name,
                UserRole.SELLER,
                UserStatus.PENDING
        );

        users.add(user);
        writeUsers(users);

        return user;
    }

    // تغییر وضعیت کاربر
    public synchronized void updateStatus(String chatId, UserStatus status) {

        List<User> users = readUsers();

        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);

            if (user.chatId().equals(chatId)) {
                users.set(i, new User(
                        user.chatId(),
                        user.name(),
                        user.role(),
                        status,
                        user.permissions()
                ));

                writeUsers(users);
                return;
            }
        }
    }

    // تغییر نقش کاربر
    public synchronized void updateRole(String chatId, UserRole role) {

        List<User> users = readUsers();

        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);

            if (user.chatId().equals(chatId)) {
                users.set(i, new User(
                        user.chatId(),
                        user.name(),
                        role,
                        user.status(),
                        user.permissions()
                ));

                writeUsers(users);
                return;
            }
        }
    }

    // خواندن کاربران از فایل JSON
    private List<User> readUsers() {
        if (Files.notExists(filePath)) {
            return new ArrayList<>();
        }

        return objectMapper.readValue(
                filePath.toFile(),
                new TypeReference<>() {
                }
        );

    }

    // ذخیره کاربران در فایل JSON
    private void writeUsers(List<User> users) {
        try {
            Files.createDirectories(filePath.getParent());

            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(filePath.toFile(), users);

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "خطا در ذخیره اطلاعات کاربران",
                    exception
            );
        }
    }

    // ثبت یا اصلاح اطلاعات مدیر اصلی
    public synchronized void ensureSuperAdmin(String chatId) {

        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException(
                    "شناسه مدیر اصلی در تنظیمات مشخص نشده است"
            );
        }

        List<User> users = readUsers();

        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);

            if (user.chatId().equals(chatId)) {

                Set<UserPermission> permissions = new HashSet<>(user.permissions());
                permissions.add(UserPermission.VIEW_PRICE_HISTORY);
                users.set(i, new User(
                        user.chatId(),
                        user.name(),
                        UserRole.SUPER_ADMIN,
                        UserStatus.APPROVED,
                        permissions
                ));

                writeUsers(users);
                return;
            }
        }

        users.add(new User(
                chatId,
                "مدیر اصلی",
                UserRole.SUPER_ADMIN,
                UserStatus.APPROVED,
                Set.of(UserPermission.VIEW_PRICE_HISTORY)
        ));

        writeUsers(users);
    }

    // بررسی مجوز مستقل از نقش کاربر
    public synchronized boolean hasPermission(String chatId, UserPermission permission) {
        return findByChatId(chatId)
                .filter(user -> user.status() == UserStatus.APPROVED)
                .map(user -> user.hasPermission(permission))
                .orElse(false);
    }

    // اعطا یا لغو یک مجوز؛ کنترل اینکه درخواست‌دهنده سوپرادمین است در لایه سرویس فراخواننده انجام می‌شود
    public synchronized void setPermission(
            String chatId,
            UserPermission permission,
            boolean enabled
    ) {
        List<User> users = readUsers();

        for (int i = 0; i < users.size(); i++) {
            User user = users.get(i);
            if (!user.chatId().equals(chatId)) {
                continue;
            }

            Set<UserPermission> permissions = new HashSet<>(user.permissions());
            if (enabled) {
                permissions.add(permission);
            } else {
                permissions.remove(permission);
            }

            users.set(i, new User(
                    user.chatId(), user.name(), user.role(), user.status(), permissions
            ));
            writeUsers(users);
            return;
        }
    }

    public synchronized List<User> findAllPending() {
        return readUsers().stream()
                .filter(user -> user.status() == UserStatus.PENDING)
                .toList();
    }
}
```

## `seller/menu/PriceManagementMenuService.java`

```java
package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.Product;
import ir.toolsmarket.tools_market_bot.model.ProductPriceHistory;
import ir.toolsmarket.tools_market_bot.model.UserPermission;
import ir.toolsmarket.tools_market_bot.service.ProductCatalogService;
import ir.toolsmarket.tools_market_bot.service.ProductPriceService;
import ir.toolsmarket.tools_market_bot.service.UserAccessService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PriceManagementMenuService {

    private final BaleBotClient baleBotClient;
    private final ProductCatalogService productCatalogService;
    private final ProductPriceService productPriceService;
    private final UserAccessService userAccessService;

    public PriceManagementMenuService(
            @Qualifier("sellerBaleBotClient") BaleBotClient baleBotClient,
            ProductCatalogService productCatalogService,
            ProductPriceService productPriceService,
            UserAccessService userAccessService
    ) {
        this.baleBotClient = baleBotClient;
        this.productCatalogService = productCatalogService;
        this.productPriceService = productPriceService;
        this.userAccessService = userAccessService;
    }

    public void showProductsForUpdate(String chatId) {
        if (!userAccessService.hasPermission(chatId, UserPermission.PRICE_MANAGER)) {
            baleBotClient.sendMessage(chatId, "شما مجوز مدیریت قیمت محصولات را ندارید.");
            return;
        }

        List<List<Map<String, Object>>> keyboard = new ArrayList<>();
        StringBuilder message = new StringBuilder("💰 مدیریت قیمت محصولات\n\nمحصول موردنظر را انتخاب کنید:\n\n");
        Map<Integer, BigDecimal> currentPrices = productPriceService.findAllCurrentPrices();
        for (Product product : productCatalogService.findAll()) {
            BigDecimal currentPrice = currentPrices.get(product.id());
            String price = currentPrice == null
                    ? "استعلام قیمت"
                    : formatPrice(currentPrice);
            message.append("🔧 ").append(product.name()).append(" — ").append(price).append('\n');
            keyboard.add(List.of(Map.of(
                    "text", "✏️ تغییر قیمت " + product.name(),
                    "callback_data", "price:edit:" + product.id()
            )));
        }
        keyboard.add(List.of(Map.of("text", "🔙 منوی اصلی", "callback_data", "price:menu")));
        baleBotClient.sendMessage(chatId, message.toString(), Map.of("inline_keyboard", keyboard));
    }

    public boolean requestPriceUpdate(String chatId, Integer productId) {
        if (!userAccessService.hasPermission(chatId, UserPermission.PRICE_MANAGER)) {
            baleBotClient.sendMessage(chatId, "شما مجوز مدیریت قیمت محصولات را ندارید.");
            return false;
        }
        Product product = productCatalogService.findById(productId).orElse(null);
        if (product == null) {
            baleBotClient.sendMessage(chatId, "محصول موردنظر پیدا نشد.");
            return false;
        }
        String currentPrice = productPriceService.findCurrentPrice(productId)
                .map(PriceManagementMenuService::formatPrice).orElse("ثبت نشده است");
        baleBotClient.sendMessage(chatId, """
                🔧 محصول: %s
                💰 قیمت فعلی: %s

                لطفاً قیمت جدید را به تومان و بدون اعشار ارسال کنید.
                برای انصراف، «لغو» را ارسال کنید.
                """.formatted(product.name(), currentPrice));
        return true;
    }

    public void savePrice(String chatId, Integer productId, BigDecimal price) {
        if (!userAccessService.hasPermission(chatId, UserPermission.PRICE_MANAGER)) {
            baleBotClient.sendMessage(chatId, "مجوز قیمت‌گذاری شما فعال نیست.");
            return;
        }
        Product product = productCatalogService.findById(productId).orElse(null);
        if (product == null) {
            baleBotClient.sendMessage(chatId, "محصول موردنظر پیدا نشد.");
            return;
        }
        boolean changed = productPriceService.updatePrice(productId, price, chatId);
        if (!changed) {
            baleBotClient.sendMessage(chatId, "قیمت واردشده با قیمت فعلی یکسان است؛ تغییری ثبت نشد.");
            return;
        }
        baleBotClient.sendMessage(chatId, """
                ✅ قیمت محصول با موفقیت ثبت شد.

                محصول: %s
                قیمت جدید: %s
                """.formatted(product.name(), formatPrice(price)));
        showProductsForUpdate(chatId);
    }

    public void showProductsForHistory(String chatId) {
        if (!userAccessService.hasPermission(chatId, UserPermission.VIEW_PRICE_HISTORY)) {
            baleBotClient.sendMessage(chatId, "فقط مدیر اصلی اجازه مشاهده تاریخچه قیمت‌ها را دارد.");
            return;
        }
        List<List<Map<String, Object>>> keyboard = new ArrayList<>();
        for (Product product : productCatalogService.findAll()) {
            keyboard.add(List.of(Map.of(
                    "text", "📊 " + product.name(),
                    "callback_data", "price:history:" + product.id()
            )));
        }
        keyboard.add(List.of(Map.of("text", "🔙 منوی مدیریت", "callback_data", "price:menu")));
        baleBotClient.sendMessage(chatId, "📊 تاریخچه قیمت محصولات\n\nمحصول موردنظر را انتخاب کنید:",
                Map.of("inline_keyboard", keyboard));
    }

    public void showHistory(String chatId, Integer productId) {
        if (!userAccessService.hasPermission(chatId, UserPermission.VIEW_PRICE_HISTORY)) {
            baleBotClient.sendMessage(chatId, "فقط مدیر اصلی اجازه مشاهده تاریخچه قیمت‌ها را دارد.");
            return;
        }
        Product product = productCatalogService.findById(productId).orElse(null);
        if (product == null) {
            baleBotClient.sendMessage(chatId, "محصول موردنظر پیدا نشد.");
            return;
        }
        List<ProductPriceHistory> history = productPriceService.findHistory(productId);
        if (history.isEmpty()) {
            baleBotClient.sendMessage(chatId, "برای این محصول هنوز تاریخچه قیمتی ثبت نشده است.");
            return;
        }
        String header = "📊 تاریخچه قیمت: " + product.name() + "\n\n";
        StringBuilder message = new StringBuilder(header);
        for (ProductPriceHistory item : history) {
            String entry = "💰 " + formatPrice(item.newPrice()) + "\n"
                    + "⬅️ قیمت قبلی: "
                    + (item.previousPrice() == null ? "اولین ثبت" : formatPrice(item.previousPrice())) + "\n"
                    + "👤 ثبت‌کننده: " + item.changedByChatId() + "\n"
                    + "🕒 زمان: " + item.changedAt().toString().replace('T', ' ') + "\n"
                    + "━━━━━━━━━━━━━━\n";
            if (message.length() + entry.length() > 3500) {
                baleBotClient.sendMessage(chatId, message.toString());
                message = new StringBuilder(header + "(ادامه تاریخچه)\n\n");
            }
            message.append(entry);
        }
        if (message.length() > 0) {
            baleBotClient.sendMessage(chatId, message.toString());
        }
    }

    private static String formatPrice(BigDecimal price) {
        return NumberFormat.getNumberInstance(Locale.forLanguageTag("fa-IR"))
                .format(price) + " تومان";
    }
}
```

## `seller/menu/SellerMenuService.java`

```java

package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.Order;
import ir.toolsmarket.tools_market_bot.service.OrderStorageService;
import ir.toolsmarket.tools_market_bot.service.UserAccessService;
import ir.toolsmarket.tools_market_bot.model.UserPermission;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

@Service
public class SellerMenuService {

    private final BaleBotClient baleBotClient;
    private final OrderStorageService orderStorageService;
    private final UserAccessService userAccessService;

    public SellerMenuService(
            @Qualifier("sellerBaleBotClient") BaleBotClient baleBotClient,
            OrderStorageService orderStorageService,
            UserAccessService userAccessService
    ) {
        this.baleBotClient = baleBotClient;
        this.orderStorageService = orderStorageService;
        this.userAccessService = userAccessService;
    }

    public void showMainMenu(String chatId) {

        String message = """
                سلام، به پنل مدیریت ابزارمارکت خوش آمدید.

                لطفاً یکی از گزینه‌های زیر را انتخاب کنید:
                """;

        List<List<Map<String, Object>>> keyboard = new java.util.ArrayList<>();
        keyboard.add(List.of(Map.of("text", "📦 مشاهده سفارش‌ها")));
        if (userAccessService.hasPermission(chatId, UserPermission.PRICE_MANAGER)) {
            keyboard.add(List.of(Map.of("text", "💰 مدیریت قیمت محصولات")));
        }
        Map<String, Object> replyMarkup = Map.of(
                "keyboard", keyboard,
                "resize_keyboard", true
        );

        baleBotClient.sendMessage(chatId, message, replyMarkup);
    }

    // نمایش فهرست سفارش‌ها برای فروشنده
    public void showOrders(String chatId) {

        try {

            List<Order> orders = orderStorageService.findAll();

            if (orders.isEmpty()) {
                baleBotClient.sendMessage(
                        chatId,
                        "📭 هنوز هیچ سفارشی ثبت نشده است."
                );
                return;
            }

            StringBuilder message = new StringBuilder(
                    "📦 فهرست سفارش‌های ثبت‌شده\n\n"
            );

            for (Order order : orders) {

                message.append("🧾 شماره سفارش: ")
                        .append(order.orderId())
                        .append("\n")
                        .append("📦 محصول: ")
                        .append(order.productName())
                        .append("\n")
                        .append("🔢 تعداد: ")
                        .append(order.quantity())
                        .append("\n")
                        .append("👤 مشتری: ")
                        .append(order.customerName())
                        .append("\n")
                        .append("📞 تماس: ")
                        .append(order.phoneNumber())
                        .append("\n");

                if (order.unitPriceSnapshot() != null) {
                    message.append("💰 قیمت زمان ثبت سفارش: ")
                            .append(NumberFormat.getNumberInstance(Locale.forLanguageTag("fa-IR"))
                                    .format(order.unitPriceSnapshot()))
                            .append(" تومان\n");
                }

                message.append("━━━━━━━━━━━━━━")
                        .append("\n\n");
            }

            baleBotClient.sendMessage(chatId, message.toString());

        } catch (IOException e) {

            baleBotClient.sendMessage(
                    chatId,
                    "در دریافت فهرست سفارش‌ها مشکلی پیش آمد. لطفاً دوباره تلاش کنید."
            );
        }
    }

    public void showHelp(String chatId) {
        baleBotClient.sendMessage(
                chatId,
                "برای مدیریت سفارش‌های مشتریان از گزینه «📦 مشاهده سفارش‌ها» استفاده کنید."
        );
    }
}
```

## `seller/menu/SellerAdminMenuService.java`

```java
package ir.toolsmarket.tools_market_bot.seller.menu;

import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.UserRole;
import ir.toolsmarket.tools_market_bot.model.UserStatus;
import ir.toolsmarket.tools_market_bot.model.UserPermission;
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

        List<List<Map<String, Object>>> keyboard = new java.util.ArrayList<>();
        keyboard.add(List.of(Map.of("text", "👥 درخواست‌های دسترسی")));
        keyboard.add(List.of(Map.of("text", "👤 مدیریت کاربران")));
        keyboard.add(List.of(Map.of("text", "📦 مشاهده سفارش‌ها")));
        if (userAccessService.hasPermission(chatId, UserPermission.VIEW_PRICE_HISTORY)) {
            keyboard.add(List.of(Map.of("text", "📊 تاریخچه قیمت محصولات")));
        }
        Map<String, Object> replyMarkup = Map.of(
                "keyboard", keyboard,
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

                // مدیریت مجوز قیمت‌گذاری فقط توسط سوپرادمین
                if (isSuperAdmin) {
                    boolean priceManager = user.hasPermission(UserPermission.PRICE_MANAGER);
                    keyboard.add(List.of(Map.of(
                            "text", priceManager
                                    ? "🔒 لغو مجوز قیمت‌گذاری " + user.name()
                                    : "💰 اعطای مجوز قیمت‌گذاری " + user.name(),
                            "callback_data", (priceManager ? "access:price-revoke:" : "access:price-grant:") + user.chatId()
                    )));
                }

                // غیرفعال‌سازی فروشنده توسط مدیر یا مدیر اصلی
                keyboard.add(List.of(
                        Map.of(
                                "text", "🔴 غیرفعال‌کردن " + user.name(),
                                "callback_data", "access:deactivate:" + user.chatId()
                        )
                ));
            }

            // اگر کاربری با مجوز قیمت‌گذاری به مدیر ارتقا یافت، امکان لغو مجوز همچنان وجود دارد
            if (isSuperAdmin
                    && user.status() == UserStatus.APPROVED
                    && user.role() == UserRole.ADMIN
                    && user.hasPermission(UserPermission.PRICE_MANAGER)) {
                keyboard.add(List.of(Map.of(
                        "text", "🔒 لغو مجوز قیمت‌گذاری " + user.name(),
                        "callback_data", "access:price-revoke:" + user.chatId()
                )));
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
```

## `seller/service/SellerUpdateService.java`

```java
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

    private long nextUpdateId = 0;

    public SellerUpdateService(
            @Qualifier("sellerBaleBotClient") BaleBotClient baleBotClient,
            SellerMenuService sellerMenuService,
            UserAccessService userAccessService,
            BaleBotProperties properties,
            SellerAdminMenuService sellerAdminMenuService,
            PriceManagementMenuService priceManagementMenuService
    ) {
        this.baleBotClient = baleBotClient;
        this.sellerMenuService = sellerMenuService;
        this.userAccessService = userAccessService;
        this.properties = properties;
        this.sellerAdminMenuService = sellerAdminMenuService;
        this.priceManagementMenuService = priceManagementMenuService;
    }

    @Scheduled(fixedDelay = 1000)
    public void receiveUpdates() {

        try {
            JsonNode response = baleBotClient.getUpdates(nextUpdateId);

            if (response == null || !response.path("ok").asBoolean()) {
                log.warn("دریافت آپدیت از بات فروشنده ناموفق بود: {}", response);
                return;
            }

            for (JsonNode update : response.path("result")) {

                processUpdate(update);

                nextUpdateId =
                        update.path("update_id").asLong() + 1;
            }

        } catch (Exception exception) {
            log.error("خطا در دریافت پیام از بات فروشنده", exception);
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

        log.info("پیام دریافتی از فروشنده {}: {}", chatId, text);

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
//                    || userOptional.get().role() == UserRole.SUPER_ADMIN
//                    || userOptional.get().role() == UserRole.ADMIN
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
            log.warn("پاسخ به درخواست دکمه بله ناموفق بود: {}", e.getMessage());
        }

        // عملیات قیمت‌گذاری دسترسی مستقل از نقش مدیر دارد
        if (callbackData.startsWith("price:edit:")) {
            String productIdText = callbackData.substring("price:edit:".length());
            try {
                Integer productId = Integer.parseInt(productIdText);
                if (priceManagementMenuService.requestPriceUpdate(adminChatId, productId)) {
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
        String normalized = rawValue
                .replace("٬", "")
                .replace(",", "")
                .replace("،", "")
                .replace(" ", "")
                .replace("۰", "0").replace("۱", "1").replace("۲", "2")
                .replace("۳", "3").replace("۴", "4").replace("۵", "5")
                .replace("۶", "6").replace("۷", "7").replace("۸", "8")
                .replace("۹", "9")
                .replace("٠", "0").replace("١", "1").replace("٢", "2")
                .replace("٣", "3").replace("٤", "4").replace("٥", "5")
                .replace("٦", "6").replace("٧", "7").replace("٨", "8")
                .replace("٩", "9");
        if (!normalized.matches("\\d+")) {
            throw new NumberFormatException("قیمت باید عدد صحیح باشد");
        }
        return new BigDecimal(normalized);
    }

}
```

## `customer/menu/BotMenuService.java`

```java
package ir.toolsmarket.tools_market_bot.customer.menu;

import ir.toolsmarket.tools_market_bot.model.Product;
import ir.toolsmarket.tools_market_bot.model.ProductCategory;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.service.ProductPriceService;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BotMenuService {

    private static final Logger log = LoggerFactory.getLogger(BotMenuService.class);

    private final BaleBotClient baleBotClient;
    private final ProductPriceService productPriceService;

    public BotMenuService(BaleBotClient baleBotClient, ProductPriceService productPriceService) {
        this.baleBotClient = baleBotClient;
        this.productPriceService = productPriceService;
    }

    // نمایش منوی اصلی
    public void showMainMenu(String chatId) {

        List<List<String>> keyboard = List.of(
                List.of(BotMenuOption.VIEW_PRODUCTS.getTitle()),
                List.of(BotMenuOption.CREATE_ORDER.getTitle()),
                List.of(BotMenuOption.CONTACT_SELLER.getTitle())
        );

        sendMessage(
                chatId,
                """
                        به ابزارمارکت خوش آمدید 🔧
                        
                        لطفاً یکی از گزینه‌های زیر را انتخاب کنید:
                        """,
                keyboard
        );
    }

    // نمایش دسته‌بندی محصولات
    public void showCategoriesMenu(String chatId) {

        List<List<String>> keyboard = java.util.Arrays.stream(
                        ProductCategory.values()
                )
                .map(category -> List.of(category.getTitle()))
                .collect(java.util.stream.Collectors.toList());

        keyboard.add(
                List.of(BotMenuOption.BACK_TO_MAIN_FROM_CATEGORY.getTitle())
        );

        sendMessage(
                chatId,
                """
                        🛒 دسته‌بندی محصولات
                        
                        لطفاً دسته‌بندی موردنظر خود را انتخاب کنید:
                        """,
                keyboard
        );
    }

    // نمایش محصولات یک دسته‌بندی
    // نمایش محصولات یک دسته‌بندی با دکمه‌های Inline
    public void showProducts(String chatId, ProductCategory category, List<Product> products) {
        if (products.isEmpty()) {
            sendMessage(
                    chatId,
                    "در حال حاضر محصولی در این دسته‌بندی ثبت نشده است.",
                    List.of(
                            List.of(BotMenuOption.BACK_TO_CATEGORIES.getTitle()),
                            List.of(BotMenuOption.BACK_TO_MAIN.getTitle())
                    )
            );
            return;
        }

        List<List<Map<String, Object>>> inlineKeyboard =
                new java.util.ArrayList<>();

        for (Product product : products) {
            inlineKeyboard.add(List.of(
                    Map.of(
                            "text", product.name(),
                            "callback_data", "product:detail:" + product.id()
                    )
            ));
        }

        inlineKeyboard.add(List.of(
                Map.of(
                        "text", "🔙 بازگشت به دسته‌بندی‌ها",
                        "callback_data", "nav:categories"
                )
        ));

        Map<String, Object> replyMarkup = Map.of(
                "inline_keyboard", inlineKeyboard
        );

        baleBotClient.sendMessage(
                chatId,
                category.getTitle() + "\n\nبرای مشاهده جزئیات، محصول موردنظر را انتخاب کنید:",
                replyMarkup
        );
    }

    // نمایش پیام ثبت سفارش
    public void showOrderGuide(String chatId) {

        baleBotClient.sendMessage(
                chatId,
                """
                        📦 ثبت سفارش
                        
                        لطفاً اطلاعات زیر را ارسال کنید:
                        
                        ۱. نام و نام خانوادگی
                        ۲. شماره تماس
                        ۳. نام محصول
                        ۴. تعداد موردنیاز
                        
                        فروشنده برای هماهنگی با شما تماس خواهد گرفت.
                        """
        );
    }

    // نمایش راه ارتباط با فروشنده
    public void showContactInfo(String chatId) {

        baleBotClient.sendMessage(
                chatId,
                """
                        📞 ارتباط با فروشنده
                        
                        برای استعلام قیمت و دریافت راهنمایی،
                        درخواست خود را ارسال کنید.
                        """
        );
    }

    // متد مشترک ارسال پیام همراه کیبورد
    private void sendMessage(
            String chatId,
            String text,
            List<List<String>> keyboard
    ) {

        Map<String, Object> replyMarkup = Map.of(
                "keyboard", keyboard,
                "resize_keyboard", true
        );

        baleBotClient.sendMessage(chatId, text, replyMarkup);
    }

    // نمایش جزئیات یک محصول


    public void showProductDetail(String chatId, Product product) {

        long totalStart = System.currentTimeMillis();

        List<List<Map<String, Object>>> inlineKeyboard = List.of(
                List.of(
                        Map.of(
                                "text", "📦 ثبت سفارش این محصول",
                                "callback_data", "product:order:" + product.id()
                        )
                ),
                List.of(
                        Map.of(
                                "text", "🔙 بازگشت به محصولات",
                                "callback_data", "category:" + product.categoryCode()
                        )
                )
        );

        Map<String, Object> replyMarkup = Map.of(
                "inline_keyboard", inlineKeyboard
        );

        String caption = """
                🔧 %s
                
                %s
                
                💰 قیمت: %s
                """.formatted(
                product.name(),
                product.description(),
                productPriceService.findCurrentPrice(product.id())
                        .map(BotMenuService::formatPrice)
                        .orElse("استعلام قیمت")
        );

        var image = new org.springframework.core.io.ClassPathResource(
                product.imagePath()
        );

        try {
            if (image.exists()) {

                // اندازه‌گیری زمان خواندن عکس
                long readStart = System.currentTimeMillis();

                byte[] photoBytes = image.getInputStream().readAllBytes();

                long readTime = System.currentTimeMillis() - readStart;

                log.info(
                        "خواندن عکس محصول {} در {} میلی‌ثانیه انجام شد. حجم: {} بایت",
                        product.name(),
                        readTime,
                        photoBytes.length
                );

                // اندازه‌گیری زمان ارسال عکس به بله
                long sendStart = System.currentTimeMillis();

                baleBotClient.sendPhoto(
                        chatId,
                        photoBytes,
                        image.getFilename(),
                        caption,
                        replyMarkup
                );

                long sendTime = System.currentTimeMillis() - sendStart;

                log.info(
                        "ارسال عکس محصول {} به بله {} میلی‌ثانیه طول کشید",
                        product.name(),
                        sendTime
                );

                /*long sendStart = System.currentTimeMillis();

                baleBotClient.sendMessage(chatId, caption, replyMarkup);

                log.info(
                        "ارسال پیام متنی به بله {} میلی‌ثانیه طول کشید",
                        System.currentTimeMillis() - sendStart
                );
*/
            } else {
                log.warn(
                        "عکس محصول {} پیدا نشد: {}",
                        product.name(),
                        product.imagePath()
                );

                baleBotClient.sendMessage(chatId, caption, replyMarkup);
            }

        } catch (Exception exception) {

            log.error(
                    "خطا در نمایش جزئیات محصول {}",
                    product.name(),
                    exception
            );

            baleBotClient.sendMessage(chatId, caption, replyMarkup);
        }

        log.info(
                "مجموع زمان نمایش جزئیات محصول {} برابر {} میلی‌ثانیه",
                product.name(),
                System.currentTimeMillis() - totalStart
        );
    }


    // نمایش راهنمای سفارش برای یک محصول مشخص
    public void showOrderGuide(String chatId, Product product) {

        baleBotClient.sendMessage(
                chatId,
                """
                        📦 ثبت سفارش
                        
                        محصول انتخاب‌شده: %s
                        
                        لطفاً اطلاعات زیر را ارسال کنید:
                        
                        ۱. نام و نام خانوادگی
                        ۲. شماره تماس
                        ۳. تعداد موردنیاز
                        
                        فروشنده برای هماهنگی با شما تماس خواهد گرفت.
                        """.formatted(product.name())
        );
    }

    public void showOrderConfirmation(
            String chatId,
            String message
    ) {

        List<List<Map<String, Object>>> inlineKeyboard = List.of(
                List.of(
                        Map.of(
                                "text", "✅ تأیید سفارش",
                                "callback_data", "order:confirm"
                        ),
                        Map.of(
                                "text", "❌ لغو سفارش",
                                "callback_data", "order:cancel"
                        )
                )
        );

        Map<String, Object> replyMarkup = Map.of(
                "inline_keyboard", inlineKeyboard
        );

        baleBotClient.sendMessage(
                chatId,
                message,
                replyMarkup
        );
    }
    private static String formatPrice(BigDecimal price) {
        return NumberFormat.getNumberInstance(Locale.forLanguageTag("fa-IR"))
                .format(price) + " تومان";
    }

}
```

## `customer/service/CustomerUpdateService.java`

```java
package ir.toolsmarket.tools_market_bot.customer.service;

import ir.toolsmarket.tools_market_bot.customer.menu.BotMenuOption;
import ir.toolsmarket.tools_market_bot.integration.bale.BaleBotClient;
import ir.toolsmarket.tools_market_bot.model.*;
import ir.toolsmarket.tools_market_bot.service.OrderSessionService;
import ir.toolsmarket.tools_market_bot.service.OrderStorageService;
import ir.toolsmarket.tools_market_bot.service.ProductCatalogService;
import ir.toolsmarket.tools_market_bot.service.ProductPriceService;
import ir.toolsmarket.tools_market_bot.customer.menu.BotMenuService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.util.List;

@Service
public class CustomerUpdateService {

    private static final Logger log =
            LoggerFactory.getLogger(CustomerUpdateService.class);

    private final BaleBotClient baleBotClient;
    private final ProductCatalogService productCatalogService;
    private final BotMenuService botMenuService;
    private final OrderSessionService orderSessionService;
    private final OrderStorageService orderStorageService;
    private final ProductPriceService productPriceService;

    private long nextUpdateId = 0;

    public CustomerUpdateService(
            @Qualifier("customerBaleBotClient")
            BaleBotClient baleBotClient,
            ProductCatalogService productCatalogService,
            BotMenuService botMenuService,
            OrderSessionService orderSessionService,
            OrderStorageService orderStorageService,
            ProductPriceService productPriceService
    ) {
        this.baleBotClient = baleBotClient;
        this.productCatalogService = productCatalogService;
        this.botMenuService = botMenuService;
        this.orderSessionService = orderSessionService;
        this.orderStorageService = orderStorageService;
        this.productPriceService = productPriceService;
    }

    @Scheduled(fixedDelay = 1000)
    public void receiveUpdates() {

        try {
            JsonNode response = baleBotClient.getUpdates(nextUpdateId);

            if (response == null || !response.path("ok").asBoolean()) {
                log.warn("دریافت آپدیت از بله ناموفق بود: {}", response);
                return;
            }

            for (JsonNode update : response.path("result")) {

                processUpdate(update);

                nextUpdateId =
                        update.path("update_id").asLong() + 1;
            }

        } catch (Exception exception) {
            log.error("خطا در دریافت پیام از بله", exception);
        }
    }

    private void processUpdate(JsonNode update) {

        JsonNode callbackQuery = update.path("callback_query");

        if (!callbackQuery.isMissingNode()) {
            processCallbackQuery(callbackQuery);
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

        log.info("پیام دریافتی از مشتری {}: {}", chatId, text);

        if (orderSessionService.getOrder(chatId) != null) {
            processOrderMessage(chatId, text);
            return;
        }

        if ("/start".equals(text)) {
            botMenuService.showMainMenu(chatId);
            return;
        }

        BotMenuOption menuOption =
                BotMenuOption.fromTitle(text).orElse(null);

        if (menuOption != null) {
            handleMenuOption(chatId, menuOption);
            return;
        }

        ProductCategory category =
                ProductCategory.fromTitle(text).orElse(null);

        if (category != null) {
            showProducts(chatId, category);
            return;
        }

        baleBotClient.sendMessage(
                chatId,
                "لطفاً یکی از گزینه‌های منو را انتخاب کنید."
        );
    }

    private void handleMenuOption(String chatId, BotMenuOption option) {

        switch (option) {

            case VIEW_PRODUCTS, BACK_TO_CATEGORIES -> botMenuService.showCategoriesMenu(chatId);

            case CREATE_ORDER -> botMenuService.showOrderGuide(chatId);

            case CONTACT_SELLER -> botMenuService.showContactInfo(chatId);

            case BACK_TO_MAIN, BACK_TO_MAIN_FROM_CATEGORY -> botMenuService.showMainMenu(chatId);
        }
    }

    private void showProducts(
            String chatId,
            ProductCategory category
    ) {

        List<Product> products =
                productCatalogService.findByCategory(category);

        botMenuService.showProducts(
                chatId,
                category,
                products
        );
    }

    // پردازش کلیک روی دکمه‌های Inline
    private void processCallbackQuery(JsonNode callbackQuery) {

        String callbackQueryId =
                callbackQuery.path("id").asText();

        String chatId = callbackQuery.path("message")
                .path("chat")
                .path("id").asText();

        String callbackData =
                callbackQuery.path("data").asText("");

        if (callbackQueryId.isBlank() || chatId.isBlank()) {
            return;
        }

        // متوقف کردن حالت انتظار دکمه در بله

        try {
            baleBotClient.answerCallbackQuery(callbackQueryId);
        } catch (Exception exception) {
            log.warn(
                    "پاسخ به Callback Query ناموفق بود. callbackQueryId: {}، علت: {}",
                    callbackQueryId,
                    exception.getMessage(),
                    exception
            );
        }


        log.info("Callback دریافتی از کاربر {}: {}", chatId, callbackData);

        String[] parts = callbackData.split(":");

        // تأیید یا لغو سفارش
        if ("order:confirm".equals(callbackData)) {

            OrderState orderState = orderSessionService.getOrder(chatId);

            if (orderState == null ||
                    orderSessionService.getStep(chatId) != OrderStep.WAITING_FOR_CONFIRMATION) {

                baleBotClient.sendMessage(
                        chatId,
                        "سفارش فعالی برای تأیید وجود ندارد."
                );
                return;
            }

            Product product = productCatalogService
                    .findById(orderState.getProductId())
                    .orElse(null);

            if (product == null) {
                baleBotClient.sendMessage(chatId, "محصول سفارش پیدا نشد.");
                orderSessionService.removeOrder(chatId);
                return;
            }

            Order order = new Order(
                    java.util.UUID.randomUUID().toString(),
                    chatId,
                    product.id(),
                    product.name(),
                    orderState.getQuantity(),
                    orderState.getCustomerName(),
                    orderState.getPhoneNumber(),
                    java.time.Instant.now(),
                    productPriceService.findCurrentPrice(product.id()).orElse(null)
            );

            try {
                // ذخیره سفارش در فایل JSON
                orderStorageService.save(order);

                orderSessionService.removeOrder(chatId);

                baleBotClient.sendMessage(
                        chatId,
                        """
                                ✅ سفارش شما با موفقیت ثبت شد.
                                
                                شماره سفارش:
                                %s
                                
                                فروشنده برای هماهنگی با شما تماس خواهد گرفت.
                                از اعتماد شما به ابزارمارکت سپاسگزاریم.
                                """.formatted(order.orderId())
                );

            } catch (Exception exception) {

                log.error("خطا در ذخیره سفارش", exception);

                baleBotClient.sendMessage(
                        chatId,
                        "متأسفانه ذخیره سفارش با خطا مواجه شد. لطفاً دوباره تلاش کنید."
                );
            }

            return;
        }

        if ("order:cancel".equals(callbackData)) {

            OrderState orderState = orderSessionService.getOrder(chatId);

            if (orderState == null) {
                baleBotClient.sendMessage(
                        chatId,
                        "سفارش فعالی برای لغو وجود ندارد."
                );
                return;
            }

            orderSessionService.removeOrder(chatId);

            baleBotClient.sendMessage(
                    chatId,
                    """
                            ❌ سفارش شما لغو شد.
                            
                            در صورت تمایل می‌توانید مجدداً محصول موردنظر خود را انتخاب کنید.
                            """
            );

            return;
        }

        if (parts.length == 3 && "product".equals(parts[0])) {

            Integer productId;

            try {
                productId = Integer.parseInt(parts[2]);
            } catch (NumberFormatException exception) {
                return;
            }

            Product product = productCatalogService.findById(productId)
                    .orElse(null);

            if (product == null) {
                baleBotClient.sendMessage(
                        chatId,
                        "محصول موردنظر پیدا نشد."
                );
                return;
            }

            switch (parts[1]) {
                case "detail" -> botMenuService.showProductDetail(chatId, product);

                case "order" -> {
                    orderSessionService.startOrder(chatId, product.id());

                    baleBotClient.sendMessage(
                            chatId,
                            """
                                    📦 ثبت سفارش %s
                                    
                                    لطفاً تعداد موردنیاز را به عدد وارد کنید.
                                    """.formatted(product.name())
                    );
                }

                default -> baleBotClient.sendMessage(
                        chatId,
                        "درخواست نامعتبر است."
                );
            }

            return;
        }

        if ("nav:categories".equals(callbackData)) {
            botMenuService.showCategoriesMenu(chatId);
            return;
        }

        if ("nav:main".equals(callbackData)) {
            botMenuService.showMainMenu(chatId);
            return;
        }

        if (callbackData.startsWith("category:")) {

            String categoryCode = parts[1];

            ProductCategory category =
                    java.util.Arrays.stream(ProductCategory.values())
                            .filter(item -> item.getCode().equals(categoryCode))
                            .findFirst()
                            .orElse(null);

            if (category != null) {
                showProducts(chatId, category);
            }
        }
    }

    private void processOrderMessage(String chatId, String text) {

        OrderState orderState = orderSessionService.getOrder(chatId);
        OrderStep step = orderSessionService.getStep(chatId);

        if (orderState == null || step == null) {
            return;
        }

        switch (step) {

            case WAITING_FOR_QUANTITY -> {
                try {
                    int quantity = Integer.parseInt(text);

                    if (quantity <= 0) {
                        baleBotClient.sendMessage(
                                chatId,
                                "تعداد باید عددی بزرگ‌تر از صفر باشد."
                        );
                        return;
                    }

                    orderState.setQuantity(quantity);

                    orderSessionService.setStep(
                            chatId,
                            OrderStep.WAITING_FOR_NAME
                    );

                    baleBotClient.sendMessage(
                            chatId,
                            "لطفاً نام و نام خانوادگی خود را وارد کنید."
                    );

                } catch (NumberFormatException exception) {
                    baleBotClient.sendMessage(
                            chatId,
                            "لطفاً تعداد را به‌صورت عدد وارد کنید."
                    );
                }
            }

            case WAITING_FOR_NAME -> {
                if (text.length() < 3) {
                    baleBotClient.sendMessage(
                            chatId,
                            "لطفاً نام و نام خانوادگی معتبر وارد کنید."
                    );
                    return;
                }

                orderState.setCustomerName(text);

                orderSessionService.setStep(
                        chatId,
                        OrderStep.WAITING_FOR_PHONE
                );

                baleBotClient.sendMessage(
                        chatId,
                        "لطفاً شماره تماس خود را وارد کنید."
                );
            }

            case WAITING_FOR_PHONE -> {
                String phone = text.replaceAll("[\\s-]", "");

                if (!phone.matches("^(09\\d{9}|\\+989\\d{9})$")) {
                    baleBotClient.sendMessage(
                            chatId,
                            "شماره تماس معتبر نیست. لطفاً شماره موبایل خود را وارد کنید."
                    );
                    return;
                }

                orderState.setPhoneNumber(phone);

                orderSessionService.setStep(
                        chatId,
                        OrderStep.WAITING_FOR_CONFIRMATION
                );

                showOrderConfirmation(chatId, orderState);
            }

            case WAITING_FOR_CONFIRMATION -> {
                baleBotClient.sendMessage(
                        chatId,
                        "لطفاً از دکمه‌های تأیید یا لغو سفارش استفاده کنید."
                );
            }
        }
    }

    private void showOrderConfirmation(
            String chatId,
            OrderState orderState
    ) {

        Product product = productCatalogService
                .findById(orderState.getProductId())
                .orElse(null);

        if (product == null) {
            baleBotClient.sendMessage(
                    chatId,
                    "محصول سفارش پیدا نشد."
            );

            orderSessionService.removeOrder(chatId);
            return;
        }

        String message = """
                🧾 خلاصه سفارش
                
                محصول: %s
                تعداد: %d
                نام مشتری: %s
                شماره تماس: %s
                
                قیمت: استعلام از فروشنده
                
                آیا سفارش را تأیید می‌کنید؟
                """.formatted(
                product.name(),
                orderState.getQuantity(),
                orderState.getCustomerName(),
                orderState.getPhoneNumber()
        );

        botMenuService.showOrderConfirmation(
                chatId,
                message
        );
    }
}
```

## `src/main/resources/application.yml`

```yaml
spring:
  application:
    name: tools-market-bot
server:
  port: 8081

tools-market:
  bale:
    base-url: https://tapi.bale.ai
    token: ${BALE_CUSTOMER_TOKEN:}
    seller-token: ${BALE_SELLER_TOKEN:}
    super-admin-chat-id: ${SUPER_ADMIN_CHAT_ID:}
  storage:
    product-prices-file: data/product-prices.json
```

## متغیرهای محیطی

```text
BALE_CUSTOMER_TOKEN=توکن_جدید_بات_مشتری
BALE_SELLER_TOKEN=توکن_جدید_بات_فروشنده
SUPER_ADMIN_CHAT_ID=شناسه_چت_سوپرادمین
```

## فایل داده

در اولین تغییر قیمت، فایل `data/product-prices.json` به‌صورت خودکار ساخته می‌شود. این فایل را داخل `src/main/resources` قرار نده؛ مسیر آن نسبت به مسیر اجرای برنامه است و باید در محیط اجرا قابل نوشتن باشد.

## نکات سازگاری

- کاربران قدیمی که `permissions` ندارند با مجموعه خالی بارگذاری می‌شوند.
- سفارش‌های قدیمی که `unitPriceSnapshot` ندارند، همچنان قابل خواندن‌اند و مقدار Snapshot آن‌ها `null` خواهد بود.
- `priceLabel` مدل Product حذف نشده است تا JSON فعلی محصولات تغییر نکند؛ قیمت فعال از فایل قیمت‌ها خوانده می‌شود.
- تاریخچه فقط برای سوپرادمین فعال می‌شود؛ مجوز `VIEW_PRICE_HISTORY` در `ensureSuperAdmin` تنظیم می‌شود.
- مجوز قیمت‌گذاری مستقل از نقش است و با `PRICE_MANAGER` کنترل می‌شود.
