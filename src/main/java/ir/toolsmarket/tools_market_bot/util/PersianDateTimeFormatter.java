package ir.toolsmarket.tools_market_bot.util;

import com.ibm.icu.text.SimpleDateFormat;
import com.ibm.icu.util.TimeZone;
import com.ibm.icu.util.ULocale;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

public final class PersianDateTimeFormatter {

    private static final ZoneId TEHRAN_ZONE =
            ZoneId.of("Asia/Tehran");

    private static final TimeZone ICU_TEHRAN_ZONE =
            TimeZone.getTimeZone("Asia/Tehran");

    private static final ULocale PERSIAN_LOCALE =
            new ULocale("fa_IR@calendar=persian;numbers=arabext");

    private PersianDateTimeFormatter() {
    }

    /**
     * تبدیل تاریخ به فرمت شمسی
     * مثال: ۱۴۰۵/۰۸/۱۸
     */
    public static String formatDate(LocalDateTime dateTime) {
        return format(dateTime, "yyyy/MM/dd");
    }

    /**
     * نمایش ساعت به فرمت ۲۴ ساعته
     * مثال: ۱۴:۳۰
     */
    public static String formatTime(LocalDateTime dateTime) {
        return format(dateTime, "HH:mm");
    }

    private static String format(
            LocalDateTime dateTime,
            String pattern
    ) {
        if (dateTime == null) {
            return "نامشخص";
        }

        SimpleDateFormat formatter =
                new SimpleDateFormat(pattern, PERSIAN_LOCALE);

        formatter.setTimeZone(ICU_TEHRAN_ZONE);

        Date date = Date.from(
                dateTime.atZone(TEHRAN_ZONE).toInstant()
        );

        return formatter.format(date);
    }
}