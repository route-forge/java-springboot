# Route Forge — Spring Boot 适配功能规格说明书

> 本文件是 **Route Forge for Spring Boot** 的对外功能承诺。
> 跨语言共享的端点契约（`schemeVersion = 1`）与 Laravel 适配包逐字段一致，权威描述见
> `route-forge/php-laravel` 的 `.docs/SPEC.md`；本文件承载三件事：
> ① 契约的 Java 侧落地口径；② Spring 生态独有概念到 forge 语义的映射；③ Java 专属扩展与差异。
>
> 状态：P0 骨架。标注 `⟨Pn⟩` 的条目在对应阶段落地时补细节，未落地前不承诺。

## 1. 定位

给 Spring Boot + Vue/React SPA 项目提供与 Laravel 版等价的命名路由全链路方案：后端按层级（level/tier）
暴露路由元信息，前端凭 `层级名 + 路由名` 构造 URL 与发请求，类型由后端这个唯一真相源生成。

- 模块分层：`forge-core`（框架无关算法，零 Spring/Jackson 依赖）+ `forge-spring-boot-starter`（适配层）。
  对应家族的 `route-forge/common` 与 `route-forge/laravel`。
- 前端三包（`@route-forge/core|vue|react` ≥ 3.1.0）**零改动**：契约即插件点。

## 2. 对外端点契约（与 Laravel 版逐字段一致）

### 2.1 摘要端点 `GET {endpoint_prefix}`

```json
{
  "schemeVersion": 1,
  "levels": {
    "admin": { "description": "...", "load": "lazy", "route_count": 27,
               "route": { "uri": "/_forge/routes/admin", "methods": ["GET", "HEAD"] } }
  },
  "config": { "strict_mode": false, "endpoint_prefix": "/_forge/routes",
              "url_prefix": null, "cache_ttl": 3600 }
}
```

Java 侧落地口径：

- `levels` 与 `config` **必须存在**（前端 `auto-discovery` 对二者不做可选链，缺失即 TypeError）。
- `levels` 为按层级名索引的对象；`unassigned` 特殊层级恒定存在（`strict_mode=true` 时 `route_count` 为 0）。
- `endpoint_prefix` 下发值经规范化（前导 `/`、去尾 `/`），与端点实际注册路径、d.ts 文件头注释三处同源。
- `cache_ttl` 恒为 `int|null`：`null` 不缓存、`0` 永久、负值归一为 `null`。
- 字段名大小写混合是既有契约（`schemeVersion`/`route.uri`/`route.methods` 为 camel 或点分，`config.*` 为 snake），
  Java 侧用显式 `@JsonProperty` 钉死，不依赖命名策略。

### 2.2 层级端点 `GET {endpoint_prefix}/{level}`

```json
{ "level": "admin",
  "routes": { "admin.users.show": { "uri": "admin/users/{user}", "methods": ["GET","HEAD"],
                                    "parameters": ["user"], "parameter_defaults": {} } } }
```

- `routes` 按路由名索引；层级下无路由时序列化为 `{}`（**不得**输出 `[]`）。
- `methods` 大写；GET 映射补 `HEAD`（对齐 Laravel 行为）。
- URI 模板**必须剥离 Spring 正则约束**：`{user:\\d+}` → `{user}`。前端占位符正则为 `/\{([^{}]+)\}/g`，
  含冒号的占位符无法被替换，会原样残留在 URL 里。
- 可选段输出 Laravel 风格 `{page?}`，来源见 §4.3。

## 3. 配置项（`forge.*`）

前缀 `forge`，键名与 `config/forge.php` 保持 snake→kebab 的机械对应，便于跨语言迁移配置。

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `forge.levels.<name>.description` | `string` | `""` | 层级描述 |
| `forge.levels.<name>.match.prefix` | `string[]` | `[]` | URI 前缀，按段匹配 |
| `forge.levels.<name>.match.middleware` | `string[]` | `[]` | 中间件标签，见 §4.4 |
| `forge.levels.<name>.match.middleware-match` | `any`\|`all`\|DNF | `any` | DNF 为 `List<List<Integer>>` |
| `forge.levels.<name>.load` | `eager`\|`lazy` | `lazy` | 前端预加载提示 |
| `forge.levels.<name>.endpoint-middleware` | `string[]` | `[]` | 该层级端点访问要求，接 Spring Security |
| `forge.endpoint-prefix` | `string` | `/_forge/routes` | 端点前缀 |
| `forge.url-prefix` | `string?` | `null` | 下发给前端的 URL 前缀 |
| `forge.endpoint-middleware` | `string[]` | `[]` | 摘要端点访问要求 |
| `forge.cache-ttl` | `int?` | `3600` | 统一 TTL |
| `forge.cache-driver` | `memory`\|`redis` | `memory` | Java 侧驱动名，语义见 §4.6 |
| `forge.strict-mode` | `bool` | `false` | 严格模式 |
| `forge.scheme-version` | `int` | `1` | 摘要格式版本 |
| `forge.aliases` | `map<string,string>` | `{}` | 别名：键=别名，值=真实路由名 |
| `forge.manager.enabled` | `bool` | `false` | 管理器页面开关（Java 侧多一道显式开关） |
| `forge.manager.allowed-ips` | `string[]` | `[127.0.0.1, ::1]` | IP 白名单，`*` 放行、空=不限制 |

- 数组型配置**接受单值**（`forge.levels.admin.match.prefix=api/admin` 等价于单元素列表），与 PHP 侧归一口径一致。
- `classifier` 在 Java 侧是 bean（`RouteClassifier`），不进配置文件，因而管理器可安全保存 PHP 侧禁存的场景在这里天然消失。

## 4. Spring 概念 → forge 语义映射

### 4.1 路由名（Spring 无命名路由） ⟨P2⟩

三通道，优先级显式 > 派生：

1. `@ForgeRoute(name = "admin.users.show", ...)` —— 组合注解，meta-annotated `@RequestMapping`，
   `@AliasFor` 全量透传原生条件属性。
2. `@Forge(name = "admin.users.show")` —— 副注解，叠加在 `@GetMapping` 等原生 mapping 上（复杂条件逃生舱）。
3. `RouteNamingStrategy` bean —— 按 handler 类/方法派生，默认关闭。

三通道同时命中同一 handler 方法且给出不同事实 → 启动期 fail-fast（`RF_BE_010`），不静默择一。

### 4.2 层级（tier）

五级优先级与 Laravel 完全一致：方法级 tier（显式）> 类级/包级 `@ForgeTier`（继承，类覆盖包）>
`RouteClassifier` bean > `forge.levels.*.match` > `unassigned` 兜底。

### 4.3 URI 与参数归一化 ⟨P2⟩

- 剥离 `{name:regex}` 约束、`{*name}`/`{version}` 等模板变量原样输出但不进 `parameters`（Spring 特有的
  `**` 通配段保留字面，SPEC 注明前端不可参数化）。
- 可选路径参数：`@ForgeRoute(optional = {"page"}, defaults = {"page=1"})` → 输出 `xxx/{page?}` +
  `parameter_defaults`。声明的名字不在 URI 模板内 → fail-fast。
- 参数名来源是 URI 模板本身，不依赖 `-parameters`；`-parameters` 仍全仓强制（配置构造绑定需要）。

### 4.4 middleware（Spring 无逐路由中间件） ⟨P3⟩

- `match.middleware` 是**纯元数据标签**，来自 `@ForgeRoute(middleware = {...})` / `@Forge(middleware = {...})`，
  只参与层级归类，**不构成安全边界**；真实鉴权仍由宿主的 Spring Security 决定。
- 提供自检：`--forge:list` 与 `/api/routes` 里每条路由的 `middleware` 字段即该标签集合，便于人工核对与 Security 规则的一致性。

### 4.5 包自身路由排除 ⟨P2⟩

`forge.manager.*` / 层级与摘要端点自身一律不进任何元信息，两个维度：按注册来源（本包 controller）+
按规范化后的 `endpoint_prefix` 做 URI **段级**前缀排除。否则 `strict_mode=true` 时包会把自己的端点报成宿主的配置错误。

### 4.6 缓存 ⟨P3⟩

`ForgeCacheStore` SPI 自写（不用 Spring Cache 抽象），键 `route-forge:<level>` / `route-forge:summary` /
`route-forge:_keys` 索引；`forgetLevel` 必须连带失效 summary；debug 模式旁路读写但 `clear` 不旁路。

## 5. 工具链

### 5.1 CLI（`ApplicationRunner` 参数式） ⟨P4⟩

`--forge:list` / `--forge:types` / `--forge:clear`，选项与退出码语义对齐 artisan：
`--level=` `--json` `--unassigned` `--aliases` `--unnamed` `--out=`。
严格模式违规时：list 表格照常 + 红色清单 + 退出码 1；types **不产出产物** + 清单走 stderr + 退出码 1。

### 5.2 d.ts 生成 ⟨P4⟩

`TypeGenerator` 移植自 common，输出结构逐行对齐：文件头三行注释 → `ForgeLevel` 联合 →
`ForgeRouteName<L>` → `ForgeRouteMeta` → `declare module '@route-forge/core'` 的二级映射 →
`ForgeRoutes` 别名与四个工具类型。`method` 取首个非 HEAD 方法；`params` 类型恒 `string | number`；
`body` 仅 POST/PUT/PATCH 出现；`response` 恒 `unknown`；空层级输出 `key: { }`。

### 5.3 管理器页面 ⟨P5⟩

`GET /_forge/manager`（HTML，零构建依赖静态页）+ `GET /_forge/manager/api/routes` +
`PUT /_forge/manager/api/config`。双开关（`forge.manager.enabled` + 非生产 profile）+ IP 白名单。
保存只写独立 `forge-levels.yml`（写前备份、写后回读比对），绝不改宿主 `application.yml`。

### 5.4 首页内嵌摘要 ⟨P5⟩

`ForgeSummaryRenderer#html()` 产出 `<script>`（`window.__ROUTE_FORGE__` 一次性自删访问器，JSON 做 script-safe
转义）+ Thymeleaf 方言片段。与摘要端点同一 producer、复用同一缓存。

## 6. 错误码

沿用 `RF_BE_001`~`RF_BE_009` 与原文消息（跨语言一致），Java 侧不实现 007、另新增 010：

| code | 触发场景 | HTTP |
|---|---|---|
| `RF_BE_001` | 单条语义保留：`TierResolver.resolve()` 直接调用时，严格模式下命名路由未归级 | 500 |
| `RF_BE_002` | 请求的层级名不在 levels 配置中 | 404 |
| `RF_BE_003` | 配置的 `cache-driver` 不可用 | 500 |
| `RF_BE_004` | classifier 回调自身抛错（包装保留 cause） | 500 |
| `RF_BE_005` | 单条语义保留：严格模式下设了层级却无路由名 | 500 |
| `RF_BE_006` | classifier 返回的层级名不在 levels 配置中 | 500 |
| — | **`RF_BE_007` 在 Spring 侧无对应物**：该码源于 PHP 的 Registrar 析构期告警（尾部链式属性丢弃），
  Spring 的注解是声明期的、不存在「属性挂了但无人消费」的生命周期。码位保留、不复用、不实现 | — |
| `RF_BE_008` | 别名指向的路由名不存在（悬空别名） | 500 |
| `RF_BE_009` | 严格模式整表违规聚合（`missing_name` / `unassigned` 一次报全，`unresolved` 作信息附录） | 500 |
| `RF_BE_010` | **Java 专属**：命名三通道对同一 handler 方法给出冲突事实（详见 §4.1） | 500 |

`RF_BE_009` 的结构化 `violations` 仅在 `debug=true` 时随错误体下发（清单本身是宿主越界路由的名字与 URI 目录，
属内部结构信息），与 Laravel 侧 `APP_DEBUG` 口径同构。

## 7. 测试矩阵

| 维度 | 覆盖点 |
|---|---|
| 构建约定 | Java 21 基线、`-parameters`、UTF-8 默认字符集（`BuildConventionTest`，已落地） |
| 层级分配 | ⟨P1/P3⟩ 显式 / 类级包级继承 / classifier / match / 多命中取最后 / unassigned |
| 中间件匹配 | ⟨P1⟩ any / all / DNF（越界索引、空子句、未知模式降级 any）、prefix 按段 |
| 别名 | ⟨P1/P3⟩ 宏优先 config / 撞车忽略 / 悬空 RF_BE_008 / 跨层级铺开 / 计数不叠加 |
| 严格模式 | ⟨P3⟩ RF_BE_009 聚合一次报全、包自身路由豁免 |
| 端点响应 | ⟨P2⟩ 摘要与层级结构、空层级 `{}`、自定义前缀规范化、缓存命中、404 |
| Spring 特有 | ⟨P2/P3⟩ 组合注解映射生效、双通道冲突、正则剥离、可选参数 |
| CLI | ⟨P4⟩ 输出格式、退出码、违规不产出产物 |
| 跨语言对等 | ⟨P6⟩ PHP 实跑 golden fixture 逐字段断言 |

## 8. 版本

Java 适配自成一条版本线（当前 `0.1.0`），不跟随 PHP/npm 的版本号；`schemeVersion` 恒 `1`，
只在摘要响应格式发生不兼容变更时递增。
