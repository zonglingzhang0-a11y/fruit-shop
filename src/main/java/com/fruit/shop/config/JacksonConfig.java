package com.fruit.shop.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * JSON 时间口径（docs/01-技术方案.md 第 5 节）：日期 {@code yyyy-MM-dd}，时间 {@code yyyy-MM-dd HH:mm:ss}，时区 GMT+8。
 *
 * <p>Spring Boot 的 {@code spring.jackson.date-format} 只作用于 {@code java.util.Date}，
 * 不影响 JSR-310 的 LocalDate/LocalDateTime，所以这里显式注册序列化器；
 * 反序列化刻意做成宽松的：{@code 2024-05-01 09:00:00} 与 ISO 的 {@code 2024-05-01T09:00:00} 都收。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer fruitShopDateTimeCustomizer() {
        SimpleModule module = new SimpleModule("fruit-shop-datetime");
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME));
        module.addDeserializer(LocalDateTime.class, new LenientLocalDateTimeDeserializer());
        module.addSerializer(LocalDate.class, new LocalDateSerializer(DateTimeFormatter.ISO_LOCAL_DATE));
        module.addDeserializer(LocalDate.class, new LocalDateDeserializer(DateTimeFormatter.ISO_LOCAL_DATE));
        return builder -> builder.modulesToInstall(module);
    }

    /** 宽松反序列化：接受 "yyyy-MM-dd HH:mm:ss"、"yyyy-MM-ddTHH:mm:ss" 以及只给日期的写法。 */
    static class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String raw = parser.getValueAsString();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String value = raw.trim().replace('T', ' ');
            try {
                if (value.length() <= 10) {
                    return LocalDate.parse(value.substring(0, 10)).atStartOfDay();
                }
                if (value.length() == 16) {
                    value = value + ":00";
                }
                return LocalDateTime.parse(value, DATE_TIME);
            } catch (DateTimeParseException e) {
                return LocalDateTime.parse(raw.trim());
            }
        }
    }
}
