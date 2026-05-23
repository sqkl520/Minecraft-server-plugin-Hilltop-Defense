package com.hilltopvillage.core;

import org.bukkit.entity.Player;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerData {

    private final UUID playerId;
    private boolean smashActive;
    private double smashStartY;
    private long smashActivateTick;
    private int kills;
    private int deaths;
    private int nodesRepaired;
    private boolean alive;

    public PlayerData(UUID playerId) {
        this.playerId = playerId;
        this.smashActive = false;
        this.smashStartY = 0.0;
        this.smashActivateTick = 0;
        this.kills = 0;
        this.deaths = 0;
        this.nodesRepaired = 0;
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
    }

    public void deactivateSmash() {
        this.smashActive = false;
        this.smashStartY = 0.0;
        this.smashActivateTick = 0;
    }

    public double getSmashStartY() {
        return smashStartY;
    }

    public long getSmashActivateTick() {
        return smashActivateTick;
    }

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

    public boolean isAlive() {
        return alive;
    }

    public void setAlive(boolean alive) {
        this.alive = alive;
    }
}
