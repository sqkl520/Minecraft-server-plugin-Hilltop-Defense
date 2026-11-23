package com.hilltopvillage.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import org.mockito.Mockito;

import com.hilltopvillage.HilltopVillagePlugin;
import com.hilltopvillage.admin.AdminDeployManager;
import com.hilltopvillage.arena.ArenaInstance;
import com.hilltopvillage.core.GameManager;
import com.hilltopvillage.testsupport.MockBukkitTestBase;

/**
 * 向导「过期按钮」的真实点击路径回归测试。
 *
 * <p><b>为什么必须有这个类</b>：同目录的 SetupWizardStaleStepTest 只测三个 static 纯谓词，
 * 从不驱动 handle(...)。实测证明它钉不住任何行为 —— 把 confirm-waiting 的守卫改回旧的
 * if (s.step != 2)（即用户报告的那个 bug 原样复活），或把 rejectStaleStep 整个改成
 * if (true) return false;（整个过期按钮特性完全失效），套件依然全绿。</p>
 *
 * <p>所以本类直接调 SetupWizard#handle(Player, String)，走的是游戏内点击 run_command
 * 之后的同一条生产路径，并断言两件事：守卫放行还是拒绝，以及数据有没有真的被改
 * （读 AdminDeployManager 的坐标，而不是只看消息）。只断言「没报错」的话，
 * 一个「放行但什么都不做」的实现也能通过。</p>
 */
class SetupWizardClickPathTest extends MockBukkitTestBase {

    private static final String ARENA_ID = "arena1";

    private HilltopVillagePlugin plugin;
    private PlayerMock admin;
    private SetupWizard wizard;
    private World arenaWorld;
    private AdminDeployManager deploy;

    @BeforeEach
    void setUp() {
        plugin = loadFullPluginWithArenas();
        wizard = new SetupWizard(plugin);

        ArenaInstance arena = arena();
        assertNotNull(arena.getGameManager(), "前置条件：arena1 必须有 GameManager");
        arenaWorld = arena.getGameManager().getGameWorld();
        assertNotNull(arenaWorld, "前置条件：arena1 必须有游戏世界");

        arenaWorld = stubParticleWorld(arenaWorld);

        admin = server.addPlayer("WizardClickAdmin");
        admin.addAttachment(plugin, "hilltopvillage.admin", true);
        ((PlayerMock) admin).setLocation(arenaWorld.getSpawnLocation());

        deploy = arena.getGameManager().getAdminDeployManager();
        assertNotNull(deploy, "前置条件：竞技场必须暴露 AdminDeployManager");
    }

    private ArenaInstance arena() {
        ArenaInstance a = plugin.getMultiArenaManager().getArena(ARENA_ID);
        assertNotNull(a, "前置条件：arena1 必须已启用");
        return a;
    }

    private List<String> drain() {
        List<String> out = new ArrayList<>();
        String message;
        while ((message = admin.nextMessage()) != null) {
            out.add(message);
        }
        return out;
    }

    /**
     * 上一次 capture() 抽干的消息。
     *
     * <p>drain() 有副作用（把消息队列取空），所以一次点击只能抽一次 —— 否则第一条断言
     * 把消息吃掉，第二条断言面对空列表，失败原因与产品行为无关。</p>
     */
    private List<String> lastBatch = List.of();

    /** 抽干当前消息队列，记为「本次点击的批次」。每次 handle(...) 之后必须调用一次。 */
    private void capture() {
        lastBatch = drain();
    }

    /**
     * 某条 i18n 消息是否出现，按「模板里第一个占位符之前的那一段」做指纹匹配。
     *
     * <p>不能拿 getFor(admin, key) 比整串：setup.stale-step 带三个占位符，不传参时
     * 返回的是未替换的模板，永远不会等于实际发出的文本。</p>
     */
    private boolean said(String i18nKey, String... args) {
        String expected = plugin.getLanguageManager().getFor(admin, i18nKey, args);
        int brace = expected.indexOf('{');
        String fingerprint = (brace > 0 ? expected.substring(0, brace) : expected).trim();
        return lastBatch.stream().anyMatch(m -> m.contains(fingerprint));
    }

    // ------------------------------------------------------------ 用户报告的原路径

    @Test
    @DisplayName("用户报告路径：第2步点[设为核心]到第3步，再点[设为等待区]必须成功写盘")
    void waitingAreaStillSettableAtStep3() {
        SetupWizard.SetupSession session = wizard.injectSessionForTest(admin, 2, ARENA_ID);

        wizard.handle(admin, "confirm-core");
        capture();
        assertEquals(3, session.step, "点完[设为核心]必须推进到第 3 步");
        assertFalse(said("setup.unknown"), "第 2 步的[设为核心]不该被拒");

        Location before = deploy.getWaitingLocation();
        ((PlayerMock) admin).setLocation(arenaWorld.getSpawnLocation().add(7, 0, 7));
        wizard.handle(admin, "confirm-waiting");
        capture();

        assertFalse(said("setup.stale-step"), "第 3 步的[设为等待区]属于已放宽的路径，不该被当成过期按钮");
        assertFalse(said("setup.unknown"), "旧实现在这里报「未知的部署命令」，正是用户报告的缺陷");
        assertNotEquals(before, deploy.getWaitingLocation(),
                "等待区坐标必须真的被改写 —— 只断言「没报错」会让空实现蒙混过关");
        assertEquals(admin.getLocation(), deploy.getWaitingLocation(), "写入的应当是玩家当前位置");
        assertEquals(3, session.step, "[设为等待区]不推进步骤，必须仍停在第 3 步");
    }

    @Test
    @DisplayName("第 2 步本身点[设为等待区]仍然可用（原有行为不回归）")
    void waitingAreaWorksAtStep2() {
        wizard.injectSessionForTest(admin, 2, ARENA_ID);
        ((PlayerMock) admin).setLocation(arenaWorld.getSpawnLocation().add(3, 0, 3));
        wizard.handle(admin, "confirm-waiting");
        capture();
        assertFalse(said("setup.unknown"), "第 2 步的[设为等待区]是原有关键路径，绝不能报错");
        assertEquals(admin.getLocation(), deploy.getWaitingLocation());
    }

    // ------------------------------------------------------------ 过期按钮必须被拒

    @Test
    @DisplayName("第 3 步点旧的[设为核心]必须被拒，且核心不许被搬走")
    void staleCoreButtonRejectedAndCoreUntouched() {
        SetupWizard.SetupSession session = wizard.injectSessionForTest(admin, 3, ARENA_ID);
        Location coreBefore = deploy.getCoreLocation();

        ((PlayerMock) admin).setLocation(arenaWorld.getSpawnLocation().add(11, 0, 11));
        wizard.handle(admin, "confirm-core");
        capture();

        assertTrue(said("setup.stale-step"), "第 3 步的[设为核心]属于第 2 步，必须被识别为过期按钮并给出跳转");
        assertFalse(said("setup.unknown"), "用户点的是自己刚收到的按钮，不该回「未知的部署命令」");
        assertEquals(3, session.step, "被拒的点击不许推进步骤");
        assertEquals(coreBefore, deploy.getCoreLocation(), "被拒的点击绝不许把核心搬到管理员脚下");
    }

    @Test
    @DisplayName("点两次[设为核心]：第二次被拒，核心不会二次移动")
    void doubleCoreClickOnlyAppliesOnce() {
        SetupWizard.SetupSession session = wizard.injectSessionForTest(admin, 2, ARENA_ID);

        wizard.handle(admin, "confirm-core");
        capture();
        Location firstCore = deploy.getCoreLocation();
        assertEquals(3, session.step);

        ((PlayerMock) admin).setLocation(arenaWorld.getSpawnLocation().add(21, 0, 21));
        wizard.handle(admin, "confirm-core");
        capture();

        assertTrue(said("setup.stale-step"), "同一个按钮点第二次必须被识别为过期");
        assertEquals(3, session.step, "第二次点击不许再推进");
        assertEquals(firstCore, deploy.getCoreLocation(), "第二次点击不许再把核心搬一次");
    }

    // ------------------------------------------------------------ 世界未确认时的保守拒绝

    @Test
    @DisplayName("第 1 步（世界未确认）点[设为等待区]必须被拒，且不写盘")
    void waitingAreaRejectedBeforeWorldConfirmed() {
        SetupWizard.SetupSession session = wizard.injectSessionForTest(admin, 1, ARENA_ID);
        Location before = deploy.getWaitingLocation();

        wizard.handle(admin, "confirm-waiting");
        capture();

        assertTrue(said("setup.stale-step"), "第 1 步必须给出过期按钮的解释");
        assertFalse(said("setup.unknown"), "第 1 步应当走 stale-step 分支说人话，而不是「未知的部署命令」");
        assertEquals(1, session.step);
        assertEquals(before, deploy.getWaitingLocation(), "第 1 步绝不许写等待区坐标");
    }

    // ------------------------------------------------------------ MockBukkit 限制的绕行

    /**
     * 把 arena 的 GameManager.gameWorld 换成拦截过 spawnParticle 的 spy。
     *
     * <p><b>为什么必须这么做</b>：MockBukkit 4.45 的 WorldMock 对 spawnParticle 的所有重载
     * 都抛 UnimplementedOperationException，而 Surefire 默认把它记为 skip 而不是 failure ——
     * 于是测试「通过」了，玩家点击路径却一步都没跑完，等于退回纯谓词测试。
     * 本项目 LocationParticlePreviewTest 已用同一手法解决过。</p>
     *
     * <p>gameWorld 是私有字段，构造后无 setter，所以用反射替换。</p>
     */
    private World stubParticleWorld(World world) {
        WorldMock real = (WorldMock) world;
        WorldMock spied = Mockito.spy(real);
        Mockito.doNothing().when(spied).spawnParticle(
                any(Particle.class), any(Location.class), anyInt(),
                anyDouble(), anyDouble(), anyDouble(), anyDouble());
        try {
            java.lang.reflect.Field f = GameManager.class.getDeclaredField("gameWorld");
            f.setAccessible(true);
            f.set(arena().getGameManager(), spied);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法注入 spy 世界：MockBukkit 的 spawnParticle 限制无法绕过", e);
        }
        return spied;
    }
}