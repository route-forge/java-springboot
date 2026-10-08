package io.github.routeforge.spring.manager;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

/**
 * {@link ForgeLevelsStore} 的落盘纪律测试：写前备份、原子写、写后回读比对（happy path）。
 * 回滚分支由变异检验覆盖（见方法内注释指向），不靠给生产代码塞测试钩子。
 */
class ForgeLevelsStoreTest {

    private static Map<String, Object> levelsAdminManage() {
        Map<String, Object> adminMatch = ordered("prefix", List.of("/admin"), "middleware", List.of("role:admin"));
        Map<String, Object> admin = ordered(
                "description", "后台",
                "match", adminMatch,
                "load", "lazy");
        Map<String, Object> manage = ordered("description", "管理", "match", ordered("prefix", List.of("/manage")));
        Map<String, Object> levels = new LinkedHashMap<>();
        levels.put("admin", admin);
        levels.put("manage", manage);
        return levels;
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @DisplayName("首次保存：文件落盘、内容为 forge.levels 结构、回读语义等于输入")
    void saveFreshWritesForgeLevelsYaml(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("forge-levels.yml");
        Map<String, Object> levels = levelsAdminManage();

        new ForgeLevelsStore(file).save(levels);

        assertThat(file).exists();
        assertThat(file.resolveSibling("forge-levels.yml.bak"))
                .as("首次保存前无原文件，不应产生备份")
                .doesNotExist();

        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        assertThat(doc).containsKey("forge");
        @SuppressWarnings("unchecked")
        Map<String, Object> forge = (Map<String, Object>) doc.get("forge");
        assertThat(forge).containsKey("levels");
        @SuppressWarnings("unchecked")
        Map<String, Object> onDisk = (Map<String, Object>) forge.get("levels");
        assertThat(onDisk).isEqualTo(levels);
    }

    @Test
    @DisplayName("覆盖保存：写前先备份原文件，备份内容等于上一版")
    void saveOverExistingCreatesBackup(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("forge-levels.yml");
        ForgeLevelsStore store = new ForgeLevelsStore(file);

        store.save(levelsAdminManage());
        String firstVersion = Files.readString(file, StandardCharsets.UTF_8);

        // 改成只留 admin（去掉 manage 层级）再保存
        Map<String, Object> onlyAdmin = ordered("admin", levelsAdminManage().get("admin"));
        store.save(onlyAdmin);

        Path backup = file.resolveSibling("forge-levels.yml.bak");
        assertThat(backup).exists();
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(firstVersion);

        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) new Yaml().load(Files.readString(file, StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> onDisk = (Map<String, Object>) ((Map<String, Object>) doc.get("forge")).get("levels");
        assertThat(onDisk).containsOnlyKeys("admin");
    }

    @Test
    @DisplayName("中文 description 不被转义、UTF-8 落盘可回读（编码铁律）")
    void savesCjkUnescaped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("forge-levels.yml");
        Map<String, Object> levels = ordered("admin", ordered("description", "客户与订单中心", "load", "eager"));

        new ForgeLevelsStore(file).save(levels);

        String text = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(text).as("中文应原样出现，不应被 dump 成 \\u 转义").contains("客户与订单中心");
    }
}
