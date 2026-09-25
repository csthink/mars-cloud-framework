package com.mars.cloud.sentinel;

import com.mars.cloud.sentinel.rule.RuleConfigSource;
import com.mars.cloud.sentinel.rule.RuleType;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 用例用的规则来源：内容存在内存里，{@link #publish} 与 {@link #delete} 像 Nacos 一样通知监听器。
 */
public final class InMemoryRuleConfigSource implements RuleConfigSource {

    private final Map<String, String> contents = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();
    private volatile RuntimeException readFailure;

    public InMemoryRuleConfigSource put(String dataId, String content) {
        contents.put(dataId, content);
        return this;
    }

    public void publish(String dataId, String content) {
        contents.put(dataId, content);
        listeners.getOrDefault(dataId, List.of()).forEach(listener -> listener.accept(content));
    }

    public void delete(String dataId) {
        contents.remove(dataId);
        listeners.getOrDefault(dataId, List.of()).forEach(listener -> listener.accept(null));
    }

    public void failReads(RuntimeException failure) {
        this.readFailure = failure;
    }

    public int listenerCount(String dataId) {
        return listeners.getOrDefault(dataId, List.of()).size();
    }

    @Override
    public String read(String dataId, String group, Duration timeout) {
        if (readFailure != null) {
            throw readFailure;
        }
        if (!RuleType.GROUP.equals(group)) {
            return null;
        }
        return contents.get(dataId);
    }

    @Override
    public Registration listen(String dataId, String group, Consumer<String> listener) {
        listeners.computeIfAbsent(dataId, key -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> listeners.getOrDefault(dataId, List.of()).remove(listener);
    }
}
