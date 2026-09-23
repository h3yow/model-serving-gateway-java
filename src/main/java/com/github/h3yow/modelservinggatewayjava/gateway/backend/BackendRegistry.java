package com.github.h3yow.modelservinggatewayjava.gateway.backend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 后端注册表：维护版本 -> 后端列表 的内存映射，线程安全。
 */
public class BackendRegistry {

    private final Map<String, CopyOnWriteArrayList<InferenceBackend>> byVersion =
            new ConcurrentHashMap<>();

    public void register(InferenceBackend backend) {
        byVersion.computeIfAbsent(backend.version(), k -> new CopyOnWriteArrayList<>())
                .add(backend);
    }

    /** 返回该版本的后端列表的不可变快照；未注册版本返回空列表。 */
    public List<InferenceBackend> backendsFor(String version) {
        CopyOnWriteArrayList<InferenceBackend> list = byVersion.get(version);
        if (list == null) {
            return Collections.emptyList();
        }
        return List.copyOf(list);
    }

    public boolean isVersionRegistered(String version) {
        CopyOnWriteArrayList<InferenceBackend> list = byVersion.get(version);
        return list != null && !list.isEmpty();
    }

    public List<String> registeredVersions() {
        List<String> versions = new ArrayList<>(byVersion.keySet());
        Collections.sort(versions);
        return versions;
    }

    /** 按 id 查找后端（管理接口注入失败用）。 */
    public InferenceBackend findById(String backendId) {
        for (List<InferenceBackend> list : byVersion.values()) {
            for (InferenceBackend b : list) {
                if (b.id().equals(backendId)) {
                    return b;
                }
            }
        }
        return null;
    }
}
