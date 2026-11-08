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
            @Value("${tools-market.storage.product-prices-file:data/product-prices.json}")
            String filePath
    ) {
        this.objectMapper = objectMapper;
        this.filePath = Path.of(filePath);
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

    public synchronized List<ProductPriceHistory> findHistory(Integer productId) {
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
        Path parent = filePath.toAbsolutePath().getParent();

        Path temporaryFile = filePath.resolveSibling(
                filePath.getFileName() + ".tmp"
        );

        try {
            Files.createDirectories(parent);

            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temporaryFile.toFile(), store);

            try {
                Files.move(
                        temporaryFile,
                        filePath,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );

            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporaryFile,
                        filePath,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

        } catch (IOException exception) {

            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException ignored) {
            }

            throw new IllegalStateException(
                    "خطا در ذخیره اطلاعات قیمت محصولات",
                    exception
            );
        }
    }
}