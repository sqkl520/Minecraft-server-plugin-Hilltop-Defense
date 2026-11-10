package com.hilltopvillage.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.File;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import com.hilltopvillage.HilltopVillagePlugin;

/**
 * Mockito 可用性自检。
 *
 * <p><b>为什么需要单独一条</b>：Mockito 5 在 JDK 21 上走 inline mock maker，
 * 依赖 {@code byte-buddy-agent} 生成 ByteBuddy agent。本项目里
 * {@code byte-buddy} 由 MockBukkit 传递进来（1.17.5），而 {@code byte-buddy-agent}
 * 是本 pom 显式声明的 1.14.10 —— <b>两个版本号不一致</b>。这种 skew 在部分
 * JDK 上会抛 {@code MockitoInitializationException: Could not initialize inline Byte Buddy mock maker}。
 * 本测试把这个风险钉死：只要 agent 与 byte-buddy 的组合在这台机器/JDK 上初始化不了，
 * 这里立刻红，而不是等到几十个业务测试各自以晦涩的方式炸掉。</p>
 *
 * <p>同时验证三件基础设施真正依赖的能力：</p>
 * <ol>
 *   <li>{@code mock(HilltopVillagePlugin.class)} 可行 —— {@code new HilltopVillagePlugin()}
 *       会抛 {@code IllegalStateException: JavaPlugin requires to be created by a valid classloader}，
 *       所以"轻量插件替身"只能靠 Mockito。</li>
 *   <li>spy 一个 MockBukkit 实体后能 {@code doNothing()} 覆盖未实现的方法
 *       （这是绕开 {@code setRemoveWhenFarAway} 等缺口的标准手法）。</li>
 *   <li>spy 后能 {@code doReturn()} 覆盖返回 {@code AttributeInstance} 的方法
 *       （{@code getAttribute(ATTACK_DAMAGE)} 在 MockBukkit 里会抛）。</li>
 * </ol>
 */
class MockitoAvailabilityProbeTest extends MockBukkitTestBase {

    @Test
    @DisplayName("Mockito: inline mock maker 在 JDK 21 上能初始化（byte-buddy 与 agent 版本 skew 的守门测试）")
    void inlineMockMakerInitializes() {
        // 只要这一行不抛 MockitoInitializationException，就说明 agent 挂载成功。
        Runnable runnable = mock(Runnable.class);
        runnable.run();
        verify(runnable).run();

        assertNotNull(runnable, "mock 返回 null —— mock maker 未工作");
    }

    @Test
    @DisplayName("Mockito: 可以 mock HilltopVillagePlugin 并只桩选需要的方法")
    void pluginCanBeMocked() {
        File folder = new File(System.getProperty("java.io.tmpdir"), "hv-mockito-probe-" + UUID.randomUUID());
        assertTrue(folder.mkdirs() || folder.isDirectory(), "无法创建临时目录");

        HilltopVillagePlugin plugin = mock(HilltopVillagePlugin.class);
        when(plugin.getDataFolder()).thenReturn(folder);
        when(plugin.isEnabled()).thenReturn(true);

        assertEquals(folder, plugin.getDataFolder(), "getDataFolder 桩未生效");
        assertTrue(plugin.isEnabled(), "isEnabled 桩未生效");
        // 未桩的方法返回 Mockito 默认值（null / false），而不是抛异常 —— 这正是
        // "轻量替身"能work的前提
        assertEquals(null, plugin.getConfigManager(), "未桩方法应返回 null");
    }

    @Test
    @DisplayName("Mockito: spy 一个 WorldMock 生成的实体后，doNothing 能覆盖 MockBukkit 未实现的方法")
    void spiedEntityCanBypassUnimplementedMethods() {
        WorldMock world = server.addSimpleWorld("hilltop_arena1");
        Zombie zombie = world.spawn(new Location(world, 0, 64, 0), Zombie.class);

        assertNotNull(zombie, "未能生成 Zombie");
        // 先证明真实 MockBukkit 实体确实抛 —— 否则下面的"绕过"证明了空气
        boolean rawThrows = false;
        try {
            zombie.setRemoveWhenFarAway(false);
        } catch (RuntimeException e) {
            rawThrows = true;
        }
        assertTrue(rawThrows,
                "预期 MockBukkit 的 setRemoveWhenFarAway 抛未实现异常；它若已实现，"
                        + "本夹具里的 spy 绕过就不再必要，可以简化");
        LivingEntity spied = spy((LivingEntity) zombie);
        doNothing().when(spied).setRemoveWhenFarAway(anyBoolean());

        // 不抛即通过
        spied.setRemoveWhenFarAway(false);
        verify(spied).setRemoveWhenFarAway(false);
    }

    @Test
    @DisplayName("Mockito: doReturn 能覆盖 getAttribute，返回可用的 AttributeInstance 替身")
    void spiedEntityCanStubAttribute() {
        WorldMock world = server.addSimpleWorld("hilltop_arena1");
        Zombie zombie = world.spawn(new Location(world, 0, 64, 0), Zombie.class);

        AttributeInstance stub = mock(AttributeInstance.class);
        when(stub.getValue()).thenReturn(3.0);

        LivingEntity spied = spy((LivingEntity) zombie);
        // 必须用 doReturn —— when(...).thenReturn(...) 会在桩打开时真的调用 getAttribute，
        // 而 MockBukkit 的那个方法会抛，导致桩还没装上就先炸。
        doReturn(stub).when(spied).getAttribute(Attribute.ATTACK_DAMAGE);

        AttributeInstance resolved = spied.getAttribute(Attribute.ATTACK_DAMAGE);
        assertNotNull(resolved, "getAttribute 未被桩覆盖");
        assertEquals(3.0, resolved.getValue(), 1.0e-9, "桩返回的 AttributeInstance 未被使用");
        assertFalse(resolved instanceof org.mockbukkit.mockbukkit.attribute.AttributeInstanceMock,
                "拿到的仍是 MockBukkit 的真实实现，说明 doReturn 没生效");
    }
}
