# 实施进度与交接

> 冷启动恢复用：新会话先读本文件 + `AGENTS.md` + `.docs/SPEC.md`，不必重读 PHP 参照仓。
> 阶段划分与验收口径见 `AGENTS.md`；本文件只记「已经定了什么、做到哪、下一步是什么」。

## 已锁定的决策

| 主题 | 结论 |
|---|---|
| 能力范围 | v1 全量对等 SPEC（含管理器页面与 d.ts 生成） |
| 交付形态 | 双模块库（`forge-core` + `forge-spring-boot-starter`）+ 示例后端 + Vue3/React 双前端 |
| 语言/构建 | Java 17 基线（`options.release` 锁定，不用 toolchain 自动下载）+ Gradle Kotlin DSL + wrapper 9.7.0-all。✅ 已落地（2026-10-08）：`release=21→17`、删 catalog 死配置 `java="21"`、`BuildConventionTest` 改读自身 class major=61（实证零 Java 18-21 语法/API，改动即通过） |
| Spring 基线 | 编译与测试用 Spring Boot 4.1.1（2026-10 最新稳定；4.2.0 仅 milestone） |
| 支持边界 | 承诺 **tested on Boot 4.x**；Boot 3.5+ **物理兼容但不承诺**（不跑测试、SPEC 不写「支持」）；Boot 2 及以下**明确排除**。2026-10-07 拍板，✅ 2026-10-08 已落地（SPEC §8.1 三行口径 + 编译哨兵，实测 Boot 3.5.16=FW 6.2.19） |
| 坐标 | group `io.github.route-forge`（对应 GitHub org），artifact `forge-core` / `forge-spring-boot-starter`；Java 包根 `io.github.routeforge.*`（包名不允许连字符） |
| 命名通道 | 形态 C：`@ForgeRoute` 组合注解（meta `@RequestMapping`，全量 `@AliasFor` 透传）+ `@Forge` 副注解逃生舱 + `RouteNamingStrategy` SPI（默认关）；冲突 fail-fast = RF_BE_010 |
| tier 继承 | 五级优先级不变：方法级 = 显式 > 类/包级 `@ForgeTier` = group 继承 > `RouteClassifier` bean > `match` > `unassigned` |
| middleware | `match.middleware` = 归类用标签，**不构成安全边界**；标签来源＝显式 `@ForgeRoute`/`@Forge` 优先，未声明时**从 handler 上的 Spring Security 守卫注解派生**（第四通道，按注解全名识别、零编译期依赖）；starter **不注册/不改写任何 SecurityFilterChain**，`endpoint-middleware` 在 Java 侧降为纯声明值（与 PHP 的实质差异，SPEC §4.4 已记） |
| 配置保存 | 只写独立 `forge-levels.yml`（备份 + 回读比对），绝不改宿主 `application.yml` |
| CLI | `ApplicationRunner` 参数式：`--forge:list` / `--forge:types` / `--forge:clear` |
| 缓存 | 自写 `CacheStore` SPI（不接 Spring Cache），内存实现 + Redis 可选 |
| 开发态判据 | 用 Spring 的 `debug=true`（与 `APP_DEBUG` 同构）驱动缓存旁路与 `violations` 下发；管理器额外要 `forge.manager.enabled=true`（默认 false）才注册，即 **debug AND 显式开关**，误开 debug 不会凭空暴露写配置入口 |
| 生命周期 | `ForgeRouteRegistry` 单例只装配、不持结果；取数一律走 `RouteCache`，miss 才扫。缓存层因此仍是唯一失效点，TTL/`clear`/Redis 语义与 PHP 逐条可比 |
| 对等粒度 | 直调仓库层：oracle 调 `RouteRepository::getLevel()/getSummary()` 与 `TypeGenerator` 冻结逐字段真值；真实 Laravel HTTP 响应留到 P6 覆盖序列化细节（键序、null 省略、空层级 `{}`） |
| 版本线 | 开发期 `0.1.0`；「全量对等 SPEC + 双前端联调通过」是 `1.0.0` 的门槛 |
| 文档 | 本仓自带一份 Java SPEC；跨语言端点契约以 `route-forge/php-laravel/.docs/SPEC.md` 为权威 |
| 前端 | `@route-forge/*` 3.1.0 零改动接入（已实证：契约即插件点，vue/react 包里 0 处 PHP 痕迹） |
| Gradle 环境 | 依赖与发行包全在项目本地 `<根>/.gradle`（gitignore），与用户目录隔离；镜像配置在 `.gradle/init.d/cn-mirrors.gradle.kts`（依赖腾讯、插件阿里、官方兜底），不入 git。`settings.gradle.kts` 只声明 mavenCentral。wrapper 的 `distributionUrl` 指腾讯——本机缓存的 9.7.0 发行包正是该 URL 的哈希，而官方 services.gradle.org 在本机 SSL 握手失败 |
| 契约基准 | **以 Spring Boot 习惯为准，Laravel 只是参照实现**（2026-10-06 拍板）：字段集与语义功能等价，形态冲突时从 Spring——例路由 `uri` 保留前导 `/`，不为对齐 Laravel 而去斜杠 |
| 命名通道 | 四条来源合并：`@ForgeRoute.name` ＞ `@Forge.name` ＞ Spring 原生 `@RequestMapping(name=...)` ＞ `RouteNamingStrategy`；同一事实被两条通道给了不同值 → RF_BE_010 fail-fast |
| methods 口径 | 声明了什么就下发什么；GET 附 `HEAD`（实测运行期能响应，属如实描述）；未声明 method 时下发完整标准方法集（空数组会让前端拿不到默认方法） |

## 阶段状态

- ✅ **P0 骨架** `f8c7c25`：多模块 + wrapper 离线 + UTF-8/`-parameters`/Java 21 约定 + `BuildConventionTest`
- ✅ **P1a 契约与基础件** `4733490`：`ForgeException` 契约、异常族、`RouteInfo`、`RouteNameFilter`、`EndpointPrefix`、`JsSafeEncoder`、`WarningSink`
- ✅ **P1b TierResolver** `ebdc417`：五级优先级 + `probe()`，53 例跨语言对等
- ✅ **P1c 缓存** `b867b04`：`CacheStore` + `RouteCache`（TTL 三态 / keys 索引 / debug 旁路 / `forgetLevel` 连带 summary）
- ✅ **P1d 别名 + 严格扫描** `f1346bb`：`AliasResolver`、`StrictViolationScanner`、`RF_BE_009`，27 例跨语言对等
- ✅ **P1e-1 仓库层** `RouteRepository` + `RouteSource` + `RepositoryConfig`，21 例 JSON 文本级对等（含缓存调用序列）
- ✅ **P1e-2 分析器** `RouteAnalyzer` + `Row` + `Analysis`：11 例对等（rows/tier_counts/warnings/unnamed/violations/--unnamed 五种过滤/list --json 五种过滤），并做三次变异检验（别名不铺开、未命名告警不入 warnings、文案改一词）分别被 5/10/6 例抓住
- ✅ **P1f** `JsonWriter`（与 PHP `json_encode` 同形态）+ `TypeGenerator`（d.ts 整文 + `--json`）+
  `SummaryRenderer`（内嵌 `<script>`）：5 例类型产物 + 3 例 HTML + 3 种 JSON 形态，全部整文比对

> **P1 核心层到此收口**：forge-core 169 个测试全绿，其中 119 例直接对着 php-common@f3fa70d 的真实产物断言。

- ✅ **P2-1 注解层** `@ForgeRoute`（组合注解，FW7 全部 9 个映射条件属性逐个 `@AliasFor`）+
  `@Forge`（副注解）+ `@ForgeTier` + `ForgeTiers` 就近解析 + `RouteNamingStrategy` SPI；
  含「与原生写法注册的 `RequestMappingInfo` 完全相等」的等价性断言
- ✅ **P2-2 URI 归一化** `UriTemplate`：按花括号深度解析（含正则量词与转义右括号）、可选段拼回 `{p?}`、
  不可参数化段原样保留不计参数
- ✅ **P2-3 扫描层** `ForgeDeclaration`（三通道合并 + 冲突 fail-fast RF_BE_010）+
  `HandlerMethodRouteSource`（handler 表 → `RouteInfo`）：多路径展开成多条、无 path 条件归一为根地址、
  methods 按实测能力下发；三项变异检验各被 1 例抓住
- ✅ **P2-4 端点与装配** `ForgeAutoConfiguration` + `ForgeRouteRegistry`（常驻装配、取数走缓存）+
  `ForgeRoutesController`（摘要与层级两端点、错误体按 PHP 同形态、`violations` 只在 `debug=true` 给）+
  `ForgeProperties`/`YamlShape`/`InMemoryCacheStore`/`Slf4jWarningSink`；
  端点契约测试断言**响应体原文**，三组上下文（宽松 / 严格无 debug / 严格有 debug），
  并验证「违例恰为 1」即包自身 `/_forge/routes/**` 已被 URI 段排除（AGENTS 铁律 3）
- ⚠️ 同一次提交携带了另一会话的 §4.4 守卫标签派生（`GuardLabels` + 其测试、`HandlerMethodRouteSource`
  的 `middlewareOf`、catalog 的 `spring-security-core`/`jakarta-annotation-api`、SPEC §4.4 全文）——
  编译耦合（来源类调用派生器、装配又用其构造器），无法拆成两笔独立可编译的提交
- ✅ **守卫标签第四通道**（即上条 ⚠️ 携带的那部分，现补齐端到端）`GuardLabels`：按注解**类型全名**识别
  （main 侧零 Security 依赖）、`hasRole`/`hasAuthority` 取字面值不归一前缀、顶层 `and` 拆、或语义与认不全的
  整条 `expression:` 兜底；`middlewareOf` 接线（显式优先，不一致只经 `WarningSink` 提示）。
  17 例规则表 + 3 例接线 + 1 例端点契约。实测推翻的直觉：`MergedAnnotations` 的 `TYPE_HIERARCHY`
  作用在 Method 元素上**读不到声明类的注解**，类级守卫全靠 `derive` 的第二轮（bean 类型）——已由专测钉住。

## 两条被实测推翻的前提（P2-3）

写扫描层时我按直觉下了两个判断，都被真实 Spring 打脸，修的是实现而不是断言：

1. **「无路径条件的映射匹配所有路径，所以无法下发」** —— 实测它只匹配根路径，且 Spring 已给它
   materialize 出 `["", "/"]` 两个 pattern。正确处理是归一成一个 `/` 并去重（不去重会产出两条记录、
   其中一条 URI 是空串），而不是跳过。
2. **「GET 补 HEAD 是为了模仿 Laravel」** —— 实测 Spring 的 GET 映射运行期确实响应 HEAD（`doHead` 走
   `doGet`），所以补 HEAD 是如实描述框架能力；而「未声明 method」的映射下发空数组才是错的（前端
   `pickMethod` 会拿不到方法），实测任何方法都能匹配，就该给完整方法集。

教训同前：Spring 侧的每一个「我以为」都要有一条断言兜着，断言由真实框架行为产生。

## 断言必须防"真空通过"

两次教训同一类：① fixture 控制器没标 `@Controller` → Spring 一个映射都没注册 → 两个空 map 相等，
最关键的等价性断言静默成立；② 改完生产代码没带 `--rerun-tasks`，读到的是上一轮结果。

规则：任何「比对两边」的断言，先各加一条 `hasSize(N)` / `isNotEmpty()`；复验一律 `--rerun-tasks`；
新落的断言集做一次变异检验（去掉一个别名、反一个覆盖方向、少注册一个映射）。

**变异检验自身也要防假阴性**：用脚本改源码做变异时，必须打印「补丁是否真的生效」再数失败数——
本仓已两次出现替换规则没匹配上、于是"0 失败"被误读成"断言有效"。改用编辑工具逐行删、
或让脚本在没匹配时直接失败。

## 实测优先于记忆（P2 已抓到两例）

- `UriTemplate` 的字符类用例：我按直觉写的是 `{code:[a-z}]+}`，实测 Spring **直接拒绝**这种写法
  （要写 `{code:[a-z\}]+}`），而接受转义的形态意味着解析器必须整对吞掉反斜杠序列——
  否则名字会被切坏。这是真 bug，不是测试写错；修法是先修解析器。
- Framework 7 的 `PathPatternParser` 在 `spring-web` 里，但 `-cp` 用 MSYS 风格路径（`/f/...`）时
  JDK 不识别，会假报"程序包不存在"；给 JDK 的一律用 `F:/...`。

## 本阶段新发现的形态细节（照抄，不「顺手修正」）

- **Boot 会把 {@code Object} 位置上的 YAML 序列绑成索引 Map**：{@code forge.levels.<n>.match.prefix: [/admin]}
  到达时是 {@code {0=/admin}}，核心层按标量处理后 **整条 match 通道静默失效**（只靠配置归级的路由全掉
  unassigned）。装配层用 {@code YamlShape} 把「全数字键的 Map」还原成列表（跳号补 null、键序无关）。
  结论：只要配置值类型是 {@code Object}（为了保宽容语义），就必须过这一道。
- **`Map.copyOf` 的教训在同一个仓库里复发了第二次**：这次是我自己新写的 {@code ForgeProperties}
  用它收 {@code levels}/{@code aliases}，摘要键序当场变成 {@code admin, manage, client}。
  光记规则没用，已把「非注释里的 Map.copyOf」当例行检查项，新代码写完后扫一遍。
- **`spring-boot-starter-test` 不再自带 MockMvc 自动配置**（Boot 4 按技术栈拆模块）：
  `@AutoConfigureMockMvc` 在 {@code org.springframework.boot.webmvc.test.autoconfigure}，
  artifact 是 {@code spring-boot-webmvc-test}；Jackson 也不在测试运行时里，缺它会 406 而不是报缺依赖。
- **测试夹具不能共用一个会抛异常的控制器**：把「非法默认值声明」放进 DemoController 后，
  整个类的扫描都抛异常，连带让 6 个无关用例失败。会抛异常的夹具要单独一个类。

- **`--json` 顶层在目标层级为 0 时 PHP 输出 `[]`**：顶层没做 `(object)` 强转，只有每个层级块做了。
  Java 照抄并在 SPEC 注明，脚本侧要能容忍顶层两种形态。
- **d.ts 文件头时间来自 `date('Y-m-d\TH:i:s.000\Z')`**：毫秒位恒为字面量 `.000`、`Z` 也是字面量、
  时区取进程默认（非 UTC 机器会写出「本地时间 + Z」）。Java 照抄该形态，同时把时间做成可注入入参以便测试。
- **emoji 的代理对不能直接喂 `%x`**：`String.format("%04x", Character)` 抛 IllegalFormatConversionException，
  必须显式转 int——这条是被对等测试当场抓到的，纯 Java 单测写不出来。

- ✅ **P4 CLI 三件套** `ForgeCliRunner`(ApplicationRunner) + 纯命令对象 `ForgeListCommand`/`ForgeTypesCommand`/`ForgeClearCommand`
  （收 `CliOptions` + `out`/`err` 两 writer → 返回退出码，退出只在薄壳里，22 例直测喂 StringWriter 断言产物原文/退出码/流归属）：
  走 `ForgeRouteRegistry.analyze()`（与两端点同一套 resolver/alias/filter，命令层不重装不重扫）+ `RouteCache` 失效口；
  list 有违例照常出全表+红色清单+退 1、types 有违例**拒绝产出**（stdout 全空）+清单走 stderr+退 1（两者刻意相反，
  变异检验「把 types 违例守卫改成永不触发」被且仅被该例抓住）；`--unnamed` 独立视图与 `--json/--unassigned/--aliases` 互斥退 1；
  `--json` 走 `JsonWriter.pretty`；d.ts 时间戳可注入。已按「stdout=产物 / stderr=一切反馈」定死流口径（SPEC §5.1 已展开）。
  三条拍板（用户 2026-10-07 定，均选 A）：① 载体=ApplicationRunner + flag 触发 + `System.exit`（flag 缺席即完全 no-op，
  故正常启动/不带这些参数的 `@SpringBootTest` 不受影响）；② types 双路（stdout 直出 + 推荐 `--out=` 为干净主路径，
  因 Boot banner/启动日志默认写 stdout 会污染重定向）；③ 不加 `forge.cli.enabled` 总开关。
  **前置修正**：PROGRESS 原记「给 registry 补 `allRoutesWithTiers()` 转发」不准——list/types 吃的是 `analyze()`
  （`allRoutesWithTiers()` 是 P5 管理器的数据源，本次未动、留给 P5）；本次给 registry 补的是
  `analyze()`/`levelNames()`/`normalizedEndpointPrefix()`/`clearAllCache()`/`clearLevelCache()` 五个口。
- ✅ **P3 重排的四项真实缺口全部收口**（详见「P3 重排」小节）：P3-1 URI 维默认排除 `{/error,/actuator}` +
  追加键 `forge.exclude-uri-prefixes`（`c47459d`）；P3-2 注解误放提示 `ForgeAnnotationSanityChecker`
  （`500f681`）；P3-3 时机对照写进 SPEC §4.9；P3-4 钉 strict×缓存×debug 的 HTTP 面（三组上下文各加一条）。
  两条变异检验：清空内置默认 / 把误放扫描 `enabled` 改恒 true，均被对应断言当场抓红。
- ✅ **P5-a 管理器三件套**（2026-10-08）：`ForgeManagerController`（页面 `GET /_forge/manager` 自包含 HTML +
  `GET api/routes`（转 `allRoutesWithTiers`）+ `PUT api/config`）+ `ManagerAccessGuard`（IP 白名单，纯对象可单测）+
  `ForgeLevelsStore`（写 `./forge-levels.yml`：备份→原子写→回读比对→失配回滚）。三道门禁 `debug∧manager.enabled∧IP`
  （`OnManagerEnabledAndDebug` 条件 + 控制器内 guard），未开者一个 bean 都不建、零副作用。保存走**热生效**（决策 D1）：
  `PUT` 全量覆盖 levels，落盘成功后 `registry.updateLevels` 换 volatile `LevelsConfig` + `clearAllCache`，即时重算。
  铁律 3：`/​_forge/manager` 并入 URI 维排除（`ForgeRouteRegistry.MANAGER_URI_PREFIX` 单点，控制器 `@RequestMapping` 复用）——
  专测钉住「管理器路由可访问却不出现在 forge 自己视图」。测：guard 单测 + 访问契约（404/404/403/排除）+ 保存回环独立上下文。
- ✅ **P5-b1 「classpath 无 Security」启动 WARN**（2026-10-08）：`ForgeSecurityAdvisory`（探针
  `org.springframework.security.web.SecurityFilterChain`、`ClassLoader` 可注入故可脱容器单测），autoconfig 里
  一个 bean 探测一次、缺则 `LOGGER.warn` 点名 {@code /_forge/**} 无鉴权，**绝不改变行为**（本包不代配 Security）；3 例单测。
- ✅ **P5-b2 首页内嵌摘要**（2026-10-08）：`ForgeSummaryEmbed`（纯 Java API，`script()`＝核心 `SummaryRenderer.render(registry.summary())`，与端点同 producer、复用同一缓存）
  + `ForgeSummaryDialect`（可选 Thymeleaf，`#forgeSummary.summary` 经 `th:utext` 原样输出；`@ConditionalOnClass(org.thymeleaf.TemplateEngine)` 门）。
  `thymeleaf` 仅 `compileOnly`（只用核心 dialect SPI，Boot 自动收 `IDialect` bean）；哨兵类路径镜像之。4 例测：embed 逐字等于核心渲染 / 输出未转义；方言 bean 注册 / 真渲染 raw `<script>`。
  踩点：Thymeleaf 表达式对象按 **注册名** 访问（`#forgeSummary` = 工厂 buildObject 返回的对象），非 `方言名.对象名`；故 buildObject 返回带 `getSummary()` 的包装对象，模板 `#forgeSummary.summary` 才成立（`AbstractDialect(name)` 构造、`getName()` final、工厂要 `getAllExpressionObjectNames/buildObject/isCacheable` 三法——均 javap 实测得来）。
- ⏳ **P5-b3 Redis `CacheStore` 驱动**：用户 2026-10-08 决定**暂缓**（单实例够用；多实例共享才需要）。现状诚实：`cache-driver=redis` 启动即抛 `RF_BE_003`（不静默退回内存）、SPEC §3 已标未实现。将来做时的约束：值序列化用 **JdkSerializationRedisSerializer**（spring-data-redis 自带，类型自持，**勿引 Jackson** 否则重开 Boot4 Jackson3 断层）、经 `@ConditionalOnClass`/`ObjectProvider` 取 `RedisConnectionFactory`（无 redis 依赖则 `RF_BE_003`）、mock `RedisTemplate` 测（本机无 Redis 服务，不做 live IT）。
- ⏳ **P6** 示例后端 + Vue/React 双前端 pnpm 联调 + 真实 Laravel HTTP golden 端到端对等 + maven-publish/signing

## P3 重排（2026-10-07，对着 `G:\web\php-laravel` 参照逐条核过）—— ✅ P3-1..P3-4 全部收口

**先记账：原 P3 的四项已被前面几个阶段吸收，不要再当待办做**（旧 ⏳ P3 行已删）。

| 原 P3 项 | 现状与证据 |
|---|---|
| `@ForgeTier` 类/包继承接线 | ✅ 已做：`ForgeTiers.resolve()` 含 `getPackage()` 分支（`annotation/ForgeTiers.java:23-45`），`HandlerMethodRouteSource:79` 接线，P2-1 有断言 |
| classifier bean | ✅ 已做：`ForgeAutoConfiguration:107` 注 `ObjectProvider<RouteClassifier>` → registry → `TierResolver` |
| 双通道冲突 fail-fast | ✅ 已做：`ForgeDeclaration` 的 RF_BE_010（P2-3，含 1 例冲突断言） |
| strict 聚合上 HTTP | ✅ 已做：P2-4 的 `RouteRepository.infos()` 预扫描 + 错误体 `violations` 按 `debug` 下发，契约测试三组上下文钉住 |

重排后的 P3 = **对照参照查出的真实缺口**，四项按依赖排序。

### ✅ P3-1 框架内部路由的第二维排除（唯一有生产风险的一条）—— 已落地 2026-10-07

Laravel 靠**路由名前缀**排除框架内部路由（`storage.*` 等，见 `ForgeServiceProvider.php:71-78, 228-245`）。
Spring 侧这条路**根本不存在**：宿主不给 `@ForgeRoute(name=...)` 的路由全都是未命名，名字前缀无物可匹。

现在适配层只有 URI 一维（`ForgeRouteRegistry:53-54` 只喂了自家 `endpoint_prefix`）。而
`StrictViolationScanner` 的兜底口径是「未命名且不命中任何层级 → 不属于 forge 管辖」（其 javadoc:27-28
举的理由正是"否则框架内部路由会把严格模式报错刷满"）。**这条兜底在 Spring 侧比在 Laravel 侧脆得多**：
宿主只要把某个层级的 `match.prefix` 写宽（`/`，或 `/api` 而 actuator 挂在 `/api/...` 下），
`/error`（Boot 的 `BasicErrorController`）、`/actuator/**`、springdoc 的 `/v3/api-docs`
就会整批落进 `missing_name`，把严格模式刷满 500——而宿主**没有任何办法**给它们命名来消掉。
CI 门禁会被永久打断，且报错文案指向的是框架路由，排查方向是错的。

**落地（两处拍板，用户 2026-10-07 定，均选 A）**：
1. URI 维并入内置默认集 `{/error, /actuator}`（`ForgeRouteRegistry.FRAMEWORK_URI_EXCLUSIONS`，段级匹配，
   未引依赖时空转无害）；**刻意不放 springdoc**（路径多变、变体众多，写进默认集是「假完备」）。
2. 新增配置键 `forge.exclude-uri-prefixes`，宿主值是**追加**到内置默认（并集、默认不可清空——防宿主一个值
   把 /error 丢了又 500）。URI 维只作用于**未命名**路由，显式命名的 `/error` 业务路由不受影响。
   合并逻辑单点在 `ForgeRouteRegistry`（endpoint ∪ 内置 ∪ 宿主），命令行/严格扫描/仓库共用同一 filter。
   SPEC §3 加了该键并标注为 Java 专属扩展、§4.5 展开根因与边界。

验收：`ForgeUriExclusionTest` 走 `registry.analyze()` 断言未命名 `/error`、`/actuator/health` 被排除、
`/v3/api-docs` 与段边界外的 `/errorlog` 仍在、宿主追加 `/v3/api-docs` 后一并排除、null/无斜杠形态宽容。
**变异检验**：把内置默认清空 → 三条排除断言 + strict 违例断言当场变红（证明咬得住）。
（注：用合成 `RouteInfo("/error")` 代替真实 `BasicErrorController` 路由——排除按 URI 字符串段级匹配，
与来源无关，一条 RouteInfo 足以钉死口径，不为此再拉一组 Spring 上下文。）

### ✅ P3-2 「注解写在不会被消费的位置」的提示 —— 已落地 2026-10-07

参照物是 `ForgeRouteRegistrar::__destruct`（`php-laravel:138-165`）：组属性挂在 Registrar 上却从未被消费
→ strict 下 error、否则 warning，**刻意不抛**（析构期抛异常致命）。

Java 侧的对应场景确实存在，而且本仓已踩过其中两个：`@Forge` 标在没有映射注解的方法上（永不成为 handler）、
`@ForgeRoute` 标在漏了 `@Controller`/`@RestController` 的类上（「断言必须防真空通过」第 ① 次教训就是这个）。

**落地（两处拍板，用户 2026-10-07 定，均选 A）**：新增 `ForgeAnnotationSanityChecker`（`SmartInitializingSingleton`，
装配后跑一次，比对已注册 handler 签名集，把带 `@Forge`/`@ForgeRoute` 却没成为 handler 的本类局部方法经 `WarningSink`
出提示，绝不抛）。① 触发：只在 `debug=true` 或 `strict=true` 时启用（生产两者皆非 bean 直接返回、零反射）；
② severity：恒 warning，不给 WarningSink 扩 error 通道（Laravel 的 strict→error 分级记为有意差异）。
**刻意收窄**：`@ForgeTier` 类/包级误放（继承/package-info/懒加载误报面大）、以及完全没进容器的类（需 classpath 扫）
都不做——只留近零误报的两类。SPEC §4.8 展开。

验收：`ForgeAnnotationSanityTest`（自建 web 上下文 + 录音 WarningSink，`@Import` 三条被测类）端到端验证：
debug 上下文报出「非 controller 的 @ForgeRoute」与「无映射的 @Forge」两类、合法映射不误报；默认上下文（debug/strict 皆非）
不出任何该提示。**变异检验**：把 autoconfig 的 `enabled` 改成恒 true → 「默认上下文不扫」那条当场变红（证明 debug‖strict
门禁真被钉住）。测试踩点：断言谓词按整句 grep 时，中文措辞差一个「的」就静默不匹配——已对齐为子串「不会被消费的位置」。

### ✅ P3-3 时机差异记账（纯文档）—— 已落地 2026-10-07（写进 SPEC §4.9）

对照后要认下来的三条等价、一条差异，写进 SPEC 免得下个会话当缺口重做：

- **层级名合法性**：PHP 在三个定义入口（宏 / Registrar / `updateGroupStack`）即查 `config('forge.levels')`
  抛 `UnknownLevelException`；Java 在 `TierResolver` 抛 RF_BE_002。Laravel 的路由文件每次请求都要重新注册，
  所以两侧都是「同一次请求内、响应之前」，**形态等价**，不需要提前到启动期；
- **悬空别名 / 别名撞车 / 同名跨层级重复**：PHP 全部延迟到扫描期（`AliasResolver.php:109-116, 146-154`、
  `RouteAnalyzer.php:177-190`），撞车时真实路由赢、别名丢弃并 warning——Java 已同形（核心层移植自同一份代码）；
- **PHP 没有任何启动期预热扫描**。Java 若为了"更早报错"加启动期扫一遍，会连带把 strict 聚合提前到启动期，
  宿主行为就从"请求 500"变成"启不来"——**不要做**；§4.8 的误放扫描是安全例外（只出 warning、绝不抛、不参与
  也不提前 RF_BE_009 判定）；
- 唯一 Java 多出来的时机面：`GuardLabels` 的显式/派生不一致提示是无条件经 `WarningSink`（SPEC §4.4 已记），
  加 §4.8 误放提示。另补一条 Spring 口径差：`strict-mode` 启动期绑定、单 JVM 内不可运行时翻转（见 P3-4）。

### ✅ P3-4 strict × 缓存 × debug 三件套的 HTTP 面断言 —— 已落地 2026-10-07

PHP 侧这四条是一个组合：缓存命中跳过预扫描（`RouteRepository.php:108-111, 181-184`）、违规**永不**入缓存
（`cache->set` 在扫描之后，:158/:239）、`debug` 整体旁路缓存、两个端点同口径。Java 结构上已满足
（`RouteRepository:83-85, 113-115` 命中即 return；`infos()` 先抛后 `cache.set`）。

**Spring 模型下的口径差（重要，别照 PHP 抄翻转测试）**：PROGRESS 原计划的「strict 500 → 关 strict 200 →
再开 strict 仍 500」翻转，在 Spring 一个 @SpringBootTest 上下文里**结构上不可能**——strict 是启动期绑定的属性，
不能运行时翻转。故据等价事实钉三条（`ForgeEndpointsContractTest` 三组上下文各加一条）：
① strict 无 debug：连取两次 admin 均 500 且 `cache.get("admin")` 为 null（违规路径从不 cache.set，不会被缓存命中
跳过预扫描而"洗白"）；② 非 debug 上下文 `cache.disabled()==false`（缓存生效）；③ debug=true 上下文
`cache.disabled()==true`（整体旁路、每请求重扫）。缓存的读写旁路与 `forgetLevel` 连带失效由核心层 `RouteCacheTest`
已钉，这里补的是装配/HTTP 面这一环。

### 给 P4 记下的参照事实（现在不动手，照抄时最容易弄反）

- **`list` 与 `types` 对严格模式违规的处理刻意相反**：`list` 有违规**照常出全表**、末尾追加红色清单、退 1
  （排查场景命令必须还能跑出全貌）；`types` 有违规**拒绝产出任何** d.ts/JSON、退 1
  （d.ts 会被 commit，宁可不出，也不给出一个"看起来是对的"的错契约）。
- **产物纯净性在 Java 侧是全新的一整件事**：Laravel 有 `WritesToErrorOutput` trait 把辅助信息挪到 stderr
  （并在测试注入 `BufferedOutput` 拿不到 error 流时回退同流）；而 **Spring Boot 的 logback console
  默认就写 stdout**，`--forge:types > x.d.ts` 会被 banner 与日志一起污染。P4 必须先定日志目标口径。
- 退出码：Forge 异常 → 打 `[错误码] 消息` 退 1；未知 level → 退 1；有产物即 0（warnings 不影响退出码）。
- `list` 的选项互斥：`--unnamed` 与 `--json` / `--unassigned` / `--aliases` 互斥，组合即报错退 1；
  `--level` 额外接受 `unassigned`，`--unnamed` 时接受虚拟分组 `unresolved`。
- `--json` 字段集：`{levels, filter, count, tier_counts, warnings, routes[...]}`，`tier_counts`
  含所有层级与 `unassigned`（0 也列出）；**着色（品红=unassigned、黄=别名、绿=被指向、红=撞车）只存在于表格模式**。
- `clear --level` 必须走 `forgetLevel`（内含「层级失效连带失效 summary」的不变量），禁止直接 `forget(key)`。
- 注意命令名的形态差异：PHP 是 `route:forge:list` 这类 Artisan 命令名，Java 侧已定为 `--forge:list` 参数式
  `ApplicationRunner`——这是 Java 专属扩展，不是对等物，别在 P6 的 Laravel golden 比对里当差异查。

## 跨语言对等的工作流（重要）

期望值**不手写**。`fixtures/php/` 下的 oracle 脚本调用 `G:\Web\php-common` 的真实代码产出 `expected/*.json`，
Java 测试读 JSON 逐字段比对。改了语义要重新生成 fixture，且必须先看 diff 再提交（fixture 变更 = 契约变更）。

```bash
php fixtures/php/oracle-tier.php         > fixtures/php/expected/tier-resolver.json
php fixtures/php/oracle-alias-strict.php > fixtures/php/expected/alias-strict.json
```

php-common 无 vendor：`bootstrap.php` 自带 PSR-4 装载与 `Psr\Log\LoggerInterface` 桩。
提交里记了 `provenance.phpCommonCommit`，对等断言的是那个 commit 的行为。

## 已由 oracle 抓出的偏差（勿再犯）

1. `RF_BE_001` 消息在无名路由上是 `Route  (uri)`（PHP 把 null 插值成空串，两个空格），Java 直拼会变成 `Route null (uri)`。
2. `prefix: [null]` 这类畸形配置 PHP 宽容归一，`List.copyOf` / `Map.copyOf` 会 NPE → 核心层集合一律用 unmodifiable 包装。
3. 显式层级名拼错的 `RF_BE_002` 对**无名**路由不可达（前置守卫先返回），消息里的 `(uri)` 兜底只有 `RF_BE_006` 走得到。
4. `middleware_match` 的类型守卫在 prefix 循环**之前**执行 → 无效类型的告警按「路由 × 层级」出现，且该层级没配 middleware 也照样告警。
5. PHP 空关联数组 `json_encode` 出 `[]` 而非 `{}`，fixture 读回的层级配置可能是空 List → `LevelsConfig` 值类型必须宽容。
6. classifier 抛错文本含异常类名，两侧必然不同（PHP 无包前缀）→ 唯一按归一化比对的字段。
7. **fixture 不得用 `json_decode(..., true)` 过一遍**：它把空 `stdClass` 变成 `[]`，而「空层级 / 空
   `parameter_defaults` 必须是 `{}`」正是这条契约最容易坏的地方。仓库层 fixture 改为冻结
   `json_encode` 后的**文本**，Java 侧比字符串。
8. 管理器条目的 `parameter_defaults` 在 PHP 里是裸数组（空→`[]`），与层级端点的 `(object)`（空→`{}`）不同；
   Java 侧刻意保留这个差异以做到字节一致，别当 bug「顺手修正」。

9. **`Map.copyOf` 不保证迭代顺序**：`tier_counts`、别名表这类「键序即契约」的产物一旦被它接管，
   顺序就变成哈希顺序——分析器的对等测试正是这样抓到 `{"admin":1,...}` 变成 `{"unassigned":1,...}`。
   一律用 `Collections.unmodifiableMap(new LinkedHashMap<>(..))`。
10. **Gradle 测试结果可能是陈旧的**：改完生产代码后 `grep FAILED` 抓到 0 不代表真跑过（UP-TO-DATE）。
    复验一律带 `--rerun-tasks`，本项目已形成习惯。

## 对等测试的有效性要做变异检验

「全绿」不等于「测到了」。P1e-1 用三个变异验证断言真会咬：注入「别名不写入产物」→ 4 例失败；
注入「摘要不落缓存」→ 缓存调用序列断言失败；注入「摘要用未归一的 cache_ttl」→ **无失败**，
因为它与已归一的值永远相同，据此发现并删掉了冗余访问器 `normalizedCacheTtl()`。
以后每个 oracle 落地时至少做一次变异检验。

## 待办与后续需要拍板的点

当前无阻塞项。P1、P2-1..P2-4、守卫标签第四通道、两端点/自动装配、P4 命令行三件套、以及带时间窗口的**版本支持边界（第 3 条，2026-10-08 已落地）**均已收口，下一步是 P5 管理器。
进入 P5 前尚有一处待拍板（下面第 2 条：内嵌摘要是否强制绑 Thymeleaf）；第 1 条已定。

1. ✅ **已定（2026-10-06，准则换成「按大多数开发者的实际写法」）**：`endpoint_middleware` **不做表达式翻译、
   也不代宿主配 Security**。实测主流写法就是自己去 `authorizeHttpRequests` 里加一行（GitHub 命中文件数
   34.8 万，与方法注解派 27 万并列两大主流），而 Boot 默认已 `anyRequest().authenticated()`，本包再注册
   一条链只会打架。于是 Java 侧它是纯声明值，`spring-security-*` 连 `compileOnly` 都不引——守卫注解按
   **类型全名**反射识别即可。层级标签改由第四通道从守卫注解派生，规则表与两条边界见 SPEC §4.4。
   口径变更的连带面：`build.gradle.kts` 里「接 Security」的注释、以及各处提到端点保护的 javadoc，落地时一并扫。
2. ✅ **已定（2026-10-08）内嵌摘要不强制 Thymeleaf**：纯 Java 渲染 API（`SummaryRenderer` 已产 `window.__ROUTE_FORGE__`
   自删 `<script>`、script-safe 转义，P1f 落地）为主入口；Thymeleaf 方言片段做成**可选**（`@ConditionalOnClass` 守护，
   宿主引了才启用），不逼纯 SPA 宿主背模板引擎依赖。属 §5.4 那一步再做，不挡当前 P5 管理器主线。
3. ✅ **版本支持边界（2026-10-07 拍板「按最省的兼容来做」，2026-10-08 一笔 `build(starter)` 落地）**

   **落地结果与两处被实测推翻的前提**（下面「四件改动」原样保留，仅记实际差异）：
   - ✅ 四件改动全部完成：`release=21→17` + 注释交代两线口径；删 catalog 死配置 `java="21"`；starter 删 main 的
     `compileOnly(jakarta.servlet)`；`BuildConventionTest` 改读自身 class 头 major==61；SPEC §8.1 补三行支持边界口径；
     README×2 与 AGENTS.md 同步 Java 17。全量 `clean build --offline --rerun-tasks` 绿；`javap` 抽查 main/test 均 major 61。
   - ⚠️ **推翻 1（原计划「test 侧 servlet 一并删」错）**：`testImplementation(jakarta.servlet.api)` **不能删**——端点契约测试里
     MockMvc 的 `result.getResponse().getStatus()` 要编译器能解析 `MockHttpServletResponse` 的父接口
     `HttpServletResponse`，测试源码从不写 `jakarta.servlet` 字样但类路径缺它就「无法访问 HttpServletResponse/找不到类文件」。
     只有 **main 的 compileOnly** 那行是真死依赖（`compileJava` 不引它也过）。删它时踩了红：`compileTestJava FAILED`，改回即绿。
   - ⚠️ **推翻 2（原计划把 Boot 3.5 记为「FW 6.1」不准）**：`compileBoot35SentinelJava` 解析出的依赖树是
     **Boot 3.5.16 → Framework 6.2.19**（Boot 3.4/3.5 线本就随 FW 6.2，6.1 是 Boot 3.3）。哨兵注释与 SPEC 已改为 6.2；
     「用到的 API 自 FW 6.1 起即存在」仍成立，只是**门禁只钉到实测过的 Boot 3.5.16=6.2.19**，不外扩。
   - ✅ **哨兵非空跑（变异检验）**：往 src/main 塞一个 `Resource.getFilePath()`（FW 7.0.9 有、6.2.19 无）——主 `compileJava`
     照常绿、哨兵 `找不到符号 getFilePath()` 变红；探针删掉后哨兵回绿。证明它真按 6.2 类路径编真源码、能咬版本差异。
     catalog 顺带删了孤儿 `jakarta-servlet-api` 别名后又因推翻 1 恢复（现仅 test 路径引用）。

   **为什么现在做**：承诺支持是单向棘轮——`0.1.0` 写了「支持 Boot 3.5」，以后收回要付一个 major；写了「只支持 4.x」，
   永远不用道歉。而基线数字是另一件事，两者别捆：`release=21` 挡掉的主要人群根本不是 Boot 3 用户，而是
   **Boot 4 + JDK 17**（Boot 4 官方只要求 Java 17，17 是 JDK 的 LTS，大量宿主升了 Boot 不升 JDK）。

   **已实证、不必重查的四条**（2026-10-07 对着 `src/main` 全量 import 与语法探针扫过）：
   - main 侧 Spring API 全部落在 Framework **6.1 ∩ 7.x 交集**内（`AutoConfiguration` / `ConditionalOn*` /
     `@ConfigurationProperties` 构造绑定 / `ObjectProvider` / `MergedAnnotations.from(elem, TYPE_HIERARCHY)` +
     `getString`/`getStringArray`/`VALUE` / `getHandlerMethods` / `getPathPatternsCondition` / `getPatternValues` /
     `getMethodsCondition` / `getPatternString` / `getBeanType`），Boot 3.2（FW 6.1）全都有；
   - main 侧**零 Jackson**（JSON 走自研 `JsonWriter`，端点返回 `Map<String,Object>`）→ 天然免疫 Boot 4 最大的
     Jackson 2→3 断层（`com.fasterxml.jackson` → `tools.jackson`）；
   - main 与 test 均**零 servlet 引用**（连全限定名都没写）→ 免疫 EE 10→11 / Servlet 6.0→6.1 断层；
     顺带证明 `compileOnly(libs.jakarta.servlet.api)` 是**死依赖**；
   - 守卫注解只以**类型全名字符串**出现（`javax.*` 与 `jakarta.*` 双认），main 零 Security 依赖。

   **四件改动（一笔 `build(starter)` 提交，不与 P5 混）**：
   1. `build.gradle.kts`：`options.release.set(21)` → `17`，注释里「Java 21 为库的公开基线（Spring Boot 4 自身要求
      17+）」改成同时交代两线口径。**连带清掉 `gradle/libs.versions.toml` 第 4 行的 `java = "21"`**——已实证全仓无
      任何 `.kts` 引用它（真基线只有 `options.release` 一处），留着就是「两处声明一处生效」，改完 17 之后 catalog
      里的 21 会误导下个会话。**做法是删掉这条，不要改成 `options.release.set(libs.versions.java.get().toInt())`**：
      catalog 的职责是依赖版本，语言级别归根 `build.gradle.kts`（同文件注释「避免两处漂移」的既有分层）。
   2. `forge-spring-boot-starter/build.gradle.kts`：删 `compileOnly(libs.jakarta.servlet.api)` 及其 test 对应行；
      新增**只做 `compileJava`、不跑测试**的 Boot 3.5 哨兵 source set/configuration；
   3. `gradle/libs.versions.toml`：加 `springBoot35` BOM 别名（仅哨兵用），`springBoot = "4.1.1"` 不动；
   4. `BuildConventionTest#targetsJava21`（在 **forge-core** 的 test 里）：现在断言 `Runtime.version().feature() == 21`，
      **钉的是构建机 JVM 而不是编译产物**——换台 JDK 25 的机器就假红。改为读自身 class 文件头 8 字节的 major version
      断言 `== 61`（与同文件 `-parameters` 那条反射探针同风格）。
      **注意这条旧断言的行为是反直觉的**：降 `options.release` 到 17 之后它**仍然会绿**（构建机还是 JDK 21），
      于是「全量门禁通过」完全不能证明基线真的降了、也不能阻止以后有人改回 21——它压根没在守这件事，这正是本条要修的
      缺陷，属于本文件「断言必须防真空通过」那一节的同类问题。所以两条必须**同笔**提交；只改断言不降 release 会当场
      红（产物还是 65，属正常），只降 release 不改断言则静默假绿。

   **哨兵的能力边界（写进它的注释，别升口径）**：它只保证「对着 FW 6.2（Boot 3.5.16 实测映射）编得过」，**不保证运行期行为一致**。
   真正会分叉的是 `YamlShape` 依赖的 Boot Binder 索引 Map 形态（`{0=/admin}`，本仓在 Boot 4 上实测得来）——3.5 上
   未做运行验证。所以口径只能停在「物理兼容、untested」，**不许在 SPEC/README 写成「支持 Boot 3」**。

   **SPEC 的三行承诺口径（tested on 4.x / 兼容 3.5+ 未测试 / 明确排除 Boot 2）与 §7 那行「Java 21 基线」，
   必须与上面四条同一批提交落地**——本仓已有一次「先写进 SPEC 的话被自己收回」，未落地的口径不进 SPEC。

   验收：`./gradlew.bat clean build --offline --rerun-tasks` 全绿；哨兵 configuration 单独跑一次 `compileJava` 绿；
   再做一次变异检验——往某个 main 类塞一个 FW7-only 方法调用，**哨兵必须变红**（证明它咬得住，而不是空跑）。
   最后 `javap -v` 抽查任一 class 确认 major 61。

## 恢复步骤

```bash
cd G:/Java/route-forge-springboot
export GRADLE_USER_HOME="$PWD/.gradle"   # cmd 下：set GRADLE_USER_HOME=G:\Java\route-forge-springboot\.gradle
./gradlew.bat build --rerun-tasks          # 全量门禁（复验一律带 --rerun-tasks）
./gradlew.bat :forge-core:test           # 单模块
./gradlew.bat clean build --offline      # 本地缓存自足性检查（加新依赖后必查）
```

提交规范：`type(scope): 中文描述`，scope 用 `core` / `starter` / `build` / `docs` / `example`；提交前跑全量；不 push。
