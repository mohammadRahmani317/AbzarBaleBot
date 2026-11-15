package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Service
public class ProductCatalogService {
    private final OrderStorageService storage;
    private final UserAccessService access;

    public ProductCatalogService(ObjectMapper mapper, OrderStorageService storage, UserAccessService access) {
        this.storage = storage;
        this.access = access;
        // Seed only once. Soft-deleted records remain in the map and must never be resurrected.
        if (storage.snapshot().products().isEmpty()) {
            try (var stream = new ClassPathResource("data/products.json").getInputStream()) {
                List<Product> seed = mapper.readValue(stream, new TypeReference<List<Product>>() {});
                storage.transact(state -> {
                    if (state.products().isEmpty()) {
                        for (Product product : seed) {
                            state.products().put(product.id(), product);
                            state.inventory().putIfAbsent(product.id(), new ProductInventory(product.id(), product.stockQuantity(), 0, null));
                        }
                    }
                    return null;
                });
            } catch (IOException exception) {
                throw new IllegalStateException("خطا در خواندن فایل محصولات", exception);
            }
        }
    }

    public List<Product> findAllIncludingDeleted() { return List.copyOf(storage.snapshot().products().values()); }
    public Optional<Product> findByIdIncludingDeleted(Integer id) { return Optional.ofNullable(storage.snapshot().products().get(id)); }

    public List<Product> findAll() {
        return storage.snapshot().products().values().stream().filter(p -> !p.deleted()).toList();
    }
    public List<Product> findByCategory(ProductCategory category) {
        return category == null ? List.of() : findAll().stream()
                .filter(product -> category.getCode().equals(product.categoryCode())).toList();
    }
    public Optional<Product> findById(Integer id) {
        return Optional.ofNullable(storage.snapshot().products().get(id)).filter(product -> !product.deleted());
    }
    public Product create(String actor, String category, String name, String brand, String description, String imagePath, int stockQuantity) {
        User user = access.requirePriceManager(actor);
        validate(category, name, brand, stockQuantity);
        return storage.transact(state -> {
            int id = Math.addExact(state.products().keySet().stream().mapToInt(Integer::intValue).max().orElse(0), 1);
            Product product = new Product(id, category, name.trim(), description, "استعلام قیمت", imagePath, brand.trim(), stockQuantity, false);
            state.products().put(id, product);
            Instant now = Instant.now();
            state.inventory().put(id, new ProductInventory(id, stockQuantity, 0, now));
            state.inventoryHistory().add(new InventoryChange(id, 0, stockQuantity, actor, user.name(), now));
            return product;
        });
    }
    public Product update(String actor, Integer id, String category, String name, String brand, String description, String imagePath) {
        access.requirePriceManager(actor);
        validate(category, name, brand, 0);
        return storage.transact(state -> {
            Product old = requireActive(state, id);
            Product product = new Product(id, category, name.trim(), description, old.priceLabel(), imagePath, brand.trim(), old.stockQuantity(), false);
            state.products().put(id, product);
            return product;
        });
    }
    public void delete(String actor, Integer id) {
        access.requirePriceManager(actor);
        storage.transact(state -> {
            state.products().put(id, requireActive(state, id).softDeleted());
            return null;
        });
    }
    static Product requireActive(OrderStore state, Integer id) {
        Product product = state.products().get(id);
        if (product == null || product.deleted()) throw new IllegalArgumentException("محصول پیدا نشد یا حذف شده است.");
        return product;
    }
    private static void validate(String category, String name, String brand, int stock) {
        if (Arrays.stream(ProductCategory.values()).noneMatch(c -> c.getCode().equals(category)))
            throw new IllegalArgumentException("دسته‌بندی نامعتبر است.");
        if (name == null || name.isBlank() || brand == null || brand.isBlank())
            throw new IllegalArgumentException("نام و برند محصول الزامی است.");
        if (stock < 0) throw new IllegalArgumentException("موجودی نباید منفی باشد.");
    }
}
