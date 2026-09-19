package com.mars.cloud.mvc.error;

import com.mars.cloud.common.error.ErrorCode;
import com.mars.cloud.mvc.env.ErrorCodeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 错误码区间校验器。
 *
 * <p>把「错误码落在自己区间内」这件事从运行期提前到启动期，并顺带拦下区间重叠、
 * 归属名写错这类配置事故。四类校验：
 *
 * <ol>
 *   <li><b>配置自检</b>：区间起止合法、归属名在权威分配表中存在、声明的区间与分配表一致、
 *       多条声明之间不重叠</li>
 *   <li><b>归属校验</b>：每个错误码的前缀决定它属于哪一层，声明里必须启用那一层</li>
 *   <li><b>越界校验</b>：错误码必须落在其归属区间内</li>
 *   <li><b>重复校验</b>：同一错误码不能被注册两次</li>
 * </ol>
 *
 * @since 2025-10-29 14:52
 */
@RequiredArgsConstructor
public class ErrorCodeRangeValidator implements SmartInitializingSingleton {

    /**
     * Spring 会自动注入项目中所有实现了 ErrorCodeRegistrar 的 Bean
     */
    private final List<ErrorCodeRegistrar> registrars;

    private final ErrorCodeProperties props;

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, List<FrameworkErrorCodeRange.Range>> effective = resolveRanges();
        Set<Integer> seen = new HashSet<>();

        for (ErrorCodeRegistrar registrar : registrars) {
            for (ErrorCode code : registrar.codes()) {
                int value = code.getCode();

                String owner = FrameworkErrorCodeRange.ownerOf(value);
                if (owner == null) {
                    throw new IllegalStateException(String.format(
                            "错误码 %d 不在框架分配表的任何区段内，请检查码值是否合法", value));
                }

                List<FrameworkErrorCodeRange.Range> declared = effective.get(owner);
                if (declared == null || declared.isEmpty()) {
                    throw new IllegalStateException(String.format(
                            "错误码 %d 属于 [%s] 区段，但该区段未启用；请在 mars.error-code.framework-layers "
                                    + "或 mars.error-code.ranges 中声明 %s", value, owner, owner));
                }

                // 同一归属可能声明多段（多个业务服务共用一个区段），命中任意一段即可
                if (declared.stream().noneMatch(range -> range.contains(value))) {
                    throw new IllegalStateException(String.format(
                            "错误码 %d 不在 [%s] 已声明的任何区间内；已声明: %s",
                            value, owner, declared));
                }

                if (!seen.add(value)) {
                    throw new IllegalStateException(String.format("错误码重复注册: %d", value));
                }
            }
        }
    }

    /**
     * 合并「框架层」与「业务声明」两类区间，并做配置自检。
     */
    private Map<String, List<FrameworkErrorCodeRange.Range>> resolveRanges() {
        Map<String, List<FrameworkErrorCodeRange.Range>> effective = new LinkedHashMap<>();

        for (String layer : props.getFrameworkLayers()) {
            FrameworkErrorCodeRange.Range range = FrameworkErrorCodeRange.rangeOf(layer);
            if (range == null) {
                throw new IllegalStateException(String.format(
                        "mars.error-code.framework-layers 里的 [%s] 不是框架层；可选值: %s",
                        layer, FrameworkErrorCodeRange.owners()));
            }
            effective.computeIfAbsent(layer, key -> new ArrayList<>()).add(range);
        }

        for (ErrorCodeProperties.RangeDeclaration declaration : props.getRanges()) {
            String owner = declaration.getOwner();
            FrameworkErrorCodeRange.Range authoritative = FrameworkErrorCodeRange.rangeOf(owner);
            if (authoritative == null) {
                throw new IllegalStateException(String.format(
                        "mars.error-code.ranges 里的归属名 [%s] 不存在；可选值: %s",
                        owner, FrameworkErrorCodeRange.owners()));
            }
            if (declaration.getStart() > declaration.getEnd()) {
                throw new IllegalStateException(String.format(
                        "[%s] 的区间起止写反了: [%d, %d]", owner, declaration.getStart(), declaration.getEnd()));
            }
            if (declaration.getStart() < authoritative.start() || declaration.getEnd() > authoritative.end()) {
                throw new IllegalStateException(String.format(
                        "[%s] 声明的区间 [%d, %d] 超出该归属的分配区段 [%d, %d]",
                        owner, declaration.getStart(), declaration.getEnd(),
                        authoritative.start(), authoritative.end()));
            }
            effective.computeIfAbsent(owner, key -> new ArrayList<>()).add(
                    new FrameworkErrorCodeRange.Range(owner, declaration.getStart(), declaration.getEnd()));
        }

        assertNoOverlap(effective.values());
        return effective;
    }

    /**
     * 段与段之间不得重叠。
     *
     * <p>这条不是冗余检查：多个业务服务共用 {@code business} 区段时，它们各自在区段内切一段，
     * 段之间的重叠只有这里能发现。
     */
    private void assertNoOverlap(java.util.Collection<List<FrameworkErrorCodeRange.Range>> grouped) {
        List<FrameworkErrorCodeRange.Range> all = new ArrayList<>();
        grouped.forEach(all::addAll);

        for (int i = 0; i < all.size(); i++) {
            for (int j = i + 1; j < all.size(); j++) {
                if (all.get(i).overlaps(all.get(j))) {
                    throw new IllegalStateException(String.format(
                            "错误码区间重叠: %s 与 %s", all.get(i), all.get(j)));
                }
            }
        }
    }
}
