package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.ProductPriceHistory;
import ir.toolsmarket.tools_market_bot.model.ProductPriceStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final UserAccessService access;
    private final ProductCatalogService catalog;

    public ProductPriceService(
            ObjectMapper objectMapper,
            @Value("${tools-market.storage.product-prices-file:data/product-prices.json}")
            String filePath, UserAccessService access, ProductCatalogService catalog
    ) {
        this.objectMapper = objectMapper;
        this.filePath = Path.of(filePath);
        this.access = access;
        this.catalog = catalog;
    }

    public synchronized Optional<BigDecimal> findCurrentPrice(
            Integer productId
    ) {
        return Optional.ofNullable(
                readStore().prices().get(productId)
        );
    }

    public synchronized Map<Integer, BigDecimal> findAllCurrentPrices() {
        return readStore().prices();
    }

    public synchronized List<ProductPriceHistory> findHistory(Integer productId, String actor) {
        if (!access.hasPermission(actor, ir.toolsmarket.tools_market_bot.model.UserPermission.VIEW_PRICE_HISTORY))
            throw new IllegalArgumentException("فقط مدیر اصلی اجازه مشاهده تاریخچه قیمت را دارد.");
        return readStore().history().stream()
                .filter(item -> item.productId().equals(productId))
                .sorted(Comparator.comparing(ProductPriceHistory::changedAt))
                .toList();
    }

    /**
     * ثبت تغییر قیمت همراه با نام ثبت‌کننده
     */
    public synchronized boolean updatePrice(
            Integer productId,
            BigDecimal newPrice,
            String changedByChatId,
            String changedByName
    ) {
        access.requireOperator(changedByChatId);
        if (catalog.findById(productId).isEmpty()) throw new IllegalArgumentException("محصول پیدا نشد.");
        if (productId == null || newPrice == null
                || newPrice.signum() <= 0) {
            throw new IllegalArgumentException(
                    "قیمت باید عددی بزرگ‌تر از صفر باشد."
            );
        }

        if (newPrice.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(
                    "قیمت باید به تومان کامل وارد شود."
            );
        }

        ProductPriceStore current = readStore();

        BigDecimal previousPrice =
                current.prices().get(productId);

        if (newPrice.compareTo(
                previousPrice == null
                        ? BigDecimal.ZERO
                        : previousPrice
        ) == 0) {
            return false;
        }

        BigDecimal normalizedPrice = newPrice.setScale(0);

        Map<Integer, BigDecimal> prices =
                new HashMap<>(current.prices());

        prices.put(productId, normalizedPrice);

        List<ProductPriceHistory> history =
                new ArrayList<>(current.history());

        history.add(new ProductPriceHistory(
                UUID.randomUUID().toString(),
                productId,
                previousPrice,
                normalizedPrice,
                changedByChatId,
                changedByName,
                LocalDateTime.now(
                        ZoneId.of("Asia/Tehran")
                )
        ));

        writeStore(new ProductPriceStore(prices, history));

        return true;
    }

    /**
     * حفظ سازگاری با فراخوانی‌های قدیمی
     */
    public synchronized boolean updatePrice(
            Integer productId,
            BigDecimal newPrice,
            String changedByChatId
    ) {
        return updatePrice(
                productId,
                newPrice,
                changedByChatId,
                null
        );
    }

    private ProductPriceStore readStore() {
        if (Files.notExists(filePath)) {
            return ProductPriceStore.empty();
        }

        ProductPriceStore store =
                objectMapper.readValue(
                        filePath.toFile(),
                        ProductPriceStore.class
                );

        return store == null
                ? ProductPriceStore.empty()
                : store;
    }

    private void writeStore(ProductPriceStore store) {
        ir.toolsmarket.tools_market_bot.util.AtomicJsonFile.write(objectMapper, filePath, store);
    }
}
