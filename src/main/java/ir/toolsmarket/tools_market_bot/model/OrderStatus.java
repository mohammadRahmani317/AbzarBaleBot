package ir.toolsmarket.tools_market_bot.model;

public enum OrderStatus {
    WAITING_CUSTOMER, CANCELLED, EXPIRED, CONFIRMED, PREPARING, DELIVERED, REJECTED;

    /** Display known historical labels without changing the original persisted lifecycle. */
    public static java.util.Optional<OrderStatus> fromStoredLabel(String value) {
        if (value == null) return java.util.Optional.empty();
        String normalized = value.replace("‌", "").replace(" ", "").trim();
        return java.util.Arrays.stream(values()).filter(status -> status.name().equalsIgnoreCase(value.trim())
                || status.title().replace(" ", "").equals(normalized)).findFirst();
    }

    public String title() {
        return switch (this) {
            case WAITING_CUSTOMER -> "در انتظار مشتری";
            case CANCELLED -> "لغو شده";
            case EXPIRED -> "منقضی شده";
            case CONFIRMED -> "تأیید شده";
            case PREPARING -> "در حال آماده‌سازی";
            case DELIVERED -> "تحویل شده";
            case REJECTED -> "رد شده";
        };
    }
}
