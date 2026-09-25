package com.mars.cloud.sentinel.rule;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 规则 JSON 的严格解析：顶层必须是数组；未知字段、重复字段、类型不符、字符串冒充数字、小数冒充整数都拒绝。
 * 这一层拦住字段名拼错后被 Sentinel 当成默认值接受的情况。
 *
 * @since 2026-09-25
 */
public final class StrictJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    private StrictJson() {
    }

    /**
     * @throws RuleRejectedException JSON 不符合该规则类型的字段表，或数组里有 {@code null} 元素
     */
    public static <D> List<D> readList(String content, TypeReference<List<D>> type) {
        List<D> documents;
        try {
            documents = MAPPER.readValue(content, type);
        } catch (JacksonException ex) {
            throw new RuleRejectedException("JSON 不符合规则格式（" + location(ex) + "）：" + ex.getOriginalMessage(), ex);
        }
        if (documents == null) {
            throw new RuleRejectedException("顶层必须是规则数组，要清空规则请写 []");
        }
        for (int i = 0; i < documents.size(); i++) {
            if (documents.get(i) == null) {
                throw RuleFields.rejected(i, "为 null");
            }
        }
        return documents;
    }

    private static String location(JacksonException ex) {
        List<JacksonException.Reference> path = ex.getPath();
        if (path == null || path.isEmpty()) {
            return "顶层";
        }
        return path.stream()
                .map(reference -> reference.getPropertyName() != null
                        ? reference.getPropertyName()
                        : "第 " + (reference.getIndex() + 1) + " 条")
                .collect(Collectors.joining(" / "));
    }
}
