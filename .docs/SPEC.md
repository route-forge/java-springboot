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

## 2.3 Java 侧与 Laravel 的形态差异（功能等价，形态从 Spring）

字段集与语义与 Laravel 适配包一一对应，只有下列形态点按 Spring Boot 的习惯走，不为了逐字节相同而改造 Spring：

| 项 | Laravel 侧 | 本包（Spring 侧） | 理由 |
|---|---|---|---|
| 路由 `uri` 前导斜杠 | 不带（`admin/users/{user}`） | 保留（`/admin/users/{user}`） | Spring 的 mapping 天然带 `/`，去掉是为了模仿参照实现，不是契约要求；前端两种都吃 |
| `schemeVersion` / 键名 / 空层级 `{}` / 剥正则 | —— | 完全一致 | 这些是前端消费契约，必须一致 |

## 3. 配置项（`forge.*`）

前缀 `forge`，键名与 `config/forge.php` 保持 snake→kebab 的机械对应，便于跨语言迁移配置。

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `forge.levels.<name>.description` | `string` | `""` | 层级描述 |
| `forge.levels.<name>.match.prefix` | `string[]` | `[]` | URI 前缀，按段匹配 |
| `forge.levels.<name>.match.middleware` | `string[]` | `[]` | 中间件标签（仅归类），见 §4.4 |
| `forge.levels.<name>.match.middleware-match` | `any`\|`all`\|DNF | `any` | DNF 为 `List<List<Integer>>` |
| `forge.levels.<name>.load` | `eager`\|`lazy` | `lazy` | 前端预加载提示 |
| `forge.levels.<name>.endpoint-middleware` | `string[]` | `[]` | 该层级端点访问要求，**声明值**，见 §4.4 |
| `forge.endpoint-prefix` | `string` | `/_forge/routes` | 端点前缀 |
| `forge.url-prefix` | `string?` | `null` | 下发给前端的 URL 前缀 |
| `forge.endpoint-middleware` | `string[]` | `[]` | 摘要端点访问要求，**声明值**，见 §4.4 |
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

Framework 7 实测事实（由 `SpringRoutingModelSpikeTest` 7 例钉成回归，SDK 升级若改动会当场失败）：

- `RequestMappingInfo#getName()` 公开可读，值即 `@RequestMapping(name=...)` 及其派生注解上的 name——
  所以「复用 Spring 原生 name 属性」这条路成立，不必借道 OpenAPI 的 operationId；
  未标注时给 **null**（不是空串），判缺省按 null 认；
- meta-annotated `@RequestMapping` 的**组合注解**能正常注册映射，`@AliasFor` 透传 `name / value / path /
  method / params / headers / consumes / produces / version` 全部生效（FW7 `@RequestMapping` 是这 9 个属性）；
- `version` 条件在**未配置 `ApiVersionStrategy` 的映射上会直接注册失败**
  （`API version specified, but no ApiVersionStrategy configured`）：本包照常透传它，
  但用不用由宿主自己配策略，forge 不代配；
- Spring 模板语法**拒绝** `{name?}`（`PatternParseException: Char '?' is not allowed in a captured variable name`），
  因此 Laravel 风格的可选标记只能由 `@ForgeRoute(optional=...)` 承载，适配层再拼进产物 URI；
- `PathPattern` 不再公开 `getVariableNames()`（只剩 `getPatternString()`），参数名一律由核心层自解析模板取得;
- GET 映射的**声明条件**只含 GET，但运行期 HEAD 请求实测能打通（Servlet 语义：`doHead` 走 `doGet` 并抑制响应体）。
  因此下发 `methods` 时给 GET 附上 `HEAD` 是**如实描述 Spring 的能力**，不是模仿 Laravel 的形态；
- 无 path 条件的映射（只按 `params`/`headers` 匹配）实测**只匹配根路径**：Spring 会把它 materialize 成
  `["", "/"]` 两个 pattern。二者是同一个地址，扫描时归一为一个 `/` 并去重——不去重就会产出两条记录，
  其中一条 URI 是空串；
- 一个映射带多个路径（`path = {"/a","/b"}`）展开成**多条**路由记录，与 Laravel「一条 URI 一条路由」的形态对齐；
- 未声明 method 的映射（`@RequestMapping("/x")`）实测任何方法都能匹配。forge 侧对这种路由下发完整标准方法集
  （GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS/TRACE），不写空数组——空数组会让前端 `pickMethod` 拿不到方法而报错，
  也与「不限方法」的真实语义相反。

命名三通道，优先级显式 > 派生：

1. `@ForgeRoute(name = "admin.users.show", ...)` —— 组合注解，meta-annotated `@RequestMapping`，
   `@AliasFor` 全量透传原生条件属性。
2. `@Forge(name = "admin.users.show")` —— 副注解，叠加在 `@GetMapping` 等原生 mapping 上（复杂条件逃生舱）。
3. `RouteNamingStrategy` bean —— 按 handler 类/方法派生，默认关闭。

宿主直接在 `@RequestMapping(name=...)` 上给的名字也认（等价第 1 通道的原生形态，不强制要求 forge 注解）。
三通道同时命中同一 handler 方法且给出不同事实 → 启动期 fail-fast（`RF_BE_010`），不静默择一。

### 4.2 层级（tier）

五级优先级与 Laravel 完全一致：方法级 tier（显式）> 类级/包级 `@ForgeTier`（继承，类覆盖包）>
`RouteClassifier` bean > `forge.levels.*.match` > `unassigned` 兜底。

### 4.3 URI 与参数归一化 ⟨P2⟩

`UriTemplate`（`forge-core`）是 **Java 侧独有部件，PHP 家族无对等实现**：Laravel 的 `Route::uri()` 不带
正则约束（约束走 `->where()`）、也没有 `{*path}` 捕获段，因此这里没有可参照的产物，只按前端消费契约自证
（前端占位符正则 `/\{([^{}]+)\}/g`：凡它替换不了的形态都不许下发）。

- **前导斜杠按 Spring 原样保留**（Java 侧与 Laravel 的形态差异，见 §2.3）：Spring 的 mapping 一律以 `/` 开头，
  本包不为了对齐 Laravel 的相对形态而去掉它——契约以 Spring 习惯为准，前端对两种形态都能正确拼接；
- 参数名由本类自解析模板取得，且必须按**花括号深度**配对：`{id:\d{4}}` 里的 `{4}` 是量词，
  按「第一个右花括号」切会把名字读成 `id:\d`；
- **反斜杠转义必须整对吞掉**：实测 Spring 接受 `{code:[a-z\}]+}`（要匹配字面 `}` 必须转义），
  不处理转义就会把 `\}` 当成占位段结束，名字切坏、尾巴掉进字面量；
- 模板语法的可用边界由 Spring 自己界定（`SpringRoutingModelSpikeTest` 钉住）：
  `{code:[a-z}]+}`（未转义的 `}`）、`{9lives}`（数字开头）、`{a+b}`（含 `+`）都会被
  `PathPatternParser` 直接拒绝，即这类模板不可能来自 Spring；解析器对它们仍按「原样保留、不计参数」兜底，
  防御非 Spring 来源的输入；
- `{*path}` 这类前端填不了的占位段原样保留但**不进** `parameters`；裸 `**` 同理，SPEC 注明前端不可参数化；
- 可选段：Spring 语法拒绝 `{name?}`，故可选性只能由 `@ForgeRoute(optional = {...})` 声明，
  再由 `withOptional` 拼回 `{name?}`；声明的名字不在模板参数里 → 直接抛异常，不静默忽略。
- 可选路径参数：`@ForgeRoute(optional = {"page"}, defaults = {"page=1"})` → 输出 `xxx/{page?}` +
  `parameter_defaults`。声明的名字不在 URI 模板内 → fail-fast。
- 参数名来源是 URI 模板本身，不依赖 `-parameters`；`-parameters` 仍全仓强制（配置构造绑定需要）。

### 4.4 middleware（守卫标签的来源与边界） ⟨P3⟩

Spring 侧**没有「逐路由中间件」**，但有两条与 Laravel 等价的守卫声明主流写法。按 GitHub 公开 Java 代码
字面命中量级实测（2026-10-06，单位是命中文件数、含 fork 重复计数，只看量级与相对比例）：
`authorizeHttpRequests` 34.8 万、`requestMatchers(` 30.2 万、`@PreAuthorize("hasRole` 27.0 万、
`@PreAuthorize("hasAuthority` 8.3 万、`@PreAuthorize("@ss.hasPermi`（若依派权限码）3.6 万、
`@RequiresPermissions`（Shiro）5.7 万、`@SaCheckPermission`（Sa-Token）0.85 万。
即 **URL 规则**与**方法注解**是并列的两大主流，第三方框架是长尾（不为它们设计通道）。

官方口径（Spring Security 7.1 文档原话，三条都直接影响设计）：

- request-level = `coarse-grained / declared in a config class / DSL`，method-level =
  `fine-grained / local to method declaration / Annotations`，二者互补，取舍只是
  *"where you want your authorization rules to live"*；
- *"By default, Spring Security requires that every request be authenticated."*；
- *"when you use annotation-based Method Security, then unannotated methods are not secured"*——
  所以注解派生出来的标签**永远不能自称安全边界**。

三条铁律与一条差异，据此定死：

1. **标签只做归类，不构成安全边界**（不变）：真实鉴权始终归宿主的 `SecurityFilterChain`。
2. **不要求宿主重复声明**：路由的 `middleware` 优先取显式通道（`@ForgeRoute(middleware=...)` /
   `@Forge(middleware=...)`）；两者都没给时，由**第四通道从 handler 上已有的守卫注解派生**。
   按 URL 配 Security 的那批宿主用不到它——他们的层级按 `match.prefix` 归类，与 `requestMatchers`
   天然同构，两边零声明。
3. **starter 不代宿主配 Security**：不注册、不改写、不排序任何 `SecurityFilterChain`，也不反射读别人的
   规则。包自身端点交宿主按 `requestMatchers("/_forge/**")` 自己配（Boot 默认已要求认证）。
4. **与 PHP 侧的实质差异（必须记，不许静默分叉）**：`forge.endpoint-middleware` 在 Laravel 侧是真的挂到
   路由上生效，Java 侧**只作声明值**——接受、按原样出现在配置产物里、不静默丢弃，但**不产生任何行为**。
   因此 Java 侧不引 `spring-security-*` 依赖（连 `compileOnly` 都不需要，注解按名字识别）。
   P5 唯一相关的动作是：classpath 上没有 Security 时启动打一条 WARN，明示 `/_forge/*` 端点当前无鉴权保护。

派生规则（`GuardLabels`，`forge-spring-boot-starter`）。识别一律按**注解类型全名**匹配，
故零编译期依赖；就近语义复用 `MergedAnnotations` + `TYPE_HIERARCHY`（方法级覆盖类级，
与 Spring Security 文档一致），并因此自动吃透**组合注解**（宿主自建的 `@AdminOnly` meta-annotate
`@PreAuthorize` 也算）：

| 声明 | 派生标签 | 说明 |
|---|---|---|
| `@PreAuthorize("hasRole('ADMIN')")` | `ADMIN` | **一律取注解里的字面值**，不补也不剥 `ROLE_` 前缀——加工会把 `hasRole('ADMIN')` 与 `hasRole('ROLE_ADMIN')` 这两种不同要求悄悄归一，那是读错 |
| `@PreAuthorize("hasAuthority('system:user:list')")` | `system:user:list` | 权限码字面值 |
| `@PreAuthorize("hasRole('A') and hasAuthority('p:read')")` | `A`、`p:read` | 顶层 `and` 是合取（都要求），拆成标签并集 |
| `@PreAuthorize("hasAnyRole('A','B')")` | `expression:` 兜底 | 或语义；拆开会**放宽**归类，属于读错，宁可原样 |
| `@PreAuthorize("hasRole('A') and #id == authentication.name")` | `expression:` 兜底 | 混入不可分类的项时**整条原样**——规则是「要么全懂，要么不猜」 |
| `@PreAuthorize("authenticated()")` / `isAuthenticated()` | `__authenticated` | 只要求登录、无权限名；`__` 前缀是本包保留字面量 |
| `@PreAuthorize("permitAll()")` / `denyAll()` | `__permit_all` / `__deny_all` | 同上 |
| `@Secured("ROLE_ADMIN")` | `ROLE_ADMIN` | 字面值。**注意**：`@Secured` 的值按 Spring 习惯带 `ROLE_`，而 `hasRole` 不带，同一份权限两种写法会出两个标签——这是如实反映声明差异，不做归一 |
| `@RolesAllowed("ADMIN")`（jakarta 与 javax 两种包名都认） | `ADMIN` | 同上；多值 → `expression:` 兜底（`@Secured` 多值是或还是与未实测，不猜） |
| `@PermitAll` / `@DenyAll` | `__permit_all` / `__deny_all` | JSR-250 |
| 其余任何表达式 | `expression:` + 原文 | 可读但不可归类；若依的 `@ss.hasPermi('x')` 全落这里 |

派生的两条边界，写在这里免得被当成 bug：

- **不校验 method security 是否真被启用**（`@EnableMethodSecurity` 及其 `securedEnabled` /
  `jsr250Enabled` 开关）：宿主把注解写成装饰物时，派生标签会跟着装饰它。这是标签只做归类的直接后果，
  也是第 1 条铁律存在的原因。
- 显式通道与派生结果**不一致时不报错**（守卫可以有很多个名字，撞不上是常态），只经 `WarningSink` 出一条提示。
  装配层是无条件接 sink 的（不按 `strict-mode` 收口——那个 sink 被严格模式扫描等多处共用，一刀切关掉会
  连带别的告警一起哑掉），要静音由宿主覆盖 `WarningSink` bean；提示频率＝真正扫描频率（缓存 miss），
  `debug=true` 旁路缓存时退化成每请求一条。
- 标签集合的**可见面**只有两处：`--forge:list`（P4，走 `RouteRepository.allRoutesWithTiers()`）与
  管理器条目（P5）。**层级端点的行字段集按 §2.2 是 `uri`/`methods`/`parameters`/`parameter_defaults` 四项，
  不含 `middleware`**——那是前端消费的契约字段集，不许为了后端自检而扩字段（由契约测试的整文断言钉住）。

### 4.5 包自身路由排除 ⟨P2⟩

`forge.manager.*` / 层级与摘要端点自身一律不进任何元信息，两个维度：按注册来源（本包 controller）+
按规范化后的 `endpoint_prefix` 做 URI **段级**前缀排除。否则 `strict_mode=true` 时包会把自己的端点报成宿主的配置错误。

### 4.6 缓存层与装配生命周期

`CacheStore` SPI 自写（不接 Spring Cache 抽象），键 `route-forge:<level>` / `route-forge:summary` /
`route-forge:_keys` 索引；`forgetLevel` 必须连带失效 summary。

`ForgeRouteRegistry` 为单例，只做**装配**（层级解析器、别名解析器、过滤器、缓存的构造与注入），
不持有扫描结果；所有取数一律经缓存，miss 才真正扫。于是缓存层在 Java 侧不是多余的第二层，
而是唯一的失效点——TTL 三态、`clear --level` 连带失效 summary、Redis 多实例共享这些语义
才与 PHP 侧逐条可比（PHP 是每请求新建的进程模型，跨请求复用本来就全靠缓存）。
失效入口只有三个：`--forge:clear`、管理器保存配置后、管理器与端点的显式刷新。

### 4.7 开发环境判据

**开发环境判据 = Spring 的 `debug=true`**（与 Laravel 的 `APP_DEBUG` 同构，一根线管三处）：

| 受控行为 | 判据 | 说明 |
|---|---|---|
| 缓存读写旁路 | `debug=true` | 保证改路由/改配置即时生效；`clear` 不旁路（否则关回 debug 时旧缓存复活） |
| `RF_BE_009` 结构化 `violations` 下发 | `debug=true` | 清单是宿主越界路由的名字与 URI 目录，生产只给 `code` + `message` |
| 管理器页面注册 | `debug=true` **且** `forge.manager.enabled=true` | 后者默认 false——误开 debug 最多旁路缓存，不会凭空暴露可写配置的端点；再叠加 IP 白名单共三道 |

`debug=true` 生效时启动日志打一条 WARN 横幅，明示「缓存已旁路，每个请求重扫路由表」，避免把它当成性能开关长期开着。


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

两处照抄上游的偶然形态（改了就会与 Laravel 后端产物不一致，注明而非隐藏）：文件头「生成时间」来自
`date('Y-m-d\TH:i:s.000\Z')`——毫秒位恒为字面量 `.000`、`Z` 为字面量、时区取进程默认；
`--json` 在**目标层级为 0** 时顶层输出 `[]`（只有层级块做了对象强转），消费侧要能容忍 `[]` 与 `{}`。

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
| Spring 特有 | ⟨P2/P3⟩ 组合注解映射生效、双通道冲突、正则剥离、可选参数、守卫注解→标签派生（§4.4 规则表逐行） |
| CLI | ⟨P4⟩ 输出格式、退出码、违规不产出产物 |
| 跨语言对等 | ⟨P6⟩ PHP 实跑 golden fixture 逐字段断言 |

## 8. 版本

Java 适配自成一条版本线（当前 `0.1.0`），不跟随 PHP/npm 的版本号；`schemeVersion` 恒 `1`，
只在摘要响应格式发生不兼容变更时递增。
