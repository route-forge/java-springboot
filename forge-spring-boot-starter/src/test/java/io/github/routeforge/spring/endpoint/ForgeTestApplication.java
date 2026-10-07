package io.github.routeforge.spring.endpoint;

import io.github.routeforge.spring.annotation.ForgeRoute;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 端点契约测试用的最小宿主应用。
 *
 * <p>刻意只放三条路由，每条都对应一个会被断言检查的行为：
 * <ul>
 *   <li>{@code admin.users.index} —— 显式 tier 的命名路由，进层级端点与摘要计数；</li>
 *   <li>{@code client.orders.list} —— 走 {@code path} 别名透传，验证 URI 模板原样下发；</li>
 *   <li>一条<b>未命名</b>且被 {@code manage} 层级的 match 命中的路由 —— 它是最关键的一条：
 *       宽松模式下它不该出现在任何元信息里，严格模式下它是<b>唯一</b>一条 {@code missing_name} 违规。
 *       用它同时验证「包自身端点被排除」：若 {@code /_forge/routes/**} 没被排除，违规数就会 &gt; 1，
 *       严格模式下端点必 500（AGENTS 铁律 3）。</li>
 * </ul>
 */
@SpringBootApplication
public class ForgeTestApplication {

    @Controller
    static class AdminController {

        @ForgeRoute(name = "admin.users.index", path = "/admin/users", method = RequestMethod.GET, tier = "admin")
        @ResponseBody
        public String index() {
            return "";
        }
    }

    @Controller
    static class ClientController {

        @ForgeRoute(name = "client.orders.list", value = "/client/orders", method = RequestMethod.GET,
                tier = "client")
        @ResponseBody
        public String list() {
            return "";
        }
    }

    @Controller
    static class UnnamedManageController {

        /** 没有名字、但被 manage 层级的 match 命中：进不了元信息，严格模式下必须被报出来。 */
        @GetMapping("/manage/reports")
        @ResponseBody
        public String reports() {
            return "";
        }
    }

    @Controller
    static class MatchedManageController {

        /**
         * 命名、但<b>不给 tier</b>：只能靠 {@code forge.levels.manage.match.prefix} 归级。
         *
         * <p>这条是「配置匹配通道」的唯一验证点——admin/client 两条都带显式 tier，
         * 删掉本条的话 match 规则绑定坏掉也没人发现（先前就是如此）。
         */
        @ForgeRoute(name = "manage.reports.index", path = "/manage/reports/index", method = RequestMethod.GET)
        @ResponseBody
        public String reportsIndex() {
            return "";
        }
    }
}
