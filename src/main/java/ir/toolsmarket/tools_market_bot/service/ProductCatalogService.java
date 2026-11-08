package ir.toolsmarket.tools_market_bot.service;

import ir.toolsmarket.tools_market_bot.model.Product;
import ir.toolsmarket.tools_market_bot.model.ProductCategory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Service
public class ProductCatalogService {

    private final List<Product> products;

    public ProductCatalogService(ObjectMapper objectMapper) {

        try {
            ClassPathResource resource =
                    new ClassPathResource("data/products.json");

            this.products = objectMapper.readValue(
                    resource.getInputStream(),
                    new TypeReference<List<Product>>() {}
            );

        } catch (IOException exception) {
            throw new IllegalStateException(
                    "خطا در خواندن فایل محصولات",
                    exception
            );
        }
    }

    /**
     * دریافت تمام محصولات
     * مورد استفاده در مدیریت قیمت محصولات
     */
    public List<Product> findAll() {
        return products;
    }

    /**
     * دریافت محصولات یک دسته‌بندی
     */
    public List<Product> findByCategory(ProductCategory category) {

        if (category == null) {
            return List.of();
        }

        return products.stream()
                .filter(product ->
                        product.categoryCode() != null &&
                                product.categoryCode().equals(category.getCode())
                )
                .toList();
    }

    /**
     * دریافت محصول بر اساس شناسه
     */
    public Optional<Product> findById(Integer id) {

        if (id == null) {
            return Optional.empty();
        }

        return products.stream()
                .filter(product -> product.id().equals(id))
                .findFirst();
    }
}