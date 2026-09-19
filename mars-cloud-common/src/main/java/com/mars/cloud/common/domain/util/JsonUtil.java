package com.mars.cloud.common.domain.util;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * JSON 工具类（Jackson 3）。
 *
 * <p>Jackson 3 与 2 的差别不只是包名：{@code ObjectMapper} 不再支持 {@code setConfig} /
 * {@code configure} 一类的可变配置，改为 {@code JsonMapper.builder()} 一次性构建；
 * 受检异常基类也从 {@code JsonProcessingException} 变为 {@code JacksonException}。
 * 因此本类以 builder 方式构建三个预配置实例。
 *
 * <p>三个实例的分工：
 * <ul>
 *   <li>{@code MAPPER}：默认实例。字段级可见、忽略未知字段、宽容的 JSON 语法（无引号字段名、单引号）</li>
 *   <li>{@code MAPPER_IGNORE_BLANK}：序列化时排除 null 与空值</li>
 *   <li>{@code MAPPER_STRICT}：不做宽容解析，用于需要严格校验的场合</li>
 * </ul>
 *
 * @since 2025-05-07 13:59
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class JsonUtil {

    private static final Logger log = LoggerFactory.getLogger(JsonUtil.class);

    private static final ObjectMapper MAPPER = buildDefault();

    private static final ObjectMapper MAPPER_IGNORE_BLANK = buildIgnoreBlank();

    private static final ObjectMapper MAPPER_STRICT = buildStrict();

    private static ObjectMapper buildDefault() {
        return JsonMapper.builder()
                .changeDefaultVisibility(checker -> checker.withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY))
                .defaultDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"))
                // 序列化 bean 时遇到没有可访问属性的对象不报错
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                // JSON 中含有实体类没有的字段时不报错
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // 注：Jackson 2 的 READ_UNKNOWN_ENUM_VALUES_AS_NULL 在 Jackson 3 已移除，
                // 新版默认对未知枚举值更宽容，此处不再需要显式开关
                // 允许没有双引号的字段名与单引号字符串
                .configure(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES, true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES, true)
                // 枚举名大小写不敏感
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
                .build();
    }

    private static ObjectMapper buildIgnoreBlank() {
        return JsonMapper.builder()
                .changeDefaultVisibility(checker -> checker.withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY))
                .defaultDateFormat(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss"))
                .defaultTimeZone(TimeZone.getDefault())
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .configure(JsonReadFeature.ALLOW_UNQUOTED_PROPERTY_NAMES, true)
                .configure(JsonReadFeature.ALLOW_SINGLE_QUOTES, true)
                .configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
                // Jackson 3 用一个包含值替代原先的两次 setSerializationInclusion 调用
                .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(JsonInclude.Include.NON_EMPTY))
                .build();
    }

    private static ObjectMapper buildStrict() {
        return JsonMapper.builder().build();
    }

    public static String toJson(Object bean) {
        if (null == bean) {
            return StringUtils.EMPTY;
        }

        try {
            return MAPPER.writeValueAsString(bean);
        } catch (JacksonException ex) {
            log.error("toJson error", ex);
            throw new RuntimeException(ex);
        }
    }

    public static String tryToJson(Object bean) {
        if (null == bean) {
            return StringUtils.EMPTY;
        }

        try {
            return MAPPER.writeValueAsString(bean);
        } catch (JacksonException e) {
            log.error("tryToJson error", e);
            return StringUtils.EMPTY;
        }
    }

    public static String toJsonIgnoreBlank(Object bean) {
        if (null == bean) {
            return StringUtils.EMPTY;
        }

        try {
            return MAPPER_IGNORE_BLANK.writeValueAsString(bean);
        } catch (JacksonException e) {
            log.error("toJsonIgnoreBlank error", e);
            throw new RuntimeException(e);
        }
    }

    public static String tryToJsonIgnoreBlank(Object bean) {
        if (null == bean) {
            return StringUtils.EMPTY;
        }

        try {
            return MAPPER_IGNORE_BLANK.writeValueAsString(bean);
        } catch (JacksonException e) {
            log.error("tryToJsonIgnoreBlank error", e);
            return StringUtils.EMPTY;
        }
    }

    public static <T> T toBean(Map<String, String> map, Class<T> clazz) {
        if (null == map || map.isEmpty()) {
            return null;
        }

        try {
            return MAPPER.convertValue(map, clazz);
        } catch (Exception e) {
            log.error("toBean error", e);
            throw new RuntimeException(e);
        }
    }

    public static <T> T toBean(Class<T> clazz, Map<String, Object> map) {
        if (null == map || map.isEmpty()) {
            return null;
        }

        try {
            return MAPPER.convertValue(map, clazz);
        } catch (Exception e) {
            log.error("toBean error", e);
            throw new RuntimeException(e);
        }
    }

    public static <T> T toBean(InputStream inputStream, Class<T> clazz) {
        if (null == inputStream) {
            return null;
        }

        try {
            return MAPPER.readValue(inputStream, clazz);
        } catch (Exception e) {
            log.error("toBean error", e);
            throw new RuntimeException(e);
        }
    }

    public static <T> T toBean(String json, Class<T> clazz) {
        if (StringUtils.isBlank(json)) {
            return null;
        }

        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            log.error("toBean error", e);
            throw new RuntimeException(e);
        }
    }

    public static <T> T tryToBean(String json, Class<T> clazz) {
        if (StringUtils.isBlank(json)) {
            return null;
        }

        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            log.error("toBean error", e);
            return null;
        }
    }

    public static <T> T tryToBeanStrict(String json, Class<T> clazz) {
        if (StringUtils.isBlank(json)) {
            return null;
        }

        try {
            return MAPPER_STRICT.readValue(json, clazz);
        } catch (Exception e) {
            log.error("toBean error", e);
            return null;
        }
    }

    public static <T> T toBean(String json, TypeReference<T> typeReference) {
        if (StringUtils.isBlank(json)) {
            return null;
        }

        try {
            return MAPPER.readValue(json, typeReference);
        } catch (Exception e) {
            log.error("toBean error", e);
            throw new RuntimeException(e);
        }
    }

    public static <T> List<T> toList(String json, Class<? super T> clazz) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }

        try {
            JavaType javaType = MAPPER.getTypeFactory().constructParametricType(List.class, clazz);
            return MAPPER.readValue(json, javaType);
        } catch (Exception e) {
            log.error("toList error", e);
            throw new RuntimeException(e);
        }
    }

    public static Map<String, Object> toMap(String json) {
        return toMap(json, String.class, Object.class);
    }

    public static <K, V> Map<K, V> toMap(String json, Class<K> kClazz, Class<?>... vClazz) {
        if (StringUtils.isBlank(json)) {
            return new HashMap<>();
        }

        try {
            JavaType valueType;
            if (vClazz[0] == List.class) {
                valueType = MAPPER.getTypeFactory().constructCollectionType(List.class, vClazz[1]);
            } else if (vClazz[0] == Set.class) {
                valueType = MAPPER.getTypeFactory().constructCollectionType(Set.class, vClazz[1]);
            } else if (vClazz[0] == String.class) {
                valueType = MAPPER.getTypeFactory().constructType(vClazz[0]);
            } else if (vClazz[0] == Object.class) {
                valueType = MAPPER.getTypeFactory().constructType(vClazz[0]);
            } else if (vClazz[0] == Map.class) {
                return toMap(json, (Class<K>) vClazz[1], vClazz[2]);
            } else if (vClazz[0] == Integer.class) {
                valueType = MAPPER.getTypeFactory().constructType(vClazz[0]);
            } else {
                throw new RuntimeException(String.format("unsupported class type, %s, %s", kClazz, vClazz));
            }
            JavaType keyType = MAPPER.getTypeFactory().constructType(kClazz);
            JavaType mapType = MAPPER.getTypeFactory().constructMapType(Map.class, keyType, valueType);
            return MAPPER.readValue(json, mapType);
        } catch (Exception e) {
            log.error("toMap error", e);
            throw new RuntimeException(e);
        }
    }

    public static <K, V> Map<K, V> tryToMap(String json, Class<K> kClazz, Class<?>... vClazz) {
        if (StringUtils.isBlank(json)) {
            return new HashMap<>();
        }

        try {
            JavaType valueType;
            if (vClazz[0] == List.class) {
                valueType = MAPPER.getTypeFactory().constructCollectionType(List.class, vClazz[1]);
            } else if (vClazz[0] == Set.class) {
                valueType = MAPPER.getTypeFactory().constructCollectionType(Set.class, vClazz[1]);
            } else if (vClazz[0] == String.class) {
                valueType = MAPPER.getTypeFactory().constructType(vClazz[0]);
            } else if (vClazz[0] == Object.class) {
                valueType = MAPPER.getTypeFactory().constructType(vClazz[0]);
            } else if (vClazz[0] == Map.class) {
                return toMap(json, (Class<K>) vClazz[1], vClazz[2]);
            } else if (vClazz[0] == Integer.class) {
                valueType = MAPPER.getTypeFactory().constructType(vClazz[0]);
            } else {
                throw new RuntimeException(String.format("unsupported class type, %s, %s", kClazz, vClazz));
            }
            JavaType keyType = MAPPER.getTypeFactory().constructType(kClazz);
            JavaType mapType = MAPPER.getTypeFactory().constructMapType(Map.class, keyType, valueType);
            return MAPPER.readValue(json, mapType);
        } catch (Exception e) {
            log.error("toMap error", e);
            return new HashMap<>();
        }
    }

    public static Map<String, Object> toMap(Object obj) throws IllegalArgumentException {
        return MAPPER.convertValue(obj, new TypeReference<Map<String, Object>>() {
        });
    }

    /**
     * 解析 JSON 字符串是否合法。
     */
    public static boolean isValid(String json) {
        if (StringUtils.isBlank(json)) {
            return false;
        }
        try {
            MAPPER.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
