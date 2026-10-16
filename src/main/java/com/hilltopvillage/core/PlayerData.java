package com.hilltopvillage.core;

import org.bukkit.entity.Player;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerData {

    private final UUID playerId;
    private boolean smashActive;
    private double smashStartY;
    private long smashActivateTick;
    private double lastSmashY;
    private int kills;
    private int deaths;
    private int nodesRepaired;
    /** 本局从击杀掉落中获取的能量水晶累计（fix4 反馈6；由 HilltopVillagePlugin#onMobDeath 按掉落返回值累加） */
    private int currencyCollected;
    private boolean alive;

    public PlayerData(UUID playerId) {
        this.playerId = playerId;
        this.smashActive = false;
        this.smashStartY = 0.0;
        this.smashActivateTick = 0;
        this.kills = 0;
        this.deaths = 0;
        this.nodesRepaired = 0;
        this.currencyCollected = 0;
        this.alive = true;
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public boolean isSmashActive() {
        return smashActive;
    }

    public void activateSmash(double startY, long tick) {
        this.smashActive = true;
        this.smashStartY = startY;
        this.smashActivateTick = tick;
        this.lastSmashY = startY;
    }

    public void deactivateSmash() {
        this.smashActive = false;
        this.smashStartY = 0.0;
        this.smashActivateTick = 0;
        this.lastSmashY = 0;
    }

    public double getSmashStartY() {
        return smashStartY;
    }

    public long getSmashActivateTick() {
        return smashActivateTick;
    }

    public double getLastSmashY() { return lastSmashY; }
    public void setLastSmashY(double v) { this.lastSmashY = v; }
    public void setSmashActivateTick(long v) { this.smashActivateTick = v; }

    public int getKills() {
        return kills;
    }

    public void addKill() {
        this.kills++;
    }

    public int getDeaths() {
        return deaths;
    }

    public void addDeath() {
        this.deaths++;
    }

    public int getNodesRepaired() {
        return nodesRepaired;
    }

    public void addNodeRepaired() {
        this.nodesRepaired++;
    }

    public int getCurrencyCollected() {
        return currencyCollected;
    }

    /** 累加本次击杀掉落的能量水晶数（amount ≤ 0 时忽略） */
    public void addCurrencyCollected(int amount) {
        if (amount > 0) {
            this.currencyCollected += amount;
        }
    }

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }
}
