package com.caseflow.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * 全局时间精度：一律精确到「天」（2026-10-18 用户要求）。
 *
 * <p>业务时间（报案/受案时间、期限、措施起止、待办截止、时间节点……）对使用者
 * 只需要知道哪一天，时分秒既没人看也容易选错。这里在 JSON 出入口统一收口：
 * <ul>
 *   <li><b>出</b>：{@link LocalDateTime} 序列化成 {@code yyyy-MM-dd}；</li>
 *   <li><b>入</b>：{@code yyyy-MM-dd} / {@code yyyy-MM-dd HH:mm} /
 *       {@code yyyy-MM-dd HH:mm:ss} / ISO {@code T} 分隔都能收，</li>
 * </ul>
 *
 * <p><b>入参为什么归零到当天 00:00</b>：如果保留调用方给的时分秒，
 * 「今天到期」的期限会按 {@code 今天 09:00} 判成已逾期（现在已过 09:00），
 * 所以统一截到当天零点，让所有天数差都是干净的整数天。
 *
 * <p><b>注意</b>：DTO 字段上不要再写 {@code @JsonFormat}——注解优先级高于这里，
 * 写死 {@code yyyy-MM-dd HH:mm:ss} 会让只传日期的请求 400。加时间字段直接裸声明
 * {@code LocalDateTime} 即可。
 *
 * <p>数据库列仍是 DATETIME（避免存量迁移），存量数据带时分秒也没关系：
 * 出参在这里截断，天数差一律按 {@code toLocalDate()} 计算。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer caseFlowDayPrecisionCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("CaseFlowDayPrecision");
            module.addSerializer(LocalDateTime.class, new DaySerializer());
            module.addDeserializer(LocalDateTime.class, new DayDeserializer());
            builder.modulesToInstall(module);
        };
    }

    /** LocalDateTime → yyyy-MM-dd */
    static class DaySerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider sp)
                throws IOException {
            if (value == null) {
                gen.writeNull();
                return;
            }
            gen.writeString(value.toLocalDate().format(DAY));
        }
    }

    /**
     * 宽松解析：所有受支持格式的前 10 位都是日期，因此取前 10 位解析 LocalDate，
     * 再归零到当天 00:00。这样既省去多套 formatter，也不会因为多了秒/毫秒而失败。
     */
    static class DayDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctx)
                throws IOException {
            if (p.hasToken(JsonToken.VALUE_NULL)) return null;
            String raw = p.getValueAsString();
            if (raw == null) return null;
            String s = raw.trim();
            if (s.isEmpty()) return null;
            // 纯时间戳（前端偶尔传毫秒）按东八区折算
            if (s.matches("\\d{10,13}")) {
                long v = Long.parseLong(s);
                if (s.length() == 10) v *= 1000L;
                return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(v),
                        java.time.ZoneId.of("GMT+8")).toLocalDate().atStartOfDay();
            }
            if (s.length() < 10) {
                throw ctx.weirdStringException(s, LocalDateTime.class,
                        "时间格式应为 yyyy-MM-dd");
            }
            try {
                return LocalDate.parse(s.substring(0, 10), DAY).atStartOfDay();
            } catch (DateTimeParseException e) {
                throw ctx.weirdStringException(s, LocalDateTime.class,
                        "时间格式应为 yyyy-MM-dd");
            }
        }
    }
}
