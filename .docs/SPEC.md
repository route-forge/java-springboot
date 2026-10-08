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
| `forge.exclude-uri-prefixes` | `string[]` | `[]` | **追加**的 URI 维排除前缀（在内置 `/error`、`/actuator` 之外做加法），见 §4.5 |
| `forge.manager.enabled` | `bool` | `false` | 管理器页面开关（Java 侧多一道显式开关） |
| `forge.manager.allowed-ips` | `string[]` | `[127.0.0.1, ::1]` | IP 白名单，`*` 放行、空=不限制 |

- 数组型配置**接受单值**（`forge.levels.admin.match.prefix=api/admin` 等价于单元素列表），与 PHP 侧归一口径一致。
- `classifier` 在 Java 侧是 bean（`RouteClassifier`），不进配置文件，因而管理器可安全保存 PHP 侧禁存的场景在这里天然消失。
- `forge.exclude-uri-prefixes` 是 **Java 专属扩展**：Laravel 靠路由名前缀（`storage.*`）排框架内部路由，Spring 侧
  框架路由全未命名、无名字可匹，只能按 URI 段前缀排。宿主值是**追加**到内置默认集 `{/error, /actuator}`（并集，
  默认集不可被清空），防止宿主配一个值就把 `/error` 的排除丢了、strict 又 500。详见 §4.5。

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

### 4.5 包自身路由与框架内部路由的排除 ⟨P2/P3⟩

`forge.manager.*` / 层级与摘要端点自身一律不进任何元信息。排除有**两个维度**，缺一都有真实自伤：

- **按注册来源**：本包 controller（其路由名带 `forge.routes.` / `forge.manager.` 前缀，被名字维排除）；
- **按规范化后的 `endpoint_prefix` 做 URI 段级前缀**：Spring 侧包自身端点全部未命名、无名字可判，
  若宿主管该层级配了 `endpoint-middleware`，带中间件的 `GET {endpoint_prefix}/{level}` 会被 match 命中——
  不按 URI 排除，`strict_mode=true` 时包会把自己的端点报成宿主的配置错误、端点必 500。

URI 维还并入一批 **Boot 框架内部路由的默认排除集 `{/error, /actuator}`**（`BasicErrorController` 与 actuator
基路径），段级匹配、未引对应依赖时空转无害。根因同「包自身端点」：这些路由也全未命名，宿主一旦把某层级
`match.prefix` 写宽（`/` 或 `/api`），`/error`、`/actuator/**` 就整批落进 `missing_name`，把严格模式刷满 500，
**且宿主无法靠给它们命名来自救**（CI 门禁被永久打断、报错指向框架路由、排查方向是错的）。

Laravel 侧没这个维度可借——它靠路由名前缀 `storage.*` 排框架内部路由（`ForgeServiceProvider` 的
`LARAVEL_INTERNAL_ROUTE_PREFIXES`），而 Spring 框架路由无名。故 URI 默认排除是 **Java 专属扩展**，宿主可经
`forge.exclude-uri-prefixes` **追加**（并集语义，内置默认不可清空，见 §3）。**刻意不纳入** springdoc
（`/v3/api-docs` 等）：第三方路径多变可配、变体众多（`swagger-ui/**`、`.yaml` 等），写进默认集是「假完备」，
更应让宿主按需显式追加。**边界**：URI 维只作用于**未命名**路由；宿主显式命名的路由（哪怕 URI 恰好是 `/error`）
不受影响——排除从不静默吞掉一个被有意命名的业务路由。

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


### 4.8 注解误放提示（写在不会被消费位置的 forge 标记） ⟨P3⟩

参照 Laravel 的 `ForgeRouteRegistrar::__destruct`（声明了属性却无消费方 → 记日志、**刻意不抛**）。Spring 侧的
对应误用是：forge 标记落在**永远不会成为 handler** 的位置，于是静默失效——路由进不了任何端点、`--forge:list`、
d.ts 或严格扫描，宿主却以为生效了。可检测的两类（高信噪比、近零误报）：

- `@Forge` 标在一个**没有请求映射**的方法上（该方法永不成为 handler）；
- `@ForgeRoute`（meta-`@RequestMapping`）标在一个 `@Component` 而非 `@Controller`/`@RestController` 的 bean 上
  （类不是 handler，映射不生效）。

机制：`HandlerMethodRouteSource` 只枚举**已注册** handler，结构上看不见「没成为 handler 的注解」，故本检查反向
在 bean 定义层面做——`SmartInitializingSingleton` 启动后跑一次，拿全量已注册 handler 的方法签名集，再看每个 bean
**本类局部声明**的、带 `@Forge`/`@ForgeRoute` 的方法是否落在集外；集外者经 `WarningSink` 出一条提示。**绝不抛异常**。

边界与取舍（按用户 2026-10-07 拍板，均选 A）：

- **只在 `debug=true` 或 `strict=true` 时启用**（排查配置问题的两个场景），生产两者皆非则 bean 存在但直接返回、零反射；
- **severity 恒为 `warning`**——不给 `WarningSink` 扩 error 通道。这类位置本就进不了 forge 产物，无法成为可 catch 的
  strict error；与 Laravel 的 strict→error / 否则 warning 分级是本处**有意差异**，据此保持 §4.4「`WarningSink` 无条件接、
  要静音由宿主覆盖该 bean」的既有口径。
- **刻意不纳入**：`@ForgeTier` 的类/包级误放（继承、`package-info`、懒加载下误报面大）；完全没被任何 stereotype
  纳入容器的类（连 bean 都不是——那要 classpath 扫描，代价与误报都不可接受）。


### 4.9 生命周期时机对照（与 PHP）⟨P3⟩

记下来免得下个会话当缺口重做。四条里三条与 PHP **等价**、一条**刻意不做**，另有一条 Java 多出的时机面，再加一条
Spring 模型带来的口径差：

- **层级名合法性（`RF_BE_002` vs PHP `UnknownLevelException`）**：PHP 在三个定义入口（宏 / Registrar /
  `updateGroupStack`）即查 `config('forge.levels')` 抛；Java 在 `TierResolver` 抛 `RF_BE_002`。Laravel 的路由文件
  每次请求都要重新注册，所以两侧都是「同一次请求内、响应之前」——**形态等价**，不需要提前到启动期。
- **悬空别名 / 别名撞车 / 同名跨层级重复**：PHP 全部延迟到扫描期（`AliasResolver`、`RouteAnalyzer`），撞车时真实路由赢、
  别名丢弃并 warning——Java 已同形（核心层移植自同一份代码）。
- **不做启动期预热扫描**：PHP 没有任何启动期全表扫描。Java 若为了「更早报错」在启动期扫一遍，会连带把 strict 聚合
  提前到启动期，宿主行为从「请求 500」变成「启不来」——**不要做**。§4.8 的误放扫描是**安全的例外**：它只经
  `WarningSink` 出提示、绝不抛，既不参与也不提前 `RF_BE_009` 的判定，故不触碰这条禁令。
- **Java 多出的时机面**：`GuardLabels` 的显式/派生不一致提示无条件经 `WarningSink`（§4.4 已记），以及 §4.8 的误放
  提示（`debug` 或 `strict` 时启动扫一次）。
- **Spring 的 fixed-strict 现实（口径差，非缺口）**：`strict-mode` 是启动期绑定的属性，单个 JVM 内不可运行时翻转；
  因此 PHP 那句「strict 下 500 → 关 strict 取 200 → 再开 strict 仍 500」的翻转测试，在 Spring 的一个上下文里
  **结构上不可能发生**。契约测试据此钉的是**等价事实**：strict 取数路径从不落缓存（每请求恒 500，不会因缓存命中
  跳过预扫描而被「洗白」）、`debug=true` 整体旁路缓存（缓存 bean 报 `disabled`）、两个端点同口径。


## 5. 工具链

### 5.1 CLI（`ApplicationRunner` 参数式） ⟨P4⟩

`--forge:list` / `--forge:types` / `--forge:clear`，选项与退出码语义对齐 artisan：
`--level=` `--json` `--unassigned` `--aliases` `--unnamed` `--out=`。
严格模式违规时：list 表格照常 + 红色清单 + 退出码 1；types **不产出产物** + 清单走 stderr + 退出码 1。

**执行载体与退出（Java 专属扩展）**：路由表来自 `RequestMappingHandlerMapping`，只能在 web 上下文里取到，
因此 CLI 是宿主 web 应用里的一个 `ApplicationRunner`，**复用注册表**（`--forge:list`/`--forge:types` 走
`ForgeRouteRegistry#analyze()`，`--forge:clear` 走 `RouteCache`），绝不在命令层再装一套 resolver/filter 重扫。
`--forge:*` flag **缺席即完全 no-op**（正常启动与不带这些参数的 `@SpringBootTest` 不受影响）；命中命令则
跑完 `System.exit(退出码)` 落地——所以宿主**正常启服务时勿误带这些 flag**（会被当运维命令跑完即退，与 artisan 同语义）。
命令逻辑抽成纯对象（收已解析选项 + `out`/`err` 两个 writer → 返回退出码），退出仅在其外层薄壳，便于整文单测。

**流口径（stdout = 机器可消费产物；stderr = 一切反馈）**：

- 走 stdout 的只有产物本身：list 的表格文本、list `--json` 的 JSON、types 无 `--out` 时的 d.ts / JSON；
- 走 stderr 的是所有诊断：list `--json` 的违例红色清单、types 的未知层级 / `[code] 消息` / warnings / 违例清单 /
  `Written to:` 确认；`clear` 无产物，其状态信息走 stdout。
- **与 PHP 的一处有意分歧**：artisan 把 `$this->error` / `Written to` 发 stdout，Java 侧统一发 stderr，
  目的是让 stdout 恒为可安全重定向的纯产物（`--forge:list --json > x.json` 不会混进错误文本）。

**产物纯净性的现实约束**：Boot 的 banner 与启动日志默认写 **stdout**，故 `--forge:types > x.d.ts` 仍会被启动期
输出污染。因此 **`--out=` 是干净产物的推荐主路径**（写盘 UTF-8、stdout 保持空），stdout 直出仅作跨语言对等与脚本消费。
命令写出一律显式 UTF-8（AGENTS 编码铁律，Windows GBK 会污染中文产物并造成假失败）。

**形态与着色**：命令名是参数式 `--forge:xxx`（非 `route:forge:list` 这类 Artisan 命令名），属 Java 专属扩展，
P6 的 Laravel golden 比对里别当差异查。`--json` 产物 = `json_encode(JSON_PRETTY_PRINT|JSON_UNESCAPED_SLASHES)`
（核心层 `JsonWriter.pretty`）。Laravel 表格的 ANSI 着色（品红/黄/绿/红）仅存在于表格模式、非跨语言 golden 产物；
Java 表格渲染为**纯文本对齐框、不落 ANSI**，层级/别名/撞车/未归级语义改由列文字表达。d.ts 文件头时间戳生产取
`TypeGenerator#currentTimestamp`，做成可注入入参以便测试整文断言。

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
| 构建约定 | Java 17 基线（产物 class major=61，由 `BuildConventionTest` 读自身 class 头钉死）、`-parameters`、UTF-8 默认字符集（已落地） |
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

### 8.1 运行时支持边界

语言基线为 **Java 17**（`options.release` 锁定；Boot 4 官方只要求 17，且 17 是 JDK 的 LTS，
大量宿主升 Boot 不升 JDK，故基线取 17 而非 21）。Spring Boot 侧承诺如下三行，逐字为准：

| 面向 | 承诺 | 依据 |
|---|---|---|
| **Boot 4.x** | **tested**：编译、全量测试、示例联调都跑在 4.1 上，出问题按 bug 修 | 主门禁 `./gradlew build` |
| **Boot 3.5+** | **物理兼容、untested**：`src/main` 用到的 Spring API 自 Framework 6.1 起即存在、main 零 Jackson/servlet/Security，故 `compileBoot35SentinelJava` 能对着 **Boot 3.5.16（实测 = FW 6.2.19）** 编过——但**不跑测试、不承诺运行期行为** | Boot 3.5 编译哨兵（只证明可编译，不证明语义） |
| **Boot 2 及以下** | **明确排除**：`javax.*` 命名空间 + 无 `AutoConfiguration`/`MergedAnnotations` 等，物理上不兼容 | —— |

> 唯一会真正分叉的运行期点是 `YamlShape` 依赖的 Boot Binder「全数字键索引 Map」形态（`{0=/admin}`，
> 本仓在 Boot 4 上实测得来），3.5 上未做运行验证。所以 3.5 那一行的口径**只能停在「物理兼容、untested」**，
> SPEC/README 一律不得写成「支持 Boot 3」。
