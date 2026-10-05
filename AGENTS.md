# AGENTS.md — route-forge-springboot 工作约定

## 项目定位

Route Forge 家族的 Spring Boot 后端适配包。前端 `@route-forge/core|vue|react`（≥ 3.1.0）**零改动**接入：
契约就是两个 JSON 端点（摘要 + 层级）与可选的 `window.__ROUTE_FORGE__` 内嵌摘要。

- 跨语言端点契约的权威描述：`route-forge/php-laravel` 的 `.docs/SPEC.md`。
- 本仓 `.docs/SPEC.md`：Java 侧落地口径 + Spring 概念映射 + Java 专属扩展。
- 参照实现：`G:\Web\php-laravel`（适配层）、`G:\Web\php-common`（框架无关核心）、`G:\Web\php-thinkphp`（第二适配，看适配点清单）。

## 模块边界

- `forge-core`：纯算法，**零 Spring / 零 Jackson 依赖**。层级解析、别名、严格模式扫描、d.ts 生成、缓存语义。
- `forge-spring-boot-starter`：注解、路由扫描、端点、Security 守卫、缓存实现、CLI、管理器。
  Spring 侧一律 `compileOnly`；`api(platform(bom))` 禁止（不得把 Spring 版本约束强加给宿主）。

破坏这条边界算方向性错误，动手前先请示。

## 构建与验证

```bash
./gradlew.bat build            # 全量：编译 + 测试 + jar
./gradlew.bat :forge-core:test # 单模块测试（迭代期用，最终仍需全量）
./gradlew.bat :forge-spring-boot-starter:build
```

- Gradle：wrapper 9.7.0（`distributionUrl` 指向腾讯镜像）。`GRADLE_USER_HOME` 用本机 `F:/gradle_home`，缓存完整可 `--offline`。
- 镜像加速属个人环境：写在 `GRADLE_USER_HOME/init.d/`，**不进仓库**（仓库只声明 mavenCentral，保证 CI 与他人可复现）。
- JDK：基线 Java 21（`options.release` 锁定，不用 toolchain 自动下载）。
- 编码：全仓 UTF-8 强制（`-Dfile.encoding` / `stdout.encoding`）。断言与 fixture 含中文，Windows GBK 会造成假失败。
- `-parameters` 全仓强制：`@ConfigurationProperties` 构造绑定依赖它，丢了要到运行期才炸，故由 `BuildConventionTest` 把守。

## 行为一致性铁律

1. 任何与 PHP 侧语义分歧的实现，必须在本仓 `.docs/SPEC.md` 显式记为「Java 专属扩展」或「差异」，不许静默分叉。
2. 端点响应逐字段对齐：`levels`/`config` 必发、空层级必须是 `{}`、URI 模板必须剥离 `{name:regex}` 约束。
3. 包自身路由（层级/摘要/管理器端点）必须在**所有**元信息扫描中排除（按来源 + 按规范化 `endpoint_prefix` 的段级 URI 前缀），
   否则 `strict_mode=true` 时包会把自己报成宿主的配置错误、端点必 500。
4. 前端校验语义不变：前端始终抛错、拒绝静默忽略；`strict_mode` 是后端语义，前端无对应开关。

## Git 约定

- 提交信息：`type(scope): 中文描述`，scope 用模块名（`core` / `starter` / `build` / `docs` / `example`）。
- 提交前必跑全量 `./gradlew.bat build`；提交后不 push，等指示。
- 临时产物一律落 `F:/tmp`，不进仓库。
