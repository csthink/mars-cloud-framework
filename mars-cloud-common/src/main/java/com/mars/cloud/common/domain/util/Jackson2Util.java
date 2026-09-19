package com.mars.cloud.common.domain.util;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * @since 2025-05-07 13:59
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Jackson2Util {

  private static final Logger log = LoggerFactory.getLogger(Jackson2Util.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final ObjectMapper OBJECT_MAPPER_IGNORE_BLANK = new ObjectMapper();
  private static final ObjectMapper OBJECT_MAPPER_STRICT = new ObjectMapper();

  static {
    DeserializationConfig dc = OBJECT_MAPPER.getDeserializationConfig();
    OBJECT_MAPPER.setConfig(dc.with(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")));
    OBJECT_MAPPER.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
    // jackson 序列化 bean 时，遇到 null 默认会报错，关闭此属性
    OBJECT_MAPPER.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    // JSON 字符串中含有我们并不需要的字段，那么当对应的实体类中不含有该字段时，会抛出一个异常，此设置不抛异常
    OBJECT_MAPPER.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    OBJECT_MAPPER.configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true);
    // 允许解析器可以解析没有加双引号的 json字段
    OBJECT_MAPPER.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
    // 允许解析器可以解析单引号的字符串或字符
    OBJECT_MAPPER.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
    // 默认只有和枚举名完全相同（包括大小写）的才能自动反序列化,例如枚举定义的是 TYPE 传入小写的 type 是无法自动反序列化的，会报错
    OBJECT_MAPPER.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);

    DeserializationConfig dc1 = OBJECT_MAPPER_IGNORE_BLANK.getDeserializationConfig();
    OBJECT_MAPPER_IGNORE_BLANK.setConfig(dc1.with(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")));
    OBJECT_MAPPER_IGNORE_BLANK.setVisibility(
            PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
    OBJECT_MAPPER_IGNORE_BLANK.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    OBJECT_MAPPER_IGNORE_BLANK.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    OBJECT_MAPPER_IGNORE_BLANK.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
    OBJECT_MAPPER_IGNORE_BLANK.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);
    // 设置 Jackson 序列化时只包含不为空的字段
    OBJECT_MAPPER_IGNORE_BLANK.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    OBJECT_MAPPER_IGNORE_BLANK.setSerializationInclusion(JsonInclude.Include.NON_EMPTY);
    OBJECT_MAPPER_IGNORE_BLANK.setTimeZone(TimeZone.getDefault());
    OBJECT_MAPPER_IGNORE_BLANK.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true);
  }

  public static String toJson(Object bean) {
    if (null == bean) {
      return StringUtils.EMPTY;
    }

    try {
      return OBJECT_MAPPER.writeValueAsString(bean);
    } catch (JsonProcessingException ex) {
      log.error("toJson error", ex);
      throw new RuntimeException(ex);
    }
  }

  public static String tryToJson(Object bean) {
    if (null == bean) {
      return StringUtils.EMPTY;
    }

    try {
      return OBJECT_MAPPER.writeValueAsString(bean);
    } catch (JsonProcessingException e) {
      log.error("tryToJson error", e);
      return StringUtils.EMPTY;
    }
  }

  public static String toJsonIgnoreBlank(Object bean) {
    if (null == bean) {
      return StringUtils.EMPTY;
    }

    try {
      return OBJECT_MAPPER_IGNORE_BLANK.writeValueAsString(bean);
    } catch (JsonProcessingException e) {
      log.error("toJsonIgnoreBlank error", e);
      throw new RuntimeException(e);
    }
  }

  public static String tryToJsonIgnoreBlank(Object bean) {
    if (null == bean) {
      return StringUtils.EMPTY;
    }

    try {
      return OBJECT_MAPPER_IGNORE_BLANK.writeValueAsString(bean);
    } catch (JsonProcessingException e) {
      log.error("tryToJsonIgnoreBlank error", e);
      return StringUtils.EMPTY;
    }
  }

  public static <T> T toBean(Map<String, String> map, Class<T> clazz) {
    if (null == map || map.isEmpty()) {
      return null;
    }

    try {
      return OBJECT_MAPPER.convertValue(map, clazz);
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
      return OBJECT_MAPPER.convertValue(map, clazz);
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
      return OBJECT_MAPPER.readValue(inputStream, clazz);
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
      return OBJECT_MAPPER.readValue(json, clazz);
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
      return OBJECT_MAPPER.readValue(json, clazz);
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
      return OBJECT_MAPPER_STRICT.readValue(json, clazz);
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
      return OBJECT_MAPPER.readValue(json, typeReference);
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
      JavaType javaType =
              OBJECT_MAPPER.getTypeFactory().constructParametricType(List.class, clazz);
      return OBJECT_MAPPER.readValue(json, javaType);
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
        valueType =
                OBJECT_MAPPER
                        .getTypeFactory()
                        .constructCollectionType(List.class, vClazz[1]);
      } else if (vClazz[0] == Set.class) {
        valueType =
                OBJECT_MAPPER
                        .getTypeFactory()
                        .constructCollectionType(Set.class, vClazz[1]);
      } else if (vClazz[0] == String.class) {
        valueType = OBJECT_MAPPER.getTypeFactory().constructType(vClazz[0]);
      } else if (vClazz[0] == Object.class) {
        valueType = OBJECT_MAPPER.getTypeFactory().constructType(vClazz[0]);
      } else if (vClazz[0] == Map.class) {
        return toMap(json, (Class<K>) vClazz[1], vClazz[2]);
      } else if (vClazz[0] == Integer.class) {
        valueType = OBJECT_MAPPER.getTypeFactory().constructType(vClazz[0]);
      } else {
        throw new RuntimeException(
                String.format("unsupported class type, %s, %s", kClazz, vClazz));
      }
      JavaType keyType = OBJECT_MAPPER.getTypeFactory().constructType(kClazz);
      JavaType mapType =
              OBJECT_MAPPER.getTypeFactory().constructMapType(Map.class, keyType, valueType);
      return OBJECT_MAPPER.readValue(json, mapType);
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
        valueType =
                OBJECT_MAPPER
                        .getTypeFactory()
                        .constructCollectionType(List.class, vClazz[1]);
      } else if (vClazz[0] == Set.class) {
        valueType =
                OBJECT_MAPPER
                        .getTypeFactory()
                        .constructCollectionType(Set.class, vClazz[1]);
      } else if (vClazz[0] == String.class) {
        valueType = OBJECT_MAPPER.getTypeFactory().constructType(vClazz[0]);
      } else if (vClazz[0] == Object.class) {
        valueType = OBJECT_MAPPER.getTypeFactory().constructType(vClazz[0]);
      } else if (vClazz[0] == Map.class) {
        return toMap(json, (Class<K>) vClazz[1], vClazz[2]);
      } else if (vClazz[0] == Integer.class) {
        valueType = OBJECT_MAPPER.getTypeFactory().constructType(vClazz[0]);
      } else {
        throw new RuntimeException(
                String.format("unsupported class type, %s, %s", kClazz, vClazz));
      }
      JavaType keyType = OBJECT_MAPPER.getTypeFactory().constructType(kClazz);
      JavaType mapType =
              OBJECT_MAPPER.getTypeFactory().constructMapType(Map.class, keyType, valueType);
      return OBJECT_MAPPER.readValue(json, mapType);
    } catch (Exception e) {
      log.error("toMap error", e);
      return new HashMap<>();
    }
  }

  public static Map<String, Object> toMap(Object obj) throws IllegalArgumentException {
    return OBJECT_MAPPER.convertValue(obj, new TypeReference<Map<String, Object>>() {
    });
  }
}
