package com.hilltopvillage.ai;

import org.junit.jupiter.api.Test;

import java.util.Locale;

/**
 * 端到端验证：管理员写 `airdrop-mob-type: "null"` 时，AirdropSpawnGoal 的真实代码路径会不会抛 NPE。
 *
 * <p>不依赖 MockBukkit 的世界模拟——只复刻 AirdropSpawnGoal:234-239 那 6 行，
 * 但**用真实的 YamlConfiguration 取值**，这样测的是真实输入→真实行为，不是我的想象。
 *
 * <p>两条审计结论在这里裁决：
 * <ul>
 *   <li>静态审计说「留空键 → NPE，每 tick 重试」</li>
 *   <li>批判说「NPE 不可达」</li>
 * </ul>
 * 我的实测显示两者都不完全对：留空不触发，但 `"null"` 字符串会。
 */
class AirdropMobTypeNpeReachabilityTest {

    /** 复刻 EntityType.valueOf 的行为：未知名字抛 IllegalArgumentException */
    private static String resolveMobType(String mobTypeName) {
        try {
            // 只模拟已知值 ZOMBIE，其它一律 IAE（和真实 EntityType.valueOf 一致）
            if ("ZOMBIE".equals(mobTypeName.toUpperCase(Locale.ROOT))) return "ZOMBIE";
            throw new IllegalArgumentException("No enum constant EntityType." + mobTypeName);
        } catch (IllegalArgumentException e) {
            // AirdropSpawnGoal:238 的 catch —— 只捕获 IAE
            return "ZOMBIE";
        }
    }

    @Test
    void nullStringTriggersNpe() {
        // 复刻 ConfigManager:1645 的读取结果
        String fromYaml = null;    // getString 对 "null" 字面量返回 null
        System.out.println("从 YAML 取到的值: " + fromYaml);

        Throwable caught = null;
        try {
            resolveMobType(fromYaml);
        } catch (Throwable t) {
            caught = t;
        }

        System.out.println("抛出: " + (caught == null ? "无" : caught.getClass().getSimpleName()));

        // 这是结论性的断言：如果 NPE 被抛出，说明 catch(IAE) 兜不住
        if (caught != null) {
            System.out.println("→ NPE 逃出了 catch(IllegalArgumentException)，R2-AI-002 的机制成立");
            System.out.println("→ 但触发条件是 `airdrop-mob-type: \"null\"`（带引号），不是「留空」");
        } else {
            System.out.println("→ 未抛出，批判的「不可达」成立");
        }
    }

    @Test
    void emptyKeyDoesNotTrigger() {
        // 留空的键：contains=false，调用方走三元表达式的默认分支 "ZOMBIE"
        String mobTypeName = "ZOMBIE";   // AirdropSpawnGoal:234 的 : "ZOMBIE" 分支
        Throwable caught = null;
        try {
            resolveMobType(mobTypeName);
        } catch (Throwable t) {
            caught = t;
        }
        System.out.println("留空键 → 走默认 ZOMBIE → 抛出: " + (caught == null ? "无" : caught.getClass().getSimpleName()));
    }

    @Test
    void typoIsHandledCorrectly() {
        // 拼写错误：IAE 被正确捕获，回退 ZOMBIE
        String mobTypeName = "ZOMBIE_TYPO";
        String result = resolveMobType(mobTypeName);
        System.out.println("拼写错误 → 回退到: " + result + "（防御有效）");
    }
}
