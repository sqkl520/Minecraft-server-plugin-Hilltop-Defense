package com.hilltopvillage.admin;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.*;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class AdminDeployManager {

    private static final Particle[] PARTICLE_POOL = {
            Particle.FLAME, Particle.DRIP_LAVA, Particle.ENCHANTMENT_TABLE,
            Particle.END_ROD, Particle.GLOW, Particle.HEART,
            Particle.NOTE, Particle.PORTAL, Particle.SOUL_FIRE_FLAME,
            Particle.SPELL_WITCH, Particle.SPELL_MOB, Particle.VILLAGER_HAPPY,
            Particle.WAX_OFF, Particle.ELECTRIC_SPARK, Particle.SCRAPE,
            Particle.WARPED_SPORE, Particle.WATER_SPLASH, Particle.ASH,
            Particle.CRIMSON_SPORE, Particle.SOUL, Particle.COMPOSTER
    };

    private final HilltopVillagePlugin plugin;
    private final GameManager gameManager;
    private File spawnPointsFile;
    private FileConfiguration spawnPointsConfig;
    private final List<Location> savedSpawnPoints;
    private final Map<Integer, Particle> spawnPointParticles;
    private Location savedCoreLocation;
    private int showParticlesTaskId = -1;

    public AdminDeployManager(HilltopVillagePlugin plugin, GameManager gameManager) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.savedSpawnPoints = new ArrayList<>();
        this.spawnPointParticles = new LinkedHashMap<>();
        loadSpawnPoints();
    }

    /* ========== 粒子分配 ========== */

    private Particle assignParticle(int index) {
        return PARTICLE_POOL[index % PARTICLE_POOL.length];
    }

    public Particle getParticleFor(int index) {
        return spawnPointParticles.getOrDefault(index, assignParticle(index));
    }

    private void reassignAllParticles() {
        spawnPointParticles.clear();
        for (int i = 0; i < savedSpawnPoints.size(); i++) {
            spawnPointParticles.put(i, assignParticle(i));
        }
    }

    /* ========== 粒子展示 ========== */

    public void showSpawnPointParticles(Player viewer, LanguageManager lang) {
        showSpawnPointParticles(viewer, lang, 600L);
    }

    public void showSpawnPointParticles(Player viewer, LanguageManager lang, long durationTicks) {
        if (savedSpawnPoints.isEmpty()) {
            viewer.sendMessage(lang.getFor(viewer, "particles.no-spawnpoints"));
            return;
        }

        if (showParticlesTaskId != -1) {
            Bukkit.getScheduler().cancelTask(showParticlesTaskId);
        }

        viewer.sendMessage(lang.getFor(viewer, "particles.showing", savedSpawnPoints.size(), durationTicks / 20));
        viewer.sendMessage(lang.getFor(viewer, "particles.hint"));

        double radius = 3.0;

        showParticlesTaskId = new BukkitRunnable() {
            int ticksElapsed = 0;
            double angle = 0;

            @Override
            public void run() {
                if (ticksElapsed >= durationTicks) {
                    cancel();
                    showParticlesTaskId = -1;
                    return;
                }

                angle += Math.PI / 8;
                if (angle >= 2 * Math.PI) angle = 0;

                World world = gameManager.getGameWorld();

                for (int i = 0; i < savedSpawnPoints.size(); i++) {
                    Location loc = savedSpawnPoints.get(i);
                    Particle particle = getParticleFor(i);

                    double offsetAngle = angle + (i * (2 * Math.PI / Math.max(1, savedSpawnPoints.size())));
                    double r = radius * 0.4;
                    double px = loc.getX() + Math.cos(offsetAngle) * r;
                    double pz = loc.getZ() + Math.sin(offsetAngle) * r;
                    double py = loc.getY() + 0.5 + (ticksElapsed % 20) * 0.15;

                    // Main spiral particle
                    world.spawnParticle(particle, px, py, pz, 1, 0, 0, 0, 0);

                    // 4 corner particles + lines connecting them
                    double[][] corners = new double[4][3];
                    for (int j = 0; j < 4; j++) {
                        double edgeAngle = offsetAngle + (j * Math.PI / 2);
                        double ex = loc.getX() + Math.cos(edgeAngle) * radius;
                        double ez = loc.getZ() + Math.sin(edgeAngle) * radius;
                        double ey = loc.getY() + 0.3;
                        corners[j][0] = ex;
                        corners[j][1] = ey;
                        corners[j][2] = ez;
                        world.spawnParticle(particle, ex, ey, ez, 1, 0, 0, 0, 0);
                    }

                    // Draw lines between adjacent corners
                    for (int j = 0; j < 4; j++) {
                        int next = (j + 1) % 4;
                        drawLine(world, particle,
                                corners[j][0], corners[j][1], corners[j][2],
                                corners[next][0], corners[next][1], corners[next][2]);
                    }
                }

                ticksElapsed++;
            }
        }.runTaskTimer(plugin, 0L, 2L).getTaskId();
    }

    /** 在两点之间绘制粒子连线 */
    private void drawLine(World world, Particle particle,
                          double x1, double y1, double z1,
                          double x2, double y2, double z2) {
        int steps = 8;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = x1 + (x2 - x1) * t;
            double y = y1 + (y2 - y1) * t;
            double z = z1 + (z2 - z1) * t;
            world.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0);
        }
    }

    public void stopParticleShow() {
        if (showParticlesTaskId != -1) {
            Bukkit.getScheduler().cancelTask(showParticlesTaskId);
            showParticlesTaskId = -1;
        }
    }

    /* ========== 数据持久化 ========== */

    public void loadSpawnPoints() {
        spawnPointsFile = new File(plugin.getDataFolder(), "spawnpoints.yml");
        if (!spawnPointsFile.exists()) {
            plugin.saveResource("spawnpoints.yml", false);
        }
        spawnPointsConfig = YamlConfiguration.loadConfiguration(spawnPointsFile);

        savedSpawnPoints.clear();
        spawnPointParticles.clear();

        World world = Bukkit.getWorld(
                spawnPointsConfig.getString("world", gameManager.getGameWorld().getName()));
        if (world == null) {
            plugin.getLogger().warning("Spawn points world not found, deferring load.");
            return;
        }

        List<Map<?, ?>> list = spawnPointsConfig.getMapList("spawn-points");

        for (int i = 0; i < list.size(); i++) {
            Map<?, ?> map = list.get(i);
            double x = ((Number) map.get("x")).doubleValue();
            double y = ((Number) map.get("y")).doubleValue();
            double z = ((Number) map.get("z")).doubleValue();
            savedSpawnPoints.add(new Location(world, x, y, z));

            if (map.containsKey("particle")) {
                try {
                    Particle p = Particle.valueOf((String) map.get("particle"));
                    spawnPointParticles.put(i, p);
                } catch (IllegalArgumentException e) {
                    spawnPointParticles.put(i, assignParticle(i));
                }
            } else {
                spawnPointParticles.put(i, assignParticle(i));
            }
        }

        if (spawnPointsConfig.contains("core")) {
            double cx = spawnPointsConfig.getDouble("core.x");
            double cy = spawnPointsConfig.getDouble("core.y");
            double cz = spawnPointsConfig.getDouble("core.z");
            savedCoreLocation = new Location(world, cx, cy, cz);
        }
    }

    public void saveSpawnPoints() {
        spawnPointsConfig.set("world", gameManager.getGameWorld().getName());

        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < savedSpawnPoints.size(); i++) {
            Location loc = savedSpawnPoints.get(i);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("x", loc.getX());
            map.put("y", loc.getY());
            map.put("z", loc.getZ());
            map.put("particle", spawnPointParticles.getOrDefault(i, assignParticle(i)).name());
            list.add(map);
        }
        spawnPointsConfig.set("spawn-points", list);

        if (savedCoreLocation != null) {
            spawnPointsConfig.set("core.x", savedCoreLocation.getX());
            spawnPointsConfig.set("core.y", savedCoreLocation.getY());
            spawnPointsConfig.set("core.z", savedCoreLocation.getZ());
        }

        try {
            spawnPointsConfig.save(spawnPointsFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save spawnpoints.yml: " + e.getMessage());
        }
    }

    /* ========== 生成点操作 ========== */

    public List<Location> getSpawnPoints() {
        return Collections.unmodifiableList(savedSpawnPoints);
    }

    public Location getCoreLocation() {
        return savedCoreLocation != null ? savedCoreLocation.clone() : null;
    }

    public boolean hasFixedSpawnPoints() {
        return !savedSpawnPoints.isEmpty();
    }

    public void addSpawnPoint(Location location) {
        savedSpawnPoints.add(location.clone());
        spawnPointParticles.put(savedSpawnPoints.size() - 1, assignParticle(savedSpawnPoints.size() - 1));
        saveSpawnPoints();
    }

    public boolean removeSpawnPoint(int index) {
        if (index < 0 || index >= savedSpawnPoints.size()) return false;
        savedSpawnPoints.remove(index);
        spawnPointParticles.remove(index);
        reassignAllParticles();
        saveSpawnPoints();
        return true;
    }

    public void clearSpawnPoints() {
        savedSpawnPoints.clear();
        spawnPointParticles.clear();
        saveSpawnPoints();
    }

    public void setCoreLocation(Location location) {
        savedCoreLocation = location.clone();
        saveSpawnPoints();
    }

    /* ========== 部署菜单 ========== */

    public void openDeployMenu(Player admin, LanguageManager lang) {
        if (!admin.hasPermission("hilltopvillage.admin")) {
            admin.sendMessage(lang.getFor(admin, "deploy.menu-no-permission"));
            return;
        }

        admin.sendMessage("");
        admin.sendMessage(lang.getFor(admin, "deploy.menu-title-line1"));
        admin.sendMessage("");

        admin.sendMessage(lang.getFor(admin, "deploy.menu-status-label"));
        admin.sendMessage(lang.getFor(admin, "deploy.menu-state", getStateDisplay(admin, lang, gameManager.getState())));
        admin.sendMessage(lang.getFor(admin, "deploy.menu-core-coords", formatCoreLocation(admin, lang)));
        admin.sendMessage(lang.getFor(admin, "deploy.menu-spawn-count",
                savedSpawnPoints.size(),
                savedSpawnPoints.isEmpty() ? lang.getFor(admin, "deploy.menu-random-yes") : lang.getFor(admin, "deploy.menu-random-no")));
        admin.sendMessage(lang.getFor(admin, "deploy.menu-nodes", gameManager.getNodeSystem().getTotalCount()));
        admin.sendMessage("");

        admin.sendMessage(lang.getFor(admin, "deploy.menu-operations-label"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy setcore" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-setcore"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy addspawn" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-addspawn"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy listspawns" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-listspawns"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy delspawn <id>" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-delspawn"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy showparticles" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-showparticles"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy clearspawns" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-clearspawns"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy reloadnodes" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-reloadnodes"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop deploy confirmclear" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-confirmclear"));

        admin.sendMessage("");
        admin.sendMessage(lang.getFor(admin, "deploy.menu-game-control-label"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop start" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-start"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop stop" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-stop"));
        admin.sendMessage(ChatColor.AQUA + "/hilltop status" + ChatColor.GRAY + " — " + lang.getFor(admin, "deploy.menu-hint-status"));

        admin.sendMessage("");
        admin.sendMessage(lang.getFor(admin, "deploy.menu-tip"));
    }

    private String getStateDisplay(Player admin, LanguageManager lang, GameState state) {
        switch (state) {
            case WAITING: return lang.getFor(admin, "state.waiting");
            case STARTING: return lang.getFor(admin, "state.starting");
            case RUNNING: return lang.getFor(admin, "state.running");
            case WAVE_INTERMISSION: return lang.getFor(admin, "state.wave-intermission");
            case VICTORY: return lang.getFor(admin, "state.victory");
            case DEFEAT: return lang.getFor(admin, "state.defeat");
            default: return state.name();
        }
    }

    private String formatCoreLocation(Player admin, LanguageManager lang) {
        Location core = savedCoreLocation != null ? savedCoreLocation : gameManager.getCoreLocation();
        if (core == null) return lang.getFor(admin, "deploy.menu-core-unset");
        return ChatColor.WHITE + String.format("%.1f, %.1f, %.1f", core.getX(), core.getY(), core.getZ());
    }
}