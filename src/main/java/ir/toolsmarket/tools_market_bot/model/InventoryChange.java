package ir.toolsmarket.tools_market_bot.model;

import java.time.Instant;

public record InventoryChange(Integer productId, int previousQuantity, int newQuantity,
                              String changedByChatId, String changedByName, Instant changedAt) { }
