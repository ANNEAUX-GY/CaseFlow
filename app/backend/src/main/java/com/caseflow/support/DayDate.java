package com.caseflow.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 「精确到天」的统一口径（2026-10-18）。
 *
 * <p>业务时间一律只到日：库里虽然还是 DATETIME 列，但写入时统一压到当天边界，
 * 让所有天数差都是干净的整数天：
 * <ul>
 *   <li>{@link #endOfDay} —— <b>期限类</b>（截止、办结、节点）：当天 23:59:59。
 *       若压成 00:00，今天到期的会被 {@code isBefore(now)} 判成已逾期。</li>
 *   <li>{@link #startOfDay} —— <b>事件类</b>（报案、措施开始、办案记录）：当天 00:00。</li>
 * </ul>
 *
 * <p>{@link #parseLenient} 收所有前端可能送来的写法：
 * {@code 2026-10-18} / {@code 2026-10-18 09:00} / {@code 2026-10-18 09:00:00} /
 * ISO {@code 2026-10-18T09:00:00} / 斜杠分隔 / 10~13 位时间戳。只看日期部分，
 * 时分秒一律忽略——既然精度到天，就不该因为多了几个字符报格式错误。
 */
public final class DayDate {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId ZONE = ZoneId.of("GMT+8");
    private static final Pattern EPOCH = Pattern.compile("\\d{10,13}");

    private DayDate() {
    }

    /** 当天的 23:59:59；null 进 null 出 */
    public static LocalDateTime endOfDay(LocalDateTime t) {
        return t == null ? null : t.toLocalDate().atTime(23, 59, 59);
    }

    /**
     * 仅当时间正好是 00:00:00（= 按天选出来、没有时分的值）才补成当天 23:59:59。
     * 历史数据里带具体时分的值原样保留——它们的天数差本来就按日期算，不必改写。
     */
    public static LocalDateTime endOfDayIfMidnight(LocalDateTime t) {
        if (t == null) return null;
        if (t.getHour() == 0 && t.getMinute() == 0 && t.getSecond() == 0) {
            return t.toLocalDate().atTime(23, 59, 59);
        }
        return t;
    }

    /** 当天的 00:00；null 进 null 出 */
    public static LocalDateTime startOfDay(LocalDateTime t) {
        return t == null ? null : t.toLocalDate().atStartOfDay();
    }

    /** 只取日期部分的分日键，如 2026-10-18 */
    public static String dayKey(LocalDateTime t) {
        return t == null ? null : t.toLocalDate().format(DAY);
    }

    /** 宽松解析：返回当天 00:00；空串或无法识别返回 null */
    public static LocalDateTime parseLenient(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        if (EPOCH.matcher(s).matches()) {
            long v = Long.parseLong(s);
            if (s.length() == 10) v *= 1000L;
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(v), ZONE)
                    .toLocalDate().atStartOfDay();
        }
        // 只看前 10 位：日期永远在最前面，后面跟什么（空格/T/时分秒）都不影响
        if (s.length() < 10) return null;
        try {
            return LocalDate.parse(s.substring(0, 10).replace('/', '-'), DAY).atStartOfDay();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 宽松解析 + 归到当天 23:59:59（期限类用） */
    public static LocalDateTime parseDeadline(String raw) {
        return endOfDay(parseLenient(raw));
    }

    /** 日志/提示里的展示格式：到天就够，不再带时分秒 */
    public static String text(LocalDateTime t) {
        return t == null ? "" : t.toLocalDate().format(DAY);
    }

    /** 兼容旧代码引用的完整格式（日志里确有需要精确到秒的场合再用） */
    public static String fullText(LocalDateTime t) {
        return t == null ? "" : t.format(FMT);
    }
}
