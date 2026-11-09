package ir.toolsmarket.tools_market_bot.util;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;

public final class BotFormatting {
    private BotFormatting() { }
    public static String number(Number value) {
        return NumberFormat.getNumberInstance(Locale.forLanguageTag("fa-IR")).format(value);
    }
    public static String price(BigDecimal value) { return number(value) + " تومان"; }
    public static String digits(String text) {
        StringBuilder result = new StringBuilder();
        for (char c : text.toCharArray()) {
            int digit = Character.digit(c, 10);
            result.append(digit >= 0 ? (char) ('0' + digit) : c);
        }
        return result.toString();
    }
    public static int quantity(String text) {
        String value = digits(text).trim();
        if (!value.matches("[0-9]+")) throw new IllegalArgumentException("تعداد باید عدد صحیح و نامنفی باشد.");
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("تعداد بیش از حد بزرگ است."); }
    }
    public static String dateTime(Instant instant) {
        if (instant == null) return "—";
        var date = java.time.LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Tehran"));
        return "تاریخ: " + PersianDateTimeFormatter.formatDate(date)
                + " | ساعت: " + PersianDateTimeFormatter.formatTime(date);
    }
}
