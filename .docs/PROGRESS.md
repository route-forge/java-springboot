# 实施进度与交接

> 冷启动恢复用：新会话先读本文件 + `AGENTS.md` + `.docs/SPEC.md`，不必重读 PHP 参照仓。
> 阶段划分与验收口径见 `AGENTS.md`；本文件只记「已经定了什么、做到哪、下一步是什么」。

## 已锁定的决策

| 主题 | 结论 |
|---|---|
| 能力范围 | v1 全量对等 SPEC（含管理器页面与 d.ts 生成） |
| 交付形态 | 双模块库（`forge-core` + `forge-spring-boot-starter`）+ 示例后端 + Vue3/React 双前端 |
| 语言/构建 | Java 21（`options.release` 锁定，不用 toolchain 自动下载）+ Gradle Kotlin DSL + wrapper 9.7.0-all |
| Spring 基线 | Spring Boot 4.1.1（2026-10 最新稳定；4.2.0 仅 milestone） |
| 坐标 | group `io.github.route-forge`（对应 GitHub org），artifact `forge-core` / `forge-spring-boot-starter`；Java 包根 `io.github.routeforge.*`（包名不允许连字符） |
| 命名通道 | 形态 C：`@ForgeRoute` 组合注解（meta `@RequestMapping`，全量 `@AliasFor` 透传）+ `@Forge` 副注解逃生舱 + `RouteNamingStrategy` SPI（默认关）；冲突 fail-fast = RF_BE_010 |
| tier 继承 | 五级优先级不变：方法级 = 显式 > 类/包级 `@ForgeTier` = group 继承 > `RouteClassifier` bean > `match` > `unassigned` |
| middleware | `match.middleware` = 元数据标签（不参与真实鉴权）；`endpoint_middleware` = 真实接入 Spring Security |
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

## 本阶段新发现的形态细节（照抄，不「顺手修正」）

- **`--json` 顶层在目标层级为 0 时 PHP 输出 `[]`**：顶层没做 `(object)` 强转，只有每个层级块做了。
  Java 照抄并在 SPEC 注明，脚本侧要能容忍顶层两种形态。
- **d.ts 文件头时间来自 `date('Y-m-d\TH:i:s.000\Z')`**：毫秒位恒为字面量 `.000`、`Z` 也是字面量、
  时区取进程默认（非 UTC 机器会写出「本地时间 + Z」）。Java 照抄该形态，同时把时间做成可注入入参以便测试。
- **emoji 的代理对不能直接喂 `%x`**：`String.format("%04x", Character)` 抛 IllegalFormatConversionException，
  必须显式转 int——这条是被对等测试当场抓到的，纯 Java 单测写不出来。

- ⏳ **P2** 注解与路由扫描、URI 归一化、两端点、异常 advice
- ⏳ **P3** `@ForgeTier` 类/包继承接线、classifier bean、双通道冲突 fail-fast、strict 聚合上 HTTP
- ⏳ **P4** CLI 三命令；**P5** Security 守卫 / 管理器 / `forge-levels.yml` 写回 / 内嵌摘要；**P6** 示例 + 双前端 + Laravel 端 golden + 发布

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

当前无阻塞项。下一步是 P1f（`TypeGenerator` d.ts 逐行 + `SummaryRenderer`），随后进入 P2 的 Spring 侧注解与扫描。

进入 P5 前有两处需要先定，届时会带上下文再问：

1. `endpoint_middleware` 的标签字符串怎么翻译成 Security 表达式（`hasAuthority(标签)` / `hasRole(...)` / 允许写 SpEL 原样）；
   它决定 starter 的可选依赖形状与降级路径。
2. 内嵌摘要（`@forgeSummary` 的等价物）是否强制依赖 Thymeleaf——倾向「纯 Java 渲染 API + Thymeleaf 方言片段可选」，
   不逼没有模板引擎的纯 SPA 宿主引依赖。

## 恢复步骤

```bash
cd G:/Java/route-forge-springboot
export GRADLE_USER_HOME="$PWD/.gradle"   # cmd 下：set GRADLE_USER_HOME=G:\Java\route-forge-springboot\.gradle
./gradlew.bat build --rerun-tasks          # 全量门禁（复验一律带 --rerun-tasks）
./gradlew.bat :forge-core:test           # 单模块
./gradlew.bat clean build --offline      # 本地缓存自足性检查（加新依赖后必查）
```

提交规范：`type(scope): 中文描述`，scope 用 `core` / `starter` / `build` / `docs` / `example`；提交前跑全量；不 push。
