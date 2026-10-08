package io.github.routeforge.spring.manager;

import io.github.routeforge.core.exception.ForgeRuntimeException;
import io.github.routeforge.spring.registry.ForgeRouteRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 管理器三件套（SPEC §5.3）：{@code GET /_forge/manager}（零构建静态页）、
 * {@code GET /_forge/manager/api/routes}（层级 × 路由视图）、{@code PUT /_forge/manager/api/config}（写回并热生效）。
 *
 * <p>只在 {@code debug=true} 且 {@code forge.manager.enabled=true} 时被装配（见 {@code ForgeAutoConfiguration}
 * 的门禁条件），再叠加 {@link ManagerAccessGuard} 的 IP 白名单，共三道（SPEC §4.7）。
 *
 * <p>保存走决策 D1「热生效」：{@link ForgeLevelsStore#save 写文件}（备份 + 回读比对，失败内部回滚）成功后，
 * 再 {@link ForgeRouteRegistry#updateLevels 换内存层级定义} 与 {@link ForgeRouteRegistry#clearAllCache 清缓存}，
 * 于是同一次请求后摘要/层级/命令行都按新层级重算，无需重启。次序刻意为「先落盘成功、再换内存」——落盘失败
 * 绝不能让内存与文件分叉。
 *
 * <p>取远程地址用 {@link HttpServletRequest#getRemoteAddr()}：Spring MVC 里读 socket 来源 IP 绕不开 Servlet 类型，
 * 而整个自动装配是 {@code @ConditionalOnWebApplication(SERVLET)}——只有 servlet-web 应用才会加载到本类，
 * servlet-api 恒由宿主 webmvc starter 提供（编译期 {@code compileOnly}）。反向代理场景须宿主启用
 * {@code ForwardedHeaderFilter}，否则 {@code getRemoteAddr()} 反映的是代理而非真实来源（SPEC §5.3）。
 * 错误体不复用 {@code RF_BE_0xx}（那是跨语言元信息契约专用），管理器的请求/写盘错误用 HTTP 状态码 +
 * {@code {"error":{"message":…}}}。
 */
@RestController
@RequestMapping(ForgeRouteRegistry.MANAGER_URI_PREFIX)
public class ForgeManagerController {

    private final ForgeRouteRegistry registry;
    private final ForgeLevelsStore store;
    private final ManagerAccessGuard guard;
    private final String page;

    public ForgeManagerController(ForgeRouteRegistry registry, ForgeLevelsStore store, ManagerAccessGuard guard) {
        this.registry = registry;
        this.store = store;
        this.guard = guard;
        this.page = loadPage();
    }

    /** 管理器页面（自包含 HTML，无外部构建/CDN 依赖）。 */
    @GetMapping(produces = "text/html;charset=UTF-8")
    public String page(HttpServletRequest request) {
        requireAllowed(request);
        return page;
    }

    /** 层级 × 路由全量视图（含守卫标签），供页面渲染。数据源＝注册表 {@code allRoutesWithTiers()}。 */
    @GetMapping(value = "/api/routes", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> routes(HttpServletRequest request) {
        requireAllowed(request);
        try {
            return ResponseEntity.ok(registry.allRoutesWithTiers());
        } catch (ForgeRuntimeException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("code", e.code());
            error.put("message", e.getMessage());
            return ResponseEntity.status(e.httpStatus()).body(Map.of("error", error));
        }
    }

    /**
     * 保存层级配置：请求体形如 {@code {"levels": { 层级名: {...} }}}。写盘成功 → 热换内存 → 清缓存。
     *
     * <p>缺 {@code levels} 或其非映射 → 400（不臆造 RF_BE 码）；写盘/回读失败 → 500（存储层已回滚到上一版）。
     */
    @PutMapping(value = "/api/config", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireAllowed(request);
        if (body == null || !(body.get("levels") instanceof Map<?, ?>)) {
            return badRequest("请求体需含对象形态的 levels 字段");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> levels = (Map<String, Object>) body.get("levels");
        try {
            store.save(levels);
        } catch (IOException | RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", Map.of("message", "写回 forge-levels.yml 失败：" + e.getMessage())));
        }
        registry.updateLevels(levels);
        registry.clearAllCache();

        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("ok", true);
        ok.put("level_names", registry.levelNames());
        ok.put("file", store.file().toAbsolutePath().toString());
        return ResponseEntity.ok(ok);
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", Map.of("message", message)));
    }

    private void requireAllowed(HttpServletRequest request) {
        String remoteIp = resolveRemoteIp(request);
        if (!guard.allows(remoteIp)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "来源不在 forge.manager.allowed-ips 白名单内");
        }
    }

    private static String resolveRemoteIp(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    private static String loadPage() {
        try (InputStream in = ForgeManagerController.class.getResourceAsStream("manager.html")) {
            if (in == null) {
                throw new IllegalStateException("管理器静态页 manager.html 未在 classpath 上");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取 manager.html 失败", e);
        }
    }
}
