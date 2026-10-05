package io.github.routeforge.core;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.routeforge.core.config.LevelsConfig;
import io.github.routeforge.core.dto.RouteInfo;
import io.github.routeforge.core.tier.RouteClassifier;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试专用：读取 PHP 参照器产物，并把 JSON 里的输入形态还原成核心层的 Java 对象。
 *
 * <p>还原逻辑集中一处，保证「同一份 fixture 被不同 oracle 测试以同一套构造方式消费」——
 * 各测试自己写一份构造代码时，很容易在字段顺序或 null 处理上各自发挥，对等断言就失真了。
 */
public final class PhpFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PhpFixtures() {
    }

    public static JsonNode read(String fileName) {
        try {
            Path path = Path.of(System.getProperty("forge.fixtures.dir"), fileName);
            JsonNode root = MAPPER.readTree(path.toFile());
            assertThatProvenanceIsReal(root, fileName);
            return root;
        } catch (IOException e) {
            throw new IllegalStateException("无法读取 fixture（先跑 fixtures/php 下的 oracle 生成）：" + e.getMessage(), e);
        }
    }

    private static void assertThatProvenanceIsReal(JsonNode root, String fileName) {
        JsonNode provenance = root.get("provenance");
        if (provenance == null) {
            throw new AssertionError(fileName + " 缺少 provenance：fixture 必须由 oracle 生成，不是手写样例");
        }
    }

    public static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(item -> out.add(item.asText()));
        return out;
    }

    public static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** fixture 里的一条路由信息 → {@link RouteInfo}。 */
    public static RouteInfo toRoute(JsonNode node) {
        return new RouteInfo(
                textOrNull(node.get("name")),
                node.get("uri").asText(),
                strings(node.get("methods")),
                strings(node.get("parameters")),
                toMap(node.get("parameterDefaults")),
                strings(node.get("middleware")),
                textOrNull(node.get("tier")),
                strings(node.get("forgeAliases")),
                null);
    }

    public static List<RouteInfo> toRoutes(JsonNode array) {
        List<RouteInfo> out = new ArrayList<>();
        array.forEach(node -> out.add(toRoute(node)));
        return out;
    }

    public static LevelsConfig toLevels(JsonNode node) {
        return new LevelsConfig(MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        }));
    }

    /** 层级配置的键集合（保持声明顺序）；PHP 空关联数组序列化成 []，此处按空表处理。 */
    public static Map<String, Object> toMap(JsonNode node) {
        if (node == null || node.isNull() || node.isArray()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    /** JsonNode → 纯 Java 结构（Map/List/String/Number/Boolean/null），供与生产代码产出的结构直接 equals。 */
    public static Object toObject(JsonNode node) {
        return MAPPER.convertValue(node, Object.class);
    }

    /** classifier 描述符 → 回调，与 oracle 里 PHP 闭包的三种形态一一对应。 */
    public static RouteClassifier toClassifier(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.has("return")) {
            String value = textOrNull(node.get("return"));
            return route -> value;
        }
        if (node.has("returnRaw")) {
            Object raw = MAPPER.convertValue(node.get("returnRaw"), Object.class);
            return route -> raw;
        }
        if (node.has("throw")) {
            String message = node.get("throw").asText();
            return route -> {
                throw new RuntimeException(message);
            };
        }
        throw new IllegalStateException("未知 classifier 描述符：" + node);
    }
}
