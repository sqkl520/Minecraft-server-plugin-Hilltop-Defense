package com.hilltopvillage.config;

public enum ConfigPermission {
    ADMIN("hilltopvillage.config.admin", "All Config"),
    WAVES("hilltopvillage.config.waves", "Wave Config"),
    ITEMS("hilltopvillage.config.items", "Item & Hammer Config"),
    NODES("hilltopvillage.config.nodes", "Node Config"),
    MONSTERS("hilltopvillage.config.monsters", "Monster Config"),
    RULES("hilltopvillage.config.rules", "Game Rules");

    private final String node;
    private final String description;

    ConfigPermission(String node, String description) {
        this.node = node;
        this.description = description;
    }

    public String getNode() {
        return node;
    }

    public String getDescription() {
        return description;
    }
}