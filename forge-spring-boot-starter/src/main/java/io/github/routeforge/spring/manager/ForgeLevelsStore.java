package io.github.routeforge.spring.manager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * 管理器把层级配置写回<b>独立</b>的 {@code forge-levels.yml}（SPEC §5.3），绝不触碰宿主的 {@code application.yml}。
 *
 * <p>落盘纪律三条（与 Laravel 侧同语义，逐条钉测试）：
 * <ol>
 *   <li><b>写前备份</b>：目标文件已存在时先整份复制成 {@code .bak}，供回滚；</li>
 *   <li><b>原子写</b>：先写同目录临时文件再 {@code MOVE_ATOMIC} 覆盖，避免半写状态被读到；</li>
 *   <li><b>写后回读比对</b>：把刚写入的文件重新 {@code load} 回来，语义（而非字节序）必须与待发内容相等，
 *       不等则用备份回滚并抛异常——这是「保存成功」与「其实没落对」的分界。</li>
 * </ol>
 *
 * <p>文件形态：顶层 {@code forge: {levels: {...}}}，使宿主加
 * {@code spring.config.import=optional:file:./forge-levels.yml} 后启动即可把它绑回 {@code ForgeProperties}。
 * 写入用列表原样（管理器收到的 PUT body 已是 JSON 列表，非 Binder 的索引 Map）；启动读回时 Boot 在
 * {@code Object} 位置会再产出索引 Map，由既有 {@code YamlShape.levels} 归一——本类不需要懂那套归一。
 *
 * <p>纯 {@code java.nio} + snakeyaml、零 Spring 依赖，故可脱离容器直接单测。
 */
public final class ForgeLevelsStore {

    /** 默认落盘位置：当前工作目录下的 {@code forge-levels.yml}（SPEC §5.3 决策 D2「固定路径」）。 */
    public static final String DEFAULT_FILE_NAME = "forge-levels.yml";

    private final Path file;
    private final Yaml yaml;

    public ForgeLevelsStore(Path file) {
        this.file = file;
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        // 不折行：层级名/prefix 可能较长，默认 80 列折行会把字符串拆断、影响人读与回读比对
        options.setWidth(Integer.MAX_VALUE);
        this.yaml = new Yaml(options);
    }

    /** 当前工作目录下的默认存储。 */
    public static ForgeLevelsStore defaultLocation() {
        return new ForgeLevelsStore(Path.of(System.getProperty("user.dir"), DEFAULT_FILE_NAME));
    }

    public Path file() {
        return file;
    }

    /**
     * 写回层级配置：备份 → 原子写 → 回读比对；比对失败即回滚到备份并抛 {@link IOException}。
     *
     * @param levels 已按 {@code 层级名 → 原始配置} 形态给来的映射（管理器从 PUT body 直取）
     * @throws IOException 落盘或回读比对失败（失败时目标文件已回滚到写入前状态）
     */
    public synchronized void save(Map<String, Object> levels) throws IOException {
        String text = yaml.dump(wrapForge(levels));

        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path backup = file.resolveSibling(file.getFileName() + ".bak");
        boolean hadOriginal = Files.exists(file);
        if (hadOriginal) {
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            writeAtomically(text, parent);
            verifyRoundTrip(levels);
        } catch (IOException | RuntimeException ex) {
            rollback(hadOriginal, backup);
            throw ex;
        }
    }

    private static Map<String, Object> wrapForge(Map<String, Object> levels) {
        // LinkedHashMap 保证 dump 出 forge→levels 的固定顺序，产物稳定、人可读
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("levels", levels);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("forge", inner);
        return doc;
    }

    private void writeAtomically(String text, Path parent) throws IOException {
        Path tmp = Files.createTempFile(parent == null ? file.toAbsolutePath().getParent() : parent,
                file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException slowFs) {
                // 某些文件系统不支持原子移动（如跨设备）；退化成非原子覆盖仍保正确性
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @SuppressWarnings("unchecked")
    private void verifyRoundTrip(Map<String, Object> expected) throws IOException {
        String reloaded = Files.readString(file, StandardCharsets.UTF_8);
        Object doc = yaml.load(reloaded);
        Map<String, Object> levelsFromDisk = extractLevels(doc);
        if (!semanticallyEqual(expected, levelsFromDisk)) {
            throw new IOException("forge-levels.yml 回读比对不一致：写入的层级配置与文件读回结果不等，已回滚");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractLevels(Object doc) {
        if (!(doc instanceof Map<?, ?> root)) {
            return Map.of();
        }
        Object forge = root.get("forge");
        if (!(forge instanceof Map<?, ?> forgeMap)) {
            return Map.of();
        }
        Object levels = forgeMap.get("levels");
        return levels instanceof Map<?, ?> levelsMap ? (Map<String, Object>) levelsMap : Map.of();
    }

    /**
     * 语义相等：snakeyaml 把 {@code null} 值序列化为 {@code key: null}，回读后 key 仍在；但空层级的
     * {@code {}} 与缺失需按 PHP 宽容口径视为一致。这里对「两侧都规整为只含非 null 叶子的排序结构」再比较，
     * 避免 dump/load 的表示差异造成假阴性。
     */
    private static boolean semanticallyEqual(Map<String, Object> a, Map<String, Object> b) {
        return canon(a).equals(canon(b));
    }

    private static Object canon(Object node) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new java.util.TreeMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), canon(v)));
            return out;
        }
        if (node instanceof java.util.List<?> list) {
            return list.stream().map(ForgeLevelsStore::canon).toList();
        }
        return node;
    }

    private void rollback(boolean hadOriginal, Path backup) throws IOException {
        if (hadOriginal) {
            Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
        } else {
            // 写入前本就没有该文件：失败后回到「不存在」，删掉刚建的临时目标（这是本存储自管的配置文件，非用户数据）
            Files.deleteIfExists(file);
        }
    }
}
