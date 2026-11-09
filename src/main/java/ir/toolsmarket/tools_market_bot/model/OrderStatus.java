package ir.toolsmarket.tools_market_bot.model;

public enum OrderStatus {
    WAITING_CUSTOMER, CANCELLED, EXPIRED, CONFIRMED, PREPARING, DELIVERED;

    public String title() {
        return switch (this) {
            case WAITING_CUSTOMER -> "در انتظار مشتری";
            case CANCELLED -> "لغو شده";
            case EXPIRED -> "منقضی شده";
            case CONFIRMED -> "تأیید شده";
            case PREPARING -> "در حال آماده‌سازی";
            case DELIVERED -> "تحویل شده";
        };
    }
}
