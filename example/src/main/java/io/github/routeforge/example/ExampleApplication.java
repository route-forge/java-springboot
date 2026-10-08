package io.github.routeforge.example;

import io.github.routeforge.spring.annotation.ForgeRoute;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * Route Forge Spring Boot 示例后端（P6）。演示前端凭「层级名 + 路由名」消费的完整链路：
 *
 * <ul>
 *   <li>{@code admin.users.index} —— 显式 {@code tier=admin} 的命名路由；</li>
 *   <li>{@code client.orders.list} —— 显式 {@code tier=client}；</li>
 *   <li>{@code manage.reports.index} —— <b>不给 tier</b>，只靠 {@code application.yml} 里
 *       {@code forge.levels.manage.match.prefix: /manage} 归级（配置匹配通道的演示，对应 AGENTS 铁律 1）。</li>
 * </ul>
 *
 * <p>启动后 {@code GET /_forge/routes} 出摘要、{@code GET /_forge/routes/<level>} 出该层级路由明细，
 * 前端 {@code @route-forge/*} 据此构造 URL。这是 starter 首次在真实 Boot 应用（内嵌 Tomcat + Jackson）里被端到端跑通。
 */
@SpringBootApplication
public class ExampleApplication {

    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(ExampleApplication.class, args);
    }

    @RestController
    @RequestMapping("/admin/users")
    static class AdminController {

        @ForgeRoute(name = "admin.users.index", method = RequestMethod.GET, tier = "admin")
        public List<Map<String, Object>> index() {
            return List.of(Map.of("id", 1, "name", "alice"), Map.of("id", 2, "name", "bob"));
        }

        /** URI 模板 + 参数名演示：{@code /admin/users/{id}}，前端凭 {@code admin.users.show} + {id} 拼 URL。 */
        @ForgeRoute(name = "admin.users.show", path = "{id}", method = RequestMethod.GET, tier = "admin")
        public Map<String, Object> show() {
            return Map.of("id", 1, "name", "alice");
        }
    }

    @RestController
    static class ClientController {

        @ForgeRoute(name = "client.orders.list", path = "/client/orders", method = RequestMethod.GET, tier = "client")
        public List<Map<String, Object>> list() {
            return List.of(Map.of("orderNo", "A-1001", "status", "paid"));
        }
    }

    @RestController
    static class ManageController {

        /** 刻意不给 tier：由 {@code forge.levels.manage.match.prefix} 命中归入 manage 层级。 */
        @ForgeRoute(name = "manage.reports.index", path = "/manage/reports", method = RequestMethod.GET)
        public List<Map<String, Object>> reports() {
            return List.of(Map.of("report", "daily-sales"));
        }
    }
}
