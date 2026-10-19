package com.hilltopvillage.mechanics;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.config.GameConfig;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.core.PlayerData;
import com.hilltopvillage.util.LanguageManager;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HammerListener implements Listener {

    /** 重锤剩余次数 PDC 键（存发锤时的 max-uses 快照，每次猛击 -1） */
    private static final String HAMMER_USES_KEY = "hammer-uses-left";
    /** 统一道具类型 PDC 键（D-3 PDC 防伪）：STRING 类型，值见 KIND_* 常量 */
    private static final String ITEM_KIND_KEY = "item-kind";
    /** item-kind 取值：重锤 */
    private static final String KIND_HAMMER = "hammer";

    private final HilltopVillagePlugin plugin;
    private final LanguageManager lang;
    /** 统一道具类型键（构造期建好复用；与 {@link WaveManager} 的 airdropKey 同一模式） */
    private final NamespacedKey itemKindKey;
    private final Map<UUID, BossBar> chargeBars;
    private final Map<UUID, Integer> lastChargeTier;
    private final Map<UUID, Integer> lastBossBarTick;
    private final Set<UUID> recentlyLanded;
    /**
     * 猛击冷却：玩家 UUID → 下次可用时间戳（毫秒，System.currentTimeMillis）。
     *
     * <p><b>按设计保留</b>（L11-09）：冷却跨离场/死亡/重连有意不清（防止离场或送死重置冷却刷技能），
     * 读时惰性失效（{@code readyAt < now} 即视为可用），量级有界于历史在线玩家数，不构成泄漏。</p>
     */
    private final Map<UUID, Long> smashCooldowns;

    public HammerListener(HilltopVillagePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
        this.itemKindKey = new NamespacedKey(plugin, ITEM_KIND_KEY);
        this.chargeBars = new ConcurrentHashMap<>();
        this.lastChargeTier = new ConcurrentHashMap<>();
        this.lastBossBarTick = new ConcurrentHashMap<>();
        this.recentlyLanded = Collections.newSetFromMap(new ConcurrentHashMap<>());
        this.smashCooldowns = new ConcurrentHashMap<>();
    }

    /** 解析玩家所在竞技场的 GameManager（不在任何竞技场时返回 null） */
    private GameManager gm(Player player) {
        var arena = plugin.getMultiArenaManager().getPlayerArena(player);
        return arena == null ? null : arena.getGameManager();
    }

    /* ========== 模型系统 ========== */

    /**
     * 重锤显示名：实时从 activeConfig 读取（P2-17），命令 / GUI 改名后无需重启即生效。
     * 配置留空则回退 messages.yml 的 {@code item.hammer-name}。
     */
    private String hammerName() {
        String configuredName = plugin.getConfigManager().getActiveConfig().getHammerItemName();
        return ChatColor.translateAlternateColorCodes('&',
                (configuredName == null || configuredName.isEmpty())
                        ? lang.get("item.hammer-name")
                        : configuredName);
    }

    public ItemStack createHammer() {
        GameConfig cfg = plugin.getConfigManager().getActiveConfig();
        ItemStack hammer;

        // ItemsAdder 优先
        if (cfg.isHammerUseItemsAdder() && !cfg.getHammerItemsAdderId().isEmpty()) {
            hammer = createItemsAdderItem(cfg.getHammerItemsAdderId());
            if (hammer == null) {
                // 回退到普通物品
                hammer = createVanillaHammer(cfg);
            }
        } else {
            hammer = createVanillaHammer(cfg);
        }

        if (hammer == null) return null;

        ItemMeta meta = hammer.getItemMeta();
        meta.setDisplayName(hammerName());
        // D-3：PDC 防伪标记（识别器以 PDC 为唯一口径）
        meta.getPersistentDataContainer().set(itemKindKey, PersistentDataType.STRING, KIND_HAMMER);
        List<String> lore = new ArrayList<>();
        lore.add(lang.get("hammer.lore1"));
        lore.add(lang.get("hammer.lore2"));
        lore.add(lang.get("hammer.lore3"));
        int maxUses = cfg.getHammerMaxUses();
        if (maxUses > 0) {
            meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, HAMMER_USES_KEY), PersistentDataType.INTEGER, maxUses);
            lore.add(lang.get("hammer.lore-uses", lang.getLocale(), maxUses));
        }
        meta.setLore(lore);
        hammer.setItemMeta(meta);
        return hammer;
    }

    private ItemStack createVanillaHammer(GameConfig cfg) {
        ItemStack hammer = new ItemStack(cfg.getHammerMaterial());
        if (cfg.getHammerCustomModelData() > 0) {
            ItemMeta meta = hammer.getItemMeta();
            meta.setCustomModelData(cfg.getHammerCustomModelData());
            hammer.setItemMeta(meta);
        }
        return hammer;
    }

    private ItemStack createItemsAdderItem(String itemId) {
        try {
            // 使用反射避免编译期依赖 ItemsAdder
            Plugin iaPlugin = Bukkit.getPluginManager().getPlugin("ItemsAdder");
            if (iaPlugin != null && iaPlugin.isEnabled()) {
                Class<?> customStackClass = Class.forName("dev.lone.itemsadder.api.CustomStack");
                Object customStack = customStackClass.getMethod("getInstance", String.class).invoke(null, itemId);
                if (customStack != null) {
                    return (ItemStack) customStackClass.getMethod("getItemStack").invoke(customStack);
                }
            }
        } catch (Exception ignored) {
            plugin.getLogger().warning("ItemsAdder \u672a\u5b89\u88c5\u6216\u65e0\u6cd5\u83b7\u53d6\u7269\u54c1: " + itemId);
        }
        return null;
    }

    /**
     * 是否重锤：PDC {@code hilltopvillage:item-kind = hammer} 命中 → true。
     *
     * <p>fix5 M-01：显示名回退已删除——旧迁移分支（fix3 前发放的 PDC-less 旧锤）经字节码
     * 验证可被铁砧逐字符复现（去码配置下），构成伪造/无限锤入口；旧道具逾期多个版本，
     * 直接停用识别。此后改名不影响识别（R3-04 同分支副面一并关闭）。</p>
     */
    public boolean isHammer(ItemStack item) {
        if (item == null) return false;
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        // D-3：PDC 为准（唯一口径）
        String kind = meta.getPersistentDataContainer().get(itemKindKey, PersistentDataType.STRING);
        return KIND_HAMMER.equals(kind);
    }

    /* ========== 右键蓄力激活 ========== */

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR) return;

        Player player = event.getPlayer();
        GameManager gameManager = gm(player);
        if (gameManager == null) return;

        // STORY-1.8：只在对局运行（含整备期，休整期允许开锤）时允许开锤，等待区/结算期拒绝并提示。
        // M-18/P2-13：结算倒计时窗口（haltGame 后状态刻意保持 RUNNING/WAVE_INTERMISSION）
        // 由 isSettlementPhase() 单独拦截 —— haltGame 不改状态，仅靠 isGameRunning() 拦不住。
        if (!gameManager.isGameRunning() || gameManager.isSettlementPhase()) {
            ItemStack probe = player.getInventory().getItemInMainHand();
            if (isHammer(probe)) {
                player.sendMessage(lang.getFor(player, "hammer.not-running"));
                event.setCancelled(true);
            }
            return;
        }

        PlayerData data = gameManager.getPlayerData(player);
        if (data == null || !data.isAlive()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isHammer(item)) return;

        if (data.isSmashActive()) return;

        // STORY-1.3：冷却检查（时间戳方案，无 ticker）
        double cooldownSeconds = plugin.getConfigManager().getActiveConfig().getHammerCooldownSeconds();
        if (cooldownSeconds > 0) {
            Long readyAt = smashCooldowns.get(player.getUniqueId());
            long now = System.currentTimeMillis();
            if (readyAt != null && now < readyAt) {
                long remainSeconds = (readyAt - now + 999) / 1000;
                player.sendActionBar(lang.getFor(player, "hammer.cooldown", remainSeconds));
                event.setCancelled(true);
                return;
            }
        }

        data.activateSmash(player.getLocation().getY(), gameManager.getGameWorld().getGameTime());
        player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.5f);
        player.spawnParticle(Particle.ELECTRIC_SPARK, player.getLocation().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0.05);

        // 复用或创建蓄力 BossBar
        BossBar bar = chargeBars.get(player.getUniqueId());
        if (bar == null) {
            bar = Bukkit.createBossBar(lang.getFor(player, "hammer.bossbar.charging"), BarColor.YELLOW, BarStyle.SOLID);
            chargeBars.put(player.getUniqueId(), bar);
            bar.addPlayer(player);
        }
        bar.setTitle(lang.getFor(player, "hammer.bossbar.charging"));
        bar.setColor(BarColor.YELLOW);
        bar.setProgress(0.0);
        bar.setVisible(true);
        lastChargeTier.put(player.getUniqueId(), 0);

        event.setCancelled(true);
    }

    /* ========== 下落追踪 + BossBar更新 ========== */

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!event.hasChangedPosition()) return;
        Player player = event.getPlayer();
        GameManager gameManager = gm(player);
        if (gameManager == null) return;

        PlayerData data = gameManager.getPlayerData(player);
        if (data == null || !data.isSmashActive()) return;

        // M-18 防御纵深：旁观/已阵亡玩家不得触发猛击结算（幽灵猛击主路径：蓄力中死亡/重连回核心）。
        // 正常清理由 clearSmashState()（onDeath/rejoin 调用）负责，此处兜底。
        if (!data.isAlive() || player.getGameMode() == GameMode.SPECTATOR) {
            clearSmashState(player);
            return;
        }
        // M-18 面 3：结算倒计时窗口（haltGame 后状态刻意不变）内在飞蓄力单不落地结算 ——
        // 与交互门控同一口径（P2-13 统一门控源），避免结算期满档猛击伤害/弹飞队友。
        if (gameManager.isSettlementPhase()) {
            clearSmashState(player);
            return;
        }

        double currentY = event.getTo().getY();
        Location to = event.getTo();
        boolean isOnGroundNow = isPlayerOnGround(player, to);

        BossBar bar = chargeBars.get(player.getUniqueId());
        if (bar != null) {
            int currentTick = Bukkit.getCurrentTick();
            Integer lastTick = lastBossBarTick.get(player.getUniqueId());
            if (lastTick != null && lastTick == currentTick) {
            } else {
                lastBossBarTick.put(player.getUniqueId(), currentTick);

                GameConfig cfg = plugin.getConfigManager().getActiveConfig();
                double startY = data.getSmashStartY();
                double fallDist = startY - currentY;
                double maxDist = cfg.getHammerHighCharge() * 1.5;
                double progress = Math.max(0.0, Math.min(1.0, fallDist / maxDist));

                bar.setProgress(progress);

                int tier = getHammerTier(cfg, fallDist);
                Integer lastTier = lastChargeTier.get(player.getUniqueId());
                if (lastTier == null || lastTier != tier) {
                    lastChargeTier.put(player.getUniqueId(), tier);
                    if (tier == 2) {
                        bar.setColor(BarColor.RED);
                        bar.setTitle(lang.getFor(player, "hammer.bossbar.ready"));
                    } else if (tier == 1) {
                        bar.setColor(BarColor.YELLOW);
                        bar.setTitle(lang.getFor(player, "hammer.bossbar.smashing"));
                    } else {
                        bar.setColor(BarColor.YELLOW);
                        bar.setTitle(lang.getFor(player, "hammer.bossbar.charging"));
                    }
                }
            }
        }

        if (!isOnGroundNow) return;
        if (recentlyLanded.contains(player.getUniqueId())) return;

        double fallDistance = startY(player, data, currentY);
        long currentTick = gameManager.getGameWorld().getGameTime();
        long tickLimit = plugin.getConfigManager().getActiveConfig().getHammerSmashTimeoutTicks();

        if (currentY < data.getLastSmashY() - 0.01) {
            data.setSmashActivateTick(currentTick);
        }
        data.setLastSmashY(currentY);

        if (currentTick - data.getSmashActivateTick() > tickLimit) {
            data.deactivateSmash();
            removeChargeBar(player);
            return;
        }

        if (fallDistance >= 1.0) {
            executeSmash(player, data, fallDistance);
        } else {
            data.deactivateSmash();
            removeChargeBar(player);
        }
    }

    private double startY(Player player, PlayerData data, double currentY) {
        return data.getSmashStartY() - currentY;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;

        Player player = (Player) event.getEntity();
        GameManager gameManager = gm(player);
        if (gameManager == null) return;

        PlayerData data = gameManager.getPlayerData(player);
        if (data == null) return;

        if (data.isSmashActive()) {
            event.setCancelled(true);
        }
    }

    /* ========== 猛击执行 ========== */

    private void executeSmash(Player player, PlayerData data, double fallDistance) {
        // fix5 锐评 C-24（X2 收割 P2）：落地瞬间主手必须仍是锤——蓄力态按玩家存（不绑物品），
        // 蓄力中换手/丢弃后落地若照常结算，consumeUse 读到非锤直接 return true（被当作
        // 「无计数语义」）→ max-uses 计数被跳过，30 次上限形同虚设（无限锤残余通道；
        // M-01 已封零成本伪造通道，此处补封真锤绕限通道）。语义收紧：换手即放弃本次蓄力
        // ——不结算伤害、不进冷却、不扣次数（与「未达落差自动取消」同款静默放弃）。
        if (!isHammer(player.getInventory().getItemInMainHand())) {
            data.deactivateSmash();
            removeChargeBar(player);
            return;
        }
        data.deactivateSmash();
        recentlyLanded.add(player.getUniqueId());

        // 清除蓄力条
        removeChargeBar(player);

        new BukkitRunnable() {
            @Override
            public void run() {
                recentlyLanded.remove(player.getUniqueId());
            }
        }.runTaskLater(plugin, 10L);

        GameConfig cfg = plugin.getConfigManager().getActiveConfig();
        int tier = getHammerTier(cfg, fallDistance);
        double finalDamage = getTierDamage(cfg, tier);
        double radius = getTierRadius(cfg, tier);

        // STORY-1.3：冷却计时起点（猛击落地成功触发）
        double cooldownSeconds = cfg.getHammerCooldownSeconds();
        if (cooldownSeconds > 0) {
            smashCooldowns.put(player.getUniqueId(),
                    System.currentTimeMillis() + (long) (cooldownSeconds * 1000));
        }

        // STORY-1.3：扣使用次数（max-uses 方案，原版/ItemsAdder 统一行为）
        if (!consumeUse(player)) {
            // 次数刚好耗尽 → 碎裂，本次猛击照常结算（伤害已打出）
            player.getInventory().setItemInMainHand(null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            player.sendMessage(lang.getFor(player, "hammer.broken"));
        }

        // 弹起玩家自身
        player.setVelocity(new Vector(0, 0.8 + Math.min(fallDistance * 0.05, 1.2), 0));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 1.2f);

        // 伤害提示
        String tierName = getTierDisplayName(player, tier);
        String smashKey = tier == 2 ? "hammer.smash.high" : tier == 1 ? "hammer.smash.medium" : "hammer.smash.low";
        String actionBarMsg = lang.getFor(player, smashKey, tierName, String.format("%.1f", finalDamage));
        player.sendActionBar(actionBarMsg);
        // 范围伤害（包含玩家弹飞）
        applyAreaDamage(player, player.getLocation(), finalDamage, radius);
        applyAftershock(player.getLocation(), radius);
        spawnSmashEffect(player.getLocation(), fallDistance);

        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.5f);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_ANVIL_LAND, 1.0f, 0.8f);
    }

    /* ========== BossBar 管理 ========== */

    private void removeChargeBar(Player player) {
        BossBar bar = chargeBars.remove(player.getUniqueId());
        if (bar != null) {
            bar.setVisible(false);
            bar.removeAll();
        }
        lastChargeTier.remove(player.getUniqueId());
        lastBossBarTick.remove(player.getUniqueId());
    }

    /**
     * 统一清理入口（M-13 + M-18，跨 lane 接口，F2 调用方注意）。
     *
     * <p><b>契约：幂等</b>——重复调用安全；<b>玩家离线安全</b>（不访问玩家实体状态，
     * 仅以 UUID 做 map 键与 PlayerData 查找）。供 F2 在 {@code ArenaInstance.removePlayer}
     * / 重置路径挂接，以及既有 quit 路径（主类 onPlayerQuit）调用。</p>
     *
     * <p>清理内容：蓄力 BossBar（{@code removeChargeBar}：setVisible(false) + removeAll()）、
     * chargeBars / lastChargeTier / lastBossBarTick 条目、recentlyLanded，以及 PlayerData 的
     * smash 状态（经 {@link #clearSmashState(Player)}）。</p>
     *
     * <p><b>smashCooldowns 刻意不清</b>（设计保留，见 fix3-L11-09 结论）：冷却跨离场/死亡/重连
     * 保留，防止「离场或送死重置冷却刷技能」；条目读时惰性失效（readyAt &lt; now 即视为可用），
     * 每条仅一个 Long，量级有界于历史曾猛击的玩家数，不构成泄漏。真退服的冷却是时间戳条目，
     * 由 {@code onPlayerQuit} 单独做内存卫生清理（见该处注释）。</p>
     */
    public void cleanupPlayer(Player player) {
        if (player == null) return;
        UUID uuid = player.getUniqueId();
        clearSmashState(player);
        recentlyLanded.remove(uuid);
    }

    /**
     * 清除玩家的猛击（蓄力）状态与蓄力 BossBar（死亡 / 重连专用，M-18；跨 lane 接口，F2 调用方注意）。
     *
     * <p><b>契约：幂等</b>——重复调用安全；<b>玩家离线安全</b>（UUID 键控，仅 map 操作；
     * 玩家已离场 / 无 PlayerData 时自动降级为仅清 BossBar）。</p>
     *
     * <p>清理内容：{@code removeChargeBar(player)}（BossBar setVisible(false) + removeAll()，
     * chargeBars / lastChargeTier / lastBossBarTick 条目）+ 所在竞技场 PlayerData 的
     * {@code deactivateSmash()}。gm(player) 为 null（玩家已离场或不在任何竞技场）时至少清
     * BossBar；此时 PlayerData 由对局级清理（endGame / stopGame / removeOfflinePlayer 的批量
     * {@code deactivateSmash()}）收口。</p>
     *
     * <p>语义：幽灵猛击防线 —— 蓄力中死亡（旁观重生落回核心）或 quit 后重连落回核心时，
     * 若不清此状态，onPlayerMove 的 fallDistance 恒达标会立刻打出满档 AOE（M-18 主路径）。</p>
     */
    public void clearSmashState(Player player) {
        if (player == null) return;
        removeChargeBar(player);
        GameManager gameManager = gm(player);
        if (gameManager != null) {
            PlayerData data = gameManager.getPlayerData(player);
            if (data != null) {
                data.deactivateSmash();
            }
        }
    }

    /**
     * 消耗主手重锤 1 次使用次数（STORY-1.3 耐久方案）。
     *
     * <p>无 PDC 计数器（max-uses=0 时发的锤、或玩家普通攻击消耗耐久中）→ 返回 true 不处理；
     * 剩余次数 &gt; 1 → PDC -1 并同步 lore 显示、映射原版耐久条（如 meta 支持）；剩余 1 次 →
     * 返回 false，由调用方碎裂（本次猛击已结算）。</p>
     *
     * @return 锤子是否还有剩余次数（false = 已耗尽，调用方应碎裂）
     */
    private boolean consumeUse(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isHammer(item)) return true;
        ItemMeta meta = item.getItemMeta();
        Integer left = meta.getPersistentDataContainer().get(
                new NamespacedKey(plugin, HAMMER_USES_KEY), PersistentDataType.INTEGER);
        // max-uses=0（创建时未写入计数器）→ 无计数语义，永不碎裂（fix5 M-01 后唯一合法 null 来源）
        if (left == null) return true;
        int remain = left - 1;
        if (remain <= 0) return false;

        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, HAMMER_USES_KEY), PersistentDataType.INTEGER, remain);
        List<String> lore = meta.getLore();
        if (lore != null && !lore.isEmpty()) {
            lore.set(lore.size() - 1, lang.getFor(player, "hammer.lore-uses", remain));
            meta.setLore(lore);
        }
        // 原版耐久条映射显示（ItemsAdder 等非 Damageable meta 自动跳过）
        if (meta instanceof Damageable damageable) {
            int maxUses = plugin.getConfigManager().getActiveConfig().getHammerMaxUses();
            if (maxUses > 0 && item.getType().getMaxDurability() > 0) {
                double fraction = (double) remain / maxUses;
                damageable.setDamage((int) (item.getType().getMaxDurability() * (1.0 - fraction)));
            }
        }
        item.setItemMeta(meta);
        return true;
    }

    /* ========== 群体攻击（AOE + 玩家击飞） ========== */

    private void applyAreaDamage(Player attacker, Location center, double damage, double radius) {
        World world = center.getWorld();
        // P2-18：排除 invulnerable 实体（商店 NPC / 预览村民均设了 invulnerable）——
        // 不被击退推离原位、不吃伤害。怪物不无敌，伤害/击退路径不受影响。
        Collection<LivingEntity> nearby = world.getNearbyLivingEntities(center, radius, radius, radius,
                entity -> entity != null && entity.isValid() && !entity.isInvulnerable()
                        && entity != attacker
                        && !VillagerSystem.isVillageVillager(entity));

        for (LivingEntity entity : nearby) {
            double distance = entity.getLocation().distance(center);
            double falloff = 1.0 - (distance / (radius * 1.2));
            falloff = Math.max(0.3, Math.min(1.0, falloff));
            double actualDamage = damage * falloff;

            Vector knockback = entity.getLocation().toVector().subtract(center.toVector()).normalize();

            if (entity instanceof Player) {
                // M-20（决策 D-2）：弹飞队友量级收敛到烈焰蛋当前锚点
                // （CHANGELOG:1178 —— 烈焰蛋垂直初速 2.25 实测升空约 21 格）。
                // 旧值 setY(1.2 + falloff*0.6) × multiply(2.0) = 竖向初速 2.76~3.6，是烈焰蛋的
                // 1.2~1.6 倍，线性外推升空 26~34 格、落地摔伤可秒人。现改为：
                //   竖向 = 1.8 × falloff + 0.4（falloff=1 → 2.2 ≤ 2.2 上限；falloff=0.3 → 0.94，明显更小）
                //   水平 = 归一化水平向量 × 0.7（对齐烈焰蛋 0.25×knockback≈1.25 的小倍率量级）
                // 语义保留：锤子对玩家仍「只弹不伤」，目标是「把人从近身弹开几格」。
                knockback.multiply(new Vector(0.7, 0.0, 0.7));
                knockback.setY(1.8 * falloff + 0.4);
                entity.setVelocity(knockback);
                ((Player) entity).playSound(entity.getLocation(), Sound.ENTITY_PLAYER_ATTACK_KNOCKBACK, 0.7f, 1.0f);
            } else {
                // 对怪物造成伤害 + 击退（M-17：带来源重载，技能击杀计入统计）
                entity.damage(actualDamage, attacker);
                knockback.setY(0.4);
                knockback.multiply(1.5);
                entity.setVelocity(knockback);
            }
        }
    }

    private void applyAftershock(Location center, double radius) {
        int duration = plugin.getConfigManager().getActiveConfig().getHammerAftershockTicks();
        int slownessLevel = 2;

        World world = center.getWorld();
        // P2-18：invulnerable 实体（商店 NPC / 预览村民）不吃余震减速
        Collection<LivingEntity> nearby = world.getNearbyLivingEntities(center, radius, radius, radius,
                entity -> entity != null && !(entity instanceof Player) && entity.isValid()
                        && !entity.isInvulnerable()
                        && !VillagerSystem.isVillageVillager(entity));

        for (LivingEntity entity : nearby) {
            entity.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, duration, slownessLevel, false, true, true));
        }
    }

    private void spawnSmashEffect(Location location, double fallDistance) {
        GameConfig cfg = plugin.getConfigManager().getActiveConfig();
        int tier = getHammerTier(cfg, fallDistance);
        if (tier >= 2) {
            HammerEffectRenderer.spawnHighTierEffect(location);
        } else if (tier >= 1) {
            HammerEffectRenderer.spawnMediumTierEffect(location);
        } else {
            HammerEffectRenderer.spawnLowTierEffect(location);
        }
    }

    /* ========== 伤害分级（每档独立配置） ========== */

    private int getHammerTier(GameConfig cfg, double fallDistance) {
        if (fallDistance >= cfg.getHammerHighCharge()) return 2;
        if (fallDistance >= cfg.getHammerMediumCharge()) return 1;
        return 0;
    }

    private double getTierDamage(GameConfig cfg, int tier) {
        return switch (tier) {
            case 2 -> cfg.getHammerHighDamage();
            case 1 -> cfg.getHammerMediumDamage();
            default -> cfg.getHammerLowDamage();
        };
    }

    private double getTierRadius(GameConfig cfg, int tier) {
        return switch (tier) {
            case 2 -> cfg.getHammerHighRadius();
            case 1 -> cfg.getHammerMediumRadius();
            default -> cfg.getHammerLowRadius();
        };
    }

    private String getTierDisplayName(Player player, int tier) {
        return switch (tier) {
            case 2 -> lang.getFor(player, "hammer.tier.high");
            case 1 -> lang.getFor(player, "hammer.tier.medium");
            default -> lang.getFor(player, "hammer.tier.low");
        };
    }

    /* ========== 落地检测 ========== */

    private boolean isPlayerOnGround(Player player, Location to) {
        if (player.isOnGround()) return true;

        Location below = to.clone().subtract(0, 0.1, 0);
        return below.getBlock().getType().isSolid();
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cleanupPlayer(player);
        // 真退服才清冷却条目（内存卫生）：cleanupPlayer 被 F2 挂到离场/死亡/重连路径，
        // 其中必须保留冷却（防离场重置刷技能，L11-09）；退服后时间戳不可能再被复用为玩法
        // 手段（登录即清零走新局），故此处清除避免长跑服条目无界累积。
        smashCooldowns.remove(player.getUniqueId());
    }
}
