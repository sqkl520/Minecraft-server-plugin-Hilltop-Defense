package com.hilltopvillage.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

/**
 * 深挖：YamlConfiguration 里 contains==true 但 getString==null 的具体机制。
 *
 * <p>上一轮测试发现 `airdrop-mob-type: "null"`（带引号）会得到 contains=true / getString=null。
 * 这个测试要判断：这是 YAML 加载器的怪癖，还是真的能让 AirdropSpawnGoal 抛 NPE。
 */
class YamlNullMechanismTest {

    @Test
    void inspectRaw() {
        String src = "monsters:\n  x:\n    airdrop-mob-type: \"null\"\n";
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader(src));
        String path = "monsters.x.airdrop-mob-type";

        Object raw = y.get(path);
        System.out.println("raw.getClass() = " + (raw == null ? "null" : raw.getClass().getName()));
        System.out.println("raw            = " + raw);
        System.out.println("contains()     = " + y.contains(path));
        System.out.println("isSet()        = " + y.isSet(path));
        System.out.println("getString()    = " + y.getString(path));

        // 关键：raw 到底是什么？如果是 null 而 contains 返 true，说明 contains 不是简单判 null
    }

    @Test
    void tryOtherQuotedForms() {
        String[] cases = {
            "airdrop-mob-type: \"null\"",
            "airdrop-mob-type: 'null'",
            "airdrop-mob-type: \"\"",
            "airdrop-mob-type: ''",
            "airdrop-mob-type: \"~\"",
        };
        for (String c : cases) {
            String src = "monsters:\n  x:\n    " + c + "\n";
            YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader(src));
            String path = "monsters.x.airdrop-mob-type";
            Object raw = y.get(path);
            System.out.printf("%-32s raw=%-22s contains=%-5s getString=%s%n",
                c,
                raw == null ? "null" : raw.getClass().getSimpleName() + ":" + raw,
                y.contains(path),
                y.getString(path));
        }
    }

    @Test
    void whatDoesTheRealConfigDo() {
        // 真实的 game-settings.yml 是怎么写这个键的？如果它从不出现，那这条路径就是理论问题
        String[] realWorld = {
            "monsters:\n  flying-dropper:\n    airdrop-mob-type: ZOMBIE\n",
            "monsters:\n  flying-dropper:\n    airdrop-mob-type: \n",
        };
        for (String src : realWorld) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(new StringReader(src));
            String path = "monsters.flying-dropper.airdrop-mob-type";
            System.out.printf("contains=%-5s getString=%-8s  ← %s%n",
                y.contains(path), y.getString(path),
                src.replace("\n", "\\n"));
        }
    }
}
