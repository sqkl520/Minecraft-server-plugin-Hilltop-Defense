package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameState;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 烈焰蛋系统（仿起床战争 Fireball）。
 * 右键投掷，沿飞行轨迹洒粒子，命中后爆炸并对周围怪物造成伤害。
 */
public class FireballListener implements Listener {

    // 爆炸伤害衰减常量
    private static final double FALLOFF_RANGE = 1.3;
    private static final double FALLOFF_MIN = 0.4;
    // 击退公式常量
    private static final double KB_HORIZONTAL_SCALE = 0.25;
    private static final double KB_Y_COMPENSATION = 1.5;
    private static final double KB_VERTICAL_SCALE = 0.45;

    /** 统一道具类型 PDC 键（D-3 PDC 防伪）：STRING 类型，值与 {@link #KIND_FIREBALL} 等对应。 */
    private static final String ITEM_KIND_KEY = "item-kind";
    /** item-kind 取值：烈焰蛋 */
    private static final String KIND_FIREBALL = "fireball";

    /** 全局唯一飞行 ticker：恢复原实现每 2 tick 一次的近炸检测语义。 */
    private static final int TRAIL_PERIOD_TICKS = 2;
    /** 高频火焰/烟雾粒子每 4 tick 输出一次，避免随检测频率一起增量。 */
    private static final int FLAME_SMOKE_PARTICLE_INTERVAL_TICKS = 4;
    /** 熔岩粒子每 6 tick 输出一次。 */
    private static final int LAVA_PARTICLE_INTERVAL_TICKS = 6;
    /** 原实现每次飞行计数代表 2 个服务器 tick，用于保持配置的历史等效寿命。 */
    private static final int LEGACY_TRAIL_PERIOD_TICKS = 2;

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;
    /** 统一道具类型键（构造期建好复用，同 WaveManager.airdropKey 模式） */
    private final NamespacedKey itemKindKey;
    private final Map<UUID, Integer> cooldowns;
    private BukkitRunnable cooldownTicker;
    private volatile boolean cooldownTickerRunning;
    /** 在飞的烈焰蛋：投射物 UUID → 飞行状态。全局共用一条 ticker 遍历 */
    private final Map<UUID, TrailState> activeTrails;
    private BukkitRunnable trailTicker;
    private volatile boolean trailTickerRunning;

    /**
     * 单个烈焰蛋飞行期间所需的配置子集。
     *
     * <p>原来这里存的是 {@code cfg.snapshot()} —— 一次深拷贝整份 GameConfig（含 monsters 与
     * waves，20+ 个对象），而实际只读 5 个标量。改用 record 后每发一弹只分配 1 个对象。</p>
     *
     * <p>仍然是「发射瞬间的快照」：与原实现一致，飞行途中不随 reloadconfig 改变
     * （原注释的理由保留：GameConfig 是可变对象且 reloadconfig 会原地改写字段）。</p>
     */
    private record FireballSnapshot(double explosionRadius, double damage, double knockback,
                                    double proximityFuse, int maxTravelTicks) {
        static FireballSnapshot of(GameConfig cfg) {
            return new FireballSnapshot(
                    cfg.getFireballExplosionRadius(),
                    cfg.getFireballDamage(),
                    cfg.getFireballKnockback(),
                    cfg.getFireballProximityFuse(),
                    cfg.getFireballMaxTravelTicks());
        }
    }

    /** 单个烈焰蛋的飞行状态（由 {@code tickTrails()} 遍历更新） */
    private static final class TrailState {
        /** 投射物本体 */
        private final Snowball projectile;
        /** 发射瞬间快照的配置（与原实现一致：飞行途中不随重载改变） */
        private final FireballSnapshot cfg;
        /** 失效阈值（服务器 tick）；使用 long 防止 max-travel-ticks × 2 溢出。 */
        private final long expireTicks;
        /** 已飞行服务器 tick 数 */
        private long ticks;

        private TrailState(Snowball projectile, GameConfig cfg) {
            this.projectile = projectile;
            // 必须存快照：GameConfig 是可变对象且 reloadconfig 会原地改写字段，
            // 直接持有引用会让飞行中的烈焰蛋读到重载后的新伤害/半径/击退。
            // 这里只抽取飞行期间真正会用到的 5 个标量，避免深拷贝整份 GameConfig
            // （monsters / waves 与本状态无关）。
            this.cfg = FireballSnapshot.of(cfg);
            // 寿命同样只从快照读取，保证 TrailState 内所有数值来自同一个不可变副本。
            int maxTravelTicks = this.cfg.maxTravelTicks();
            this.expireTicks = maxTravelTicks <= 0
                    ? 0L
                    : (long) maxTravelTicks * LEGACY_TRAIL_PERIOD_TICKS;
        }
    }

    public FireballListener(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.itemKindKey = new NamespacedKey(plugin, ITEM_KIND_KEY);
        this.cooldowns = new ConcurrentHashMap<>();
        this.cooldownTickerRunning = false;
        this.activeTrails = new ConcurrentHashMap<>();
        this.trailTickerRunning = false;
    }

    private void ensureCooldownTickerRunning() {
        if (cooldownTickerRunning) return;
        cooldownTickerRunning = true;
        cooldownTicker = new BukkitRunnable() {
            @Override
            public void run() {
                Iterator<Map.Entry<UUID, Integer>> it = cooldowns.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, Integer> entry = it.next();
                    int remaining = entry.getValue() - 2;
                    if (remaining <= 0) {
                        it.remove();
                    } else {
                        entry.setValue(remaining);
                    }
                }
                if (cooldowns.isEmpty()) {
                    cooldownTickerRunning = false;
                    cooldownTicker = null;
                    cancel();
                }
            }
        };
        cooldownTicker.runTaskTimer(plugin, 2L, 2L);
    }

    /* ========== 创建烈焰蛋物品 ========== */

    /**
     * 烈焰蛋显示名：实时从 activeConfig 读取（P2-17），命令 / GUI 改名后无需重启即生效。
     * 配置留空则回退 messages.yml 的 {@code item.fireball-name}。
     */
    private String fireballName() {
        String configuredName = plugin.getConfigManager().getActiveConfig().getFireballItemName();
        return ChatColor.translateAlternateColorCodes('&',
                (configuredName == null || configuredName.isEmpty())
                        ? lang.get("item.fireball-name")
                        : configuredName);
    }

    public ItemStack createFireball() {
        GameConfig cfg = plugin.getConfigManager().getActiveConfig();
        ItemStack item = new ItemStack(cfg.getFireballMaterial());

        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(fireballName());
        // D-3：PDC 防伪标记（识别器以 PDC 为准；显示名仅作旧道具迁移兼容）。必须写在任何
        // 提前 return（如后续新增分支）之前，保证所有工厂产物都带标记。
        meta.getPersistentDataContainer().set(itemKindKey, PersistentDataType.STRING, KIND_FIREBALL);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + lang.get("fireball.lore-use"));
        lore.add(ChatColor.RED + lang.get("fireball.lore-damage"));
        int cdTicks = cfg.getFireballCooldownTicks();
        if (cdTicks > 0) {
            lore.add(ChatColor.GOLD + lang.get("fireball.lore-cooldown", lang.getLocale(), String.format("%.1f", cdTicks / 20.0)));
        } else {
            lore.add(ChatColor.GOLD + lang.get("fireball.lore-cooldown-none"));
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * 是否烈焰蛋：PDC {@code hilltopvillage:item-kind = fireball} 命中 → true；
     * 否则回退显示名比较（迁移兼容：fix3 之前发放的旧蛋无 PDC，下个大版本可删此分支）。
     */
    public boolean isFireball(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        // D-3：PDC 为准
        String kind = meta.getPersistentDataContainer().get(itemKindKey, PersistentDataType.STRING);
        if (KIND_FIREBALL.equals(kind)) return true;
        // 迁移兼容（旧道具无 PDC，仅按精确显示名识别）
        return meta.hasDisplayName() && meta.getDisplayName().equals(fireballName());
    }

    /* ========== 右键投掷 ========== */

    /** 世界必须对应一个仍在进行中的竞技场。 */
    private boolean isRunningArena(World world) {
        if (world == null) return false;
        var arena = plugin.getMultiArenaManager().getArenaByWorld(world.getName());
        return arena != null
                && (arena.getState() == GameState.RUNNING
                || arena.getState() == GameState.WAVE_INTERMISSION);
    }

    /** 玩家必须属于其当前世界对应的进行中竞技场。 */
    private boolean isPlayerInRunningArena(Player player) {
        var playerArena = plugin.getMultiArenaManager().getPlayerArena(player);
        if (playerArena == null
                || (playerArena.getState() != GameState.RUNNING
                && playerArena.getState() != GameState.WAVE_INTERMISSION)) return false;

        var worldArena = plugin.getMultiArenaManager().getArenaByWorld(player.getWorld().getName());
        return worldArena != null && worldArena.getArenaId().equals(playerArena.getArenaId());
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onRightClick(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        boolean isOffhand = event.getHand() == org.bukkit.inventory.EquipmentSlot.OFF_HAND;
        ItemStack item = isOffhand ? player.getInventory().getItemInOffHand() : player.getInventory().getItemInMainHand();
        if (!isFireball(item)) return;

        // P2-14：旁观者不可投弹（静默拒绝：不消耗、无提示）。锤子的门控已有 isAlive 判定可对照。
        // 置于 isFireball 之后：非弹药右键（高频事件）不付出竞技场查找开销。
        if (player.getGameMode() == GameMode.SPECTATOR) return;
        // 与锤子对齐的存活双保险（死亡→旁观的过渡帧等边缘状态）
        var aliveArena = plugin.getMultiArenaManager().getPlayerArena(player);
        if (aliveArena != null) {
            var aliveData = aliveArena.getGameManager().getPlayerData(player);
            if (aliveData != null && !aliveData.isAlive()) return;
        }

        if (!isPlayerInRunningArena(player)) return;

        event.setCancelled(true);

        // 冷却检查
        GameConfig cfg = plugin.getConfigManager().getActiveConfig();
        UUID pid = player.getUniqueId();
        int cd = cooldowns.getOrDefault(pid, 0);
        if (cd > 0) {
            player.sendMessage(lang.getFor(player, "fireball.cooldown-message", String.format("%.1f", cd / 20.0)));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.5f);
            return;
        }

        // 消耗物品 (生存模式)
        if (player.getGameMode() == org.bukkit.GameMode.SURVIVAL || player.getGameMode() == org.bukkit.GameMode.ADVENTURE) {
            item.setAmount(item.getAmount() - 1);
        }

        // 冷却
        cooldowns.put(pid, cfg.getFireballCooldownTicks());
        ensureCooldownTickerRunning();

        // 发射 fireball 投射物
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();

        Snowball projectile = player.launchProjectile(Snowball.class, direction.multiply(cfg.getFireballSpeed()));
        projectile.setMetadata("hilltop_fireball", new FixedMetadataValue(plugin, player.getUniqueId().toString()));
        projectile.setGravity(false);
        projectile.setGlowing(true);

        player.playSound(player.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1.0f, 1.5f);
        player.spawnParticle(Particle.FLAME, eye.add(direction.clone().multiply(0.5)), 10, 0.1, 0.1, 0.1, 0.02);

        // 飞行粒子轨迹
        trackProjectile(projectile, cfg);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDispense(BlockDispenseEvent event) {
        if (event.isCancelled()) return;
        ItemStack item = event.getItem();
        if (!isFireball(item)) return;
        if (!(event.getBlock().getState() instanceof org.bukkit.block.Dispenser dispenser)) return;
        if (!isRunningArena(event.getBlock().getWorld())) return;

        event.setCancelled(true);

        org.bukkit.inventory.Inventory inv = dispenser.getInventory();

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack slot = inv.getItem(i);
            if (slot != null && isFireball(slot)) {
                slot.setAmount(slot.getAmount() - 1);
                if (slot.getAmount() <= 0) {
                    inv.setItem(i, null);
                }
                break;
            }
        }

        Vector velocity = event.getVelocity();
        Location origin = event.getBlock().getLocation().toCenterLocation().add(velocity.clone().normalize().multiply(0.6));

        GameConfig cfg = plugin.getConfigManager().getActiveConfig();
        Snowball projectile = origin.getWorld().spawn(origin, Snowball.class);
        projectile.setMetadata("hilltop_fireball", new FixedMetadataValue(plugin, "DISPENSER"));
        projectile.setGravity(false);
        projectile.setGlowing(true);
        projectile.setVelocity(velocity.clone().normalize().multiply(cfg.getFireballSpeed()));

        origin.getWorld().playSound(origin, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 1.5f);
        trackProjectile(projectile, cfg);
    }

    /* ========== 飞行轨迹粒子 + 近炸检测 ========== */

    /**
     * 把投射物登记进全局飞行列表，并确保统一 ticker 在运行。
     *
     * <p>只有「加入」这一条路径会创建任务；任务本身全局唯一，列表清空后自停。</p>
     */
    private void trackProjectile(Snowball projectile, GameConfig cfg) {
        activeTrails.put(projectile.getUniqueId(), new TrailState(projectile, cfg));
        ensureTrailTickerRunning();
    }

    /**
     * 确保全局飞行 ticker 在运行。
     * 沿用本文件冷却计时器与 {@code DisplayEntityManager} 的「空则自停」惯例。
     */
    private void ensureTrailTickerRunning() {
        if (trailTickerRunning) return;
        trailTickerRunning = true;
        trailTicker = new BukkitRunnable() {
            @Override
            public void run() {
                if (activeTrails.isEmpty()) {
                    trailTickerRunning = false;
                    trailTicker = null;
                    cancel();
                    return;
                }
                tickTrails();
            }
        };
        trailTicker.runTaskTimer(plugin, 0L, TRAIL_PERIOD_TICKS);
    }

    /**
     * 遍历所有在飞投射物：按 2 tick 做近炸判定，并分别节流粒子输出。
     * 所有结束路径都会从列表移除，且在主动移除实体前清掉 metadata。
     */
    private void tickTrails() {
        Iterator<Map.Entry<UUID, TrailState>> it = activeTrails.entrySet().iterator();
        while (it.hasNext()) {
            TrailState state = it.next().getValue();
            Snowball projectile = state.projectile;

            if (!projectile.isValid() || projectile.isDead()) {
                projectile.removeMetadata("hilltop_fireball", plugin);
                it.remove();
                continue;
            }

            state.ticks += TRAIL_PERIOD_TICKS;
            if (state.ticks > state.expireTicks) {
                removeProjectile(projectile);
                it.remove();
                continue;
            }

            Location loc = projectile.getLocation();
            World world = loc.getWorld();
            if (!isRunningArena(world)) {
                removeProjectile(projectile);
                it.remove();
                continue;
            }

            if (state.ticks % FLAME_SMOKE_PARTICLE_INTERVAL_TICKS == 0) {
                world.spawnParticle(Particle.FLAME, loc, 3, 0.1, 0.1, 0.1, 0.01);
                world.spawnParticle(Particle.SMOKE, loc, 2, 0.15, 0.15, 0.15, 0.01);
            }
            if (state.ticks % LAVA_PARTICLE_INTERVAL_TICKS == 0) {
                world.spawnParticle(Particle.LAVA, loc, 1, 0.05, 0.05, 0.05, 0);
            }

            if (probeProximity(state, loc)) {
                triggerExplosion(projectile, loc, state.cfg);
                projectile.remove();
                it.remove();
            }
        }
    }

    /** 每 2 tick 使用原始近炸 AABB 查询，不扩大查询盒、不缓存密集实体结果。 */
    private boolean probeProximity(TrailState state, Location loc) {
        double fuse = state.cfg.proximityFuse();
        World world = loc.getWorld();
        if (fuse <= 0 || world == null) return false;

        return !world.getNearbyLivingEntities(
                loc, fuse, fuse, fuse, FireballListener::isValidTarget).isEmpty();
    }

    /**
     * 目标筛选条件：存活中的非玩家 LivingEntity，且剔除待保护村民（STORY-1.6：蛋飞过村民头顶不再空爆）。
     * P2-18 延伸：invulnerable 实体（商店 NPC / 预览村民）不是战斗目标，同样不触发近炸空爆
     * （与村民豁免同族；爆炸波及过滤另有同一排除）。
     */
    private static boolean isValidTarget(LivingEntity entity) {
        return entity != null && entity.isValid() && !entity.isDead() && !(entity instanceof Player)
                && !entity.isInvulnerable()
                && !VillagerSystem.isVillageVillager(entity);
    }

    /** 主动清理投射物时先移除标记，防止任何后续命中路径重复引爆。 */
    private void removeProjectile(Snowball projectile) {
        projectile.removeMetadata("hilltop_fireball", plugin);
        projectile.remove();
    }

    /**
     * 冷却查询清理（跨 lane 接口，F2 调用方注意）。
     *
     * <p><b>契约：幂等</b>——重复调用安全；玩家离线安全（仅 UUID 键控的 map 操作，
     * 不访问玩家实体状态）。供既有 quit 路径与 F2 的 onDeath / rejoin / removePlayer 挂接。
     * 在飞烈焰蛋不在此清理（由全局 ticker 按失效/超时自行出列）。</p>
     */
    public void cleanupPlayer(Player player) {
        if (player == null) return;
        cooldowns.remove(player.getUniqueId());
    }

    /**
     * 插件关闭时停止全局任务，并清理仍在飞行的投射物及所有运行期状态。
     * 可重复调用。
     */
    public void shutdown() {
        if (cooldownTicker != null) {
            cooldownTicker.cancel();
            cooldownTicker = null;
        }
        if (trailTicker != null) {
            trailTicker.cancel();
            trailTicker = null;
        }

        for (TrailState state : activeTrails.values()) {
            removeProjectile(state.projectile);
        }
        activeTrails.clear();
        cooldowns.clear();
        cooldownTickerRunning = false;
        trailTickerRunning = false;
    }

    /* ========== 命中爆炸 ========== */

    @EventHandler(priority = EventPriority.NORMAL)
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof Snowball)) return;
        Snowball projectile = (Snowball) event.getEntity();
        if (!projectile.hasMetadata("hilltop_fireball")) return;

        // remove 返回发射时状态；受追踪投射物必须沿用发射瞬间的配置快照。
        TrailState state = activeTrails.remove(projectile.getUniqueId());
        Location impact = projectile.getLocation().clone();
        if (!isRunningArena(impact.getWorld())) {
            removeProjectile(projectile);
            return;
        }

        // state == null 仅用于兼容升级前或其他旧路径创建、但仍带 metadata 的投射物。
        // 该分支改用当前配置即时构造快照，与旧行为一致（旧行为也是每次读取当时的 activeConfig）。
        FireballSnapshot cfg = state != null
                ? state.cfg
                : FireballSnapshot.of(plugin.getConfigManager().getActiveConfig());
        triggerExplosion(projectile, impact, cfg);
    }

    /**
     * 触发烈焰蛋爆炸效果。由 ProjectileHitEvent 或近炸检测调用。
     * 在入口处读取 shooter/metadata 到局部变量，再移除 metadata，确保近炸路径也能正常获取投掷者信息。
     */
    private void triggerExplosion(Snowball projectile, Location impact, FireballSnapshot cfg) {
        // 只处理一次，并再次验证命中世界仍对应运行中的竞技场。
        if (!projectile.hasMetadata("hilltop_fireball")) return;

        World world = impact.getWorld();
        if (!isRunningArena(world)) {
            removeProjectile(projectile);
            return;
        }

        // 获取投掷者（优先 getShooter，metadata 作备用）
        UUID throwerUuid = null;
        if (projectile.getShooter() instanceof Player) {
            throwerUuid = ((Player) projectile.getShooter()).getUniqueId();
        }
        // 从 metadata 读取（兼容发射器或 shooter 为 null 的情况）
        List<MetadataValue> metas = projectile.getMetadata("hilltop_fireball");
        if (throwerUuid == null && !metas.isEmpty()) {
            try {
                String val = metas.get(0).asString();
                if (!"DISPENSER".equals(val)) {
                    throwerUuid = UUID.fromString(val);
                }
            } catch (IllegalArgumentException ignored) {}
        }
        projectile.removeMetadata("hilltop_fireball", plugin);

        // M-17：解析投掷玩家实体实例作为伤害来源（投掷者离线 / 发射器 → null，退回无来源伤害）。
        // 带上来源后 EntityDeathEvent.getKiller() 能解析到投掷者，技能击杀正常计入统计。
        Player attacker = throwerUuid != null ? Bukkit.getPlayer(throwerUuid) : null;

        // 爆炸音效
        world.playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 0.8f);
        world.playSound(impact, Sound.ENTITY_BLAZE_DEATH, 0.8f, 1.0f);

        // 中心爆炸粒子
        world.spawnParticle(Particle.EXPLOSION_EMITTER, impact, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.EXPLOSION, impact, 5, 0.5, 0.5, 0.5, 0.2);
        world.spawnParticle(Particle.FLAME, impact, 30, 1.0, 0.5, 1.0, 0.05);
        world.spawnParticle(Particle.LAVA, impact, 15, 0.5, 0.2, 0.5, 0.02);
        world.spawnParticle(Particle.LARGE_SMOKE, impact, 10, 0.8, 0.5, 0.8, 0.05);

        // 环形火焰扩散
        for (int i = 0; i < 16; i++) {
            double angle = (2 * Math.PI / 16) * i;
            double x = Math.cos(angle) * 2.5;
            double z = Math.sin(angle) * 2.5;
            world.spawnParticle(Particle.FLAME, impact.clone().add(x, 0.2, z), 1, 0, 0, 0, 0.02);
        }

        // 范围伤害与击退（BedWars1058 风格公式：水平推离爆炸点 + 智能 Y 轴处理）
        double radius = cfg.explosionRadius();
        double damage = cfg.damage();
        double knockback = cfg.knockback();
        // P2-18：排除 invulnerable 实体（商店 NPC / 预览村民均设了 invulnerable）——
        // 既不受伤害/点燃/击退，也不被 setVelocity 推离原位。怪物不无敌，伤害路径不受影响。
        Collection<LivingEntity> nearby = world.getNearbyLivingEntities(impact, radius, radius, radius,
                entity -> entity != null && entity.isValid() && !entity.isInvulnerable()
                        && !VillagerSystem.isVillageVillager(entity));

        for (LivingEntity entity : nearby) {
            double distance = entity.getLocation().distance(impact);
            double falloff = 1.0 - (distance / (radius * FALLOFF_RANGE));
            falloff = Math.max(FALLOFF_MIN, Math.min(1.0, falloff));

            entity.setNoDamageTicks(0);

            boolean isThrower = throwerUuid != null && entity.getUniqueId().equals(throwerUuid);

            // STORY-1.8：零友伤 —— 玩家分支只弹飞不伤、去点燃（弹飞在循环尾统一处理）；
            // 怪物分支伤害/点燃不变。
            // M-17：带来源重载 damage(double, Entity)（Paper 1.21.4 的 source 参数为 @Nullable）——
            // attacker 为投掷玩家时 getKiller() 可取到，技能击杀计入统计。保留 null 分支走旧重载，
            // 与修改前的「发射器 / 投掷者离线」路径行为严格一致。（附注：带来源后怪物会正常
            // 记录仇恨来源 = 投掷者，属预期语义。）
            if (!(entity instanceof Player)) {
                if (attacker != null) {
                    entity.damage(damage * falloff, attacker);
                } else {
                    entity.damage(damage * falloff);
                }
                entity.setFireTicks(40);
            }

            // 投掷者本人跳过此循环，延迟单独处理
            if (isThrower) continue;

            applyBwKnockback(entity, impact, knockback, falloff);
        }

        // 对投掷者本人：同一公式，延迟 1 tick（避免同 tick 被覆盖）
        if (throwerUuid != null) {
            final UUID finalUuid = throwerUuid;
            final Location finalImpact = impact.clone();
            final double finalRadius = radius;
            final double finalKnockback = knockback;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player thrower = Bukkit.getPlayer(finalUuid);
                if (thrower != null && thrower.isOnline()
                        && thrower.getWorld().equals(finalImpact.getWorld())
                        && thrower.getLocation().distance(finalImpact) <= finalRadius) {
                    double distance = thrower.getLocation().distance(finalImpact);
                    double falloff = 1.0 - (distance / (finalRadius * FALLOFF_RANGE));
                    falloff = Math.max(FALLOFF_MIN, Math.min(1.0, falloff));
                    applyBwKnockback(thrower, finalImpact, finalKnockback, falloff);
                }
            });
        }

        // 命中位置生成短暂火焰粒子残留
        new BukkitRunnable() {
            int t = 0;
            @Override
            public void run() {
                if (t++ >= 10) { cancel(); return; }
                world.spawnParticle(Particle.FLAME, impact.clone().add(0, 0.1, 0),
                        3, 1.0, 0.05, 1.0, 0.01);
                world.spawnParticle(Particle.SMOKE, impact.clone().add(0, 0.2, 0),
                        2, 0.8, 0.1, 0.8, 0.01);
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /**
     * BedWars1058 风格击退公式。
     * 水平方向：推离爆炸中心；垂直方向：动态 Y 轴处理（补偿脚下爆炸、跳跃增强）。
     *
     * <p><b>P2-16 注释修正（不改数值行为，火箭跳是 feature）：</b>下方 {@code y += KB_Y_COMPENSATION}
     * 的 1.5 是<b>归一化域</b>补偿（{@code normalizedVector.getY() ∈ [-1,1]}），不是格单位；
     * 分支阈值 0.5 同样在归一化域。补偿后 y 几乎恒 &gt; 0.5，因此三分支的实际选择由
     * 「爆点相对实体的几何比例」主导而非距离 —— 与直觉的「越近越平推」相反。此为已定稿手感
     * （作者已知悉，见 CHANGELOG 火箭跳调优锚点）；如需精调，建议实机标定后另开调整。</p>
     */
    private void applyBwKnockback(LivingEntity entity, Location impact, double knockback, double falloff) {
        Vector entityVector = entity.getLocation().toVector();
        Vector diff = impact.toVector().subtract(entityVector);

        double vScale = knockback * falloff;

        if (diff.lengthSquared() < 1e-6) {
            entity.setVelocity(new Vector(0, vScale * KB_VERTICAL_SCALE, 0));
            return;
        }

        Vector normalizedVector = diff.normalize();

        // 水平方向：用独立倍率（远小于垂直），避免脚下稍微偏离就横飞几十格
        double hScale = vScale * KB_HORIZONTAL_SCALE;
        Vector horizontalVector = new Vector(
                normalizedVector.getX() * hScale * -1,
                0,
                normalizedVector.getZ() * hScale * -1);

        double y = normalizedVector.getY();
        if (y < 0) y += KB_Y_COMPENSATION;
        if (y <= 0.5) {
            y = vScale * KB_VERTICAL_SCALE;
        } else {
            y = y * vScale * KB_VERTICAL_SCALE;
        }

        entity.setVelocity(horizontalVector.setY(y));
    }
}