# Route Forge for Spring Boot

> **状态：预发布。** 产物尚未发布到 Maven Central，前端端到端联调也还没做。下面写的每一条都已实现并有测试守门，但目前没有可以直接复制粘贴的安装坐标——见[安装（预发布阶段）](#安装预发布阶段)。

**语言 / Language:** [English](./README.md) · [简体中文](./README_zh.md)

把 Spring Boot 的路由表**按层级（tier）暴露**给 Vue / React 单页应用：前端凭 `层级名 + 路由名`
构造 URL、发请求，不再硬编码路径，也不必把整张路由表发到浏览器里。

Route Forge 家族成员。线上契约（wire contract，即两侧收发的 JSON 字段约定）与
[php-laravel](https://github.com/route-forge/php-laravel) 逐字段一致，因此**现有的前端包无需任何改动**
就能对接 Spring Boot 后端。

## 它解决什么问题

单页应用调 Spring Boot 后端，通常要么把路径写死（`/admin/users/42`），要么在 TypeScript 里再手维护
一份 URL 清单。两种都会腐化：后端改了路径，前端要到运行期才发现。

Route Forge 把路由表作为元信息发布，并按层级切分：

- `GET /_forge/routes` 返回一份很小的摘要——层级名、描述、路由条数，以及每个层级去哪里取明细。
- `GET /_forge/routes/{level}` 只返回**一个**层级的路由。

只碰 `public` 层级的浏览器，永远不会下载 `admin` 的路由表，更不用说 `internal` 的。
TypeScript 声明文件由同一个真相源生成。

## 这个适配包的位置

- **不征收注解税。** `@ForgeRoute` 是可选的。一条完全没有 forge 注解的路由照样会被扫描、归一 URI、
  归派层级——你只需要给前端真正要调的那些名字。
- **守卫注解是读取，不是重抄一遍。** 层级标签可以从你已经写下的 `@PreAuthorize` / `@Secured` /
  `@RolesAllowed` 派生。识别方式是注解的**类型全名**，所以本库**零 Spring Security 依赖**、
  **不注册任何** `SecurityFilterChain`，也不排序、不改写你的安全配置。
- **`forge-core` 不依赖 Spring，也不依赖 Jackson。** 解析、层级判定、别名、严格模式、d.ts 生成都是
  纯 Java；只有适配层碰 Spring 的类型。
- **该报错的地方就报错，不静默。** 层级名拼错、路由名撞车、别名指向不存在的路由、`defaults` 里写了
  不是 URI 变量的名字——一律抛结构化错误码（`RF_BE_*`），不会被悄悄丢掉。
- **跨语言对等是测出来的，不是说出来的。** `forge-core` 的断言对着冻结在 `fixtures/php/expected/` 的
  php-common 真实产物逐字段比对；全仓测试套件共 327 例、全绿。

## 能力一览

| | |
|---|---|
| 路由元信息 | 摘要端点 + 分层级端点，契约与 Laravel 适配包一致 |
| 层级归派 | 五级优先级：显式声明 → 类/包级注解 → `RouteClassifier` bean → 配置 `match` 规则 → `unassigned` |
| 路由命名 | `@ForgeRoute` / `@Forge` / Spring 原生 `@RequestMapping(name=...)` / 可插拔 `RouteNamingStrategy` |
| URI 归一化 | 剥离 Spring 正则约束（`{id:\d+}` → `{id}`），可选段输出为 `{page?}` |
| 别名 | 对外稳定名指向真实路由，可写在路由上也可写在配置里 |
| 严格模式 | 层级问题聚合成一份报告；违规明细只在 `debug=true` 时下发 |
| 缓存 | 可插拔 `CacheStore` SPI（服务提供者接口），含 TTL、按层级失效、`debug` 旁路；内置内存驱动，可选 Redis 驱动（多实例共享，值走 JDK 序列化） |
| d.ts 生成 | `--forge:types` 用与端点同一套解析器产出 TypeScript 声明 |
| 命令行 | `--forge:list` / `--forge:types` / `--forge:clear` |
| 框架内部路由排除 | 内置 `{/error, /actuator}` 的 URI 维排除，宿主配置只能做加法 |
| 管理器页面 | `debug` + `forge.manager.enabled` + IP 白名单三道门禁；保存写回独立 `forge-levels.yml` 并即时热生效 |
| 首页内嵌摘要 | `ForgeSummaryEmbed` 纯 Java API 产出 `window.__ROUTE_FORGE__` 脚本；Thymeleaf 方言可选（无依赖也能用） |

## 环境要求

| | |
|---|---|
| Java | 17 及以上 |
| Spring Boot | **tested on 4.x**（开发与测试基于 4.1）；Boot 3.5+ 物理兼容但未测试、不承诺；Boot 2 及以下明确排除 |
| Web 技术栈 | Servlet 侧（`spring-boot-starter-webmvc`）且带 JSON 消息转换器；**不支持 WebFlux**，扫描器读的是 `RequestMappingHandlerMapping` |
| 前端 | `@route-forge/core`、`@route-forge/vue`、`@route-forge/react` ≥ 3.1.0 |

## 安装（预发布阶段）

还没有 Maven Central 坐标。想今天就从源码试用，可以用 Gradle 的复合构建（composite build，
把另一个构建直接当成本地依赖）按 `io.github.route-forge` 坐标解析：

```kotlin
// settings.gradle.kts
includeBuild("/path/to/route-forge-springboot")

// build.gradle.kts
dependencies {
    implementation("io.github.route-forge:forge-spring-boot-starter")
}
```

这条路径还没做端到端验证。正式坐标、POM 与签名配置在发布阶段给出。

不需要 `@Import`，也不需要任何 `@Enable...` 开关——自动装配自行注册，且只在 Servlet Web 应用下生效。

## 快速开始

### 1. 定义层级

```yaml
forge:
  strict-mode: true
  cache-ttl: 3600
  levels:
    public:
      description: Anonymous pages
      load: eager
    admin:
      description: Admin console
      load: lazy
      match:
        prefix: /admin
        middleware: [admin]
        middleware-match: all
```

### 2. 给前端要调的路由起名字

```java
@RestController
@ForgeTier("admin")                       // 本类所有路由继承这个层级
class AdminUserController {

    @ForgeRoute(name = "admin.users.show", method = RequestMethod.GET,
            path = "/admin/users/{user:\\d+}")
    UserDto show(@PathVariable long user) { ... }

    @GetMapping("/admin/users")           // 不动它：照样被扫描，只是未命名
    List<UserDto> index() { ... }

    @Forge(name = "admin.users.page", tier = "admin")
    @GetMapping("/admin/users/page/{page}")
    PageDto page(@PathVariable int page) { ... }   // 原生映射注解 + 副注解
}
```

下发给前端的是归一后的形态，可以放心做占位符替换：

```json
{
  "admin.users.show": {
    "uri": "/admin/users/{user}",
    "methods": ["GET", "HEAD"],
    "parameters": ["user"],
    "parameter_defaults": {}
  }
}
```

### 3. 可选：从已有的守卫注解派生层级标签

```java
@PreAuthorize("hasRole('ADMIN')")          // → 标签 "ADMIN"
@Secured("ROLE_SUPER")                     // → 标签 "ROLE_SUPER"
@PreAuthorize("isAuthenticated()")         // → 标签 "__authenticated"
@PreAuthorize("hasRole('A') and isAuthenticated()")  // → 顶层 and 拆开，两个标签
@PreAuthorize("hasAnyRole('A','B')")       // → "expression:hasAnyRole('A','B')" 原样保留
```

凡是不能无损降成名字标签的声明（读了会把要求放宽或收窄的），一律以 `expression:` 前缀原样下发。
角色前缀**绝不**补也**绝不**剥，因为 `hasRole('ADMIN')` 与 `hasRole('ROLE_ADMIN')` 是两个不同的要求。

这些标签只用于层级归类，**不构成安全边界**；本库也不校验方法级鉴权是否真的启用了。

### 4. 前端消费

前端包无需改动——把它们指向摘要端点，层级会被自动发现。见
[route-forge](https://github.com/route-forge/route-forge)。

## 命令行

Spring Boot 没有 artisan，所以命令是 JVM 启动参数，由一个 `ApplicationRunner` 承接。这是 Java 侧的
专属扩展，不是 Laravel 命令名的移植。参数缺席时这个 runner 完全 no-op，因此正常启动和普通的
`@SpringBootTest` 上下文都不受影响。

```bash
java -jar app.jar --forge:list                      # 层级归派表
java -jar app.jar --forge:list --level=admin --json
java -jar app.jar --forge:types --out=src/api/routes.d.ts
java -jar app.jar --forge:clear --level=admin
```

严格模式报违规时，`list` 与 `types` 的处理**刻意相反**：`list` 照常输出全表并退码 1（排查问题时需要
全貌），`types` 则**拒绝产出任何文件**——d.ts 是要提交进仓库的，一份"看起来是对的"的错契约比没有文件
更糟。约定是 stdout 只走产物，一切反馈走 stderr。

## 配置项（`forge.*`）

| 键 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `levels.<name>.description` | `string` | `""` | 层级描述 |
| `levels.<name>.match.prefix` | `string[]` | `[]` | URI 前缀，按段匹配 |
| `levels.<name>.match.middleware` | `string[]` | `[]` | 归类用的标签集合 |
| `levels.<name>.match.middleware-match` | `any`\|`all`\|DNF | `any` | DNF 形态是 `List<List<Integer>>` |
| `levels.<name>.load` | `eager`\|`lazy` | `lazy` | 下发给前端的预加载提示 |
| `endpoint-prefix` | `string` | `/_forge/routes` | 元信息端点前缀 |
| `url-prefix` | `string?` | `null` | 下发给前端的 URL 前缀 |
| `cache-ttl` | `int?` | `3600` | `null` 不缓存、`0` 永久、负值归一为 `null` |
| `cache-driver` | `memory`\|`redis` | `memory` | `redis` 走 spring-data-redis（可选依赖，值用 JDK 序列化）；缺依赖 / 无 `RedisConnectionFactory` bean / 配成未知驱动，启动即抛 `RF_BE_003`，绝不静默退回内存 |
| `strict-mode` | `bool` | `false` | 层级问题聚合成一条 `RF_BE_009` |
| `scheme-version` | `int` | `1` | 摘要格式版本 |
| `aliases` | `map` | `{}` | 别名 → 真实路由名 |
| `exclude-uri-prefixes` | `string[]` | `[]` | **Java 专属扩展**：在内置 `{/error, /actuator}` 之外做**追加** |

数组型键接受单值（`forge.levels.admin.match.prefix=api/admin` 等价于单元素列表），与 PHP 侧的归一口径一致。

自定义分类器是注册一个 `RouteClassifier` bean，不进配置文件。

开发态判据是 Spring 自身的顶层 `debug` 开关——**不是** `forge.debug`。它旁路元信息缓存（改路由或改配置
即时生效），并解锁错误响应里的结构化 `violations` 清单。

## 端点

```
GET {endpoint-prefix}           → { schemeVersion, levels{...route}, config{...} }
GET {endpoint-prefix}/{level}   → { level, routes{...} }
```

`levels` 与 `config` 恒定存在，空层级序列化为 `{}`（**绝不会**是 `[]`），URI 模板永不携带正则约束——
前端的占位符正则替换不了 `{name:regex}`。错误返回 `{"error": {"code": "RF_BE_...", "message": "..."}}`；
结构化 `violations` 清单只在 `debug=true` 时给出，因为它是一份路由名与路径的目录。

`/_forge/**` 的保护由你负责：在你自己的 `authorizeHttpRequests` 规则里声明。

## 已知限制

- 不扫描 `RouterFunction`（函数式端点）——它的路径谓词不暴露模板字符串。
- 不支持 WebFlux。
- 层级名在启动期绑定，同一个 JVM 内无法运行期翻转 `forge.strict-mode`。

## 还没有的部分

Maven Central 发布，以及 Vue/React 的端到端联调。当前阶段清单见
[`.docs/PROGRESS.md`](.docs/PROGRESS.md)。

## 文档

- [`.docs/SPEC.md`](.docs/SPEC.md)：Java 侧契约口径、Spring 概念到 forge 语义的映射，以及本适配包
  与 Laravel **刻意分歧**的每一处。
- 跨语言契约的权威描述在
  [php-laravel 的 `.docs/SPEC.md`](https://github.com/route-forge/php-laravel/tree/main/.docs)。

## 许可

MIT，见 [LICENSE](./LICENSE)。
