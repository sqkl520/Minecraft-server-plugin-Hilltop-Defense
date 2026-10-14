package com.hilltopvillage.arena;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** In-memory, insertion-ordered registry of arena definitions. */
public final class ArenaRegistry {

    private final Map<String, ArenaDefinition> arenas = new LinkedHashMap<>();
    private String defaultArenaId;

    /** Registers an arena and rejects an ID already present in the registry. */
    public void register(ArenaDefinition arena) {
        if (arena == null) throw new IllegalArgumentException("Arena must not be null");
        String key = normalizeId(arena.getId());
        if (arenas.containsKey(key)) {
            throw new IllegalArgumentException("Arena ID is already registered: " + arena.getId());
        }
        arenas.put(key, arena);
    }

    public ArenaDefinition get(String id) {
        return id == null ? null : arenas.get(normalizeId(id));
    }

    public ArenaDefinition remove(String id) {
        if (id == null) return null;
        ArenaDefinition removed = arenas.remove(normalizeId(id));
        if (removed != null && normalizeId(removed.getId()).equals(defaultArenaId)) {
            defaultArenaId = null;
        }
        return removed;
    }

    /** Returns arenas in registration order without exposing the internal map. */
    public List<ArenaDefinition> list() {
        return Collections.unmodifiableList(new ArrayList<>(arenas.values()));
    }

    public String getDefaultArenaId() {
        return defaultArenaId;
    }

    public void setDefaultArenaId(String id) {
        if (id == null || id.isBlank()) {
            defaultArenaId = null;
            return;
        }
        String normalized = normalizeId(id);
        if (!arenas.containsKey(normalized)) {
            throw new IllegalArgumentException("Cannot set an unregistered default arena: " + id);
        }
        defaultArenaId = normalized;
    }

    public ArenaDefinition getByWorldName(String worldName) {
        if (worldName == null || worldName.isBlank()) return null;
        for (ArenaDefinition arena : arenas.values()) {
            // 忽略大小写（P2-29）：arenas.yml 的原始字符串与实际世界名大小写不一致时
            // （Bukkit 世界查找本身不区分大小写，能正常加载与游玩），equals 会让
            // /hilltop setup 起手解析误报 setup.no-arena-here。
            if (arena.getWorldName().equalsIgnoreCase(worldName)) return arena;
        }
        return null;
    }

    /** Removes all registered arenas and the default marker. */
    public void clear() {
        arenas.clear();
        defaultArenaId = null;
    }

    public int size() {
        return arenas.size();
    }

    private static String normalizeId(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Arena ID must not be blank");
        }
        return id.toLowerCase(Locale.ROOT);
    }
}
