package ir.toolsmarket.tools_market_bot.model;

/** Stock is projected from the inventory in the same persisted aggregate. */
public record Product(Integer id, String categoryCode, String name, String description,
                      String priceLabel, String imagePath, String brand, Integer stockQuantity, Boolean deleted) {
    public Product {
        stockQuantity = stockQuantity == null ? 0 : stockQuantity;
        deleted = Boolean.TRUE.equals(deleted);
        brand = brand == null ? "" : brand;
        description = description == null ? "" : description;
        imagePath = imagePath == null ? "" : imagePath;
        if (stockQuantity < 0) throw new IllegalArgumentException("موجودی نامعتبر است.");
    }
    public Product(Integer id, String categoryCode, String name, String description, String priceLabel, String imagePath) {
        this(id, categoryCode, name, description, priceLabel, imagePath, "", 0, false);
    }
    public Product withStock(int quantity) {
        return new Product(id, categoryCode, name, description, priceLabel, imagePath, brand, quantity, deleted);
    }
    public Product softDeleted() {
        return new Product(id, categoryCode, name, description, priceLabel, imagePath, brand, stockQuantity, true);
    }
}
