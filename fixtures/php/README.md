# PHP 参照器（oracle）

跨语言对等测试的期望值生产现场。**不是测试夹具的手写样例**：所有 `expected/*.json` 都由本目录的脚本
调用 `G:\Web\php-common`（route-forge 家族的框架无关核心）里的<b>真实代码</b>跑出来。

## 为什么要有这一层

层级解析、别名、严格模式这些逻辑里有大量「文档没写但行为存在」的细节：`prefix !== ''` 的严格比较、
`in_array(..., true)` 的类型严格性、DNF 越界索引、`null` 名字被插值进错误消息变成两个空格、
`middleware_match` 类型守卫在 prefix 循环之前执行所以告警按「路由 × 层级」出现……

Java 侧照文档自己写断言，测的是「我对文档的理解」，不是对面那套实现的真实语义。所以期望值必须由 PHP 自己产出。

## 重新生成

前置：本机可用 `php`（当前产物由 PHP 8.5.11 生成），且 `php-common` 源在 `G:\Web\php-common`
（可用环境变量 `ROUTE_FORGE_COMMON_SRC` 覆盖）。

```bash
php fixtures/php/oracle-tier.php > fixtures/php/expected/tier-resolver.json
```

生成后必须核对 JSON 里 `provenance.phpCommonCommit` —— 它记录了本次取参照时 php-common 的 commit，
对等测试断言的就是「与这个 commit 的行为一致」。php-common 若改了语义，重新生成 fixture 属于跨语言对齐工作，
要连带检查 Java 侧是否需要同步（而不是无声覆盖期望值）。

## 用例约定

- 用例定义只写在 oracle 脚本里一份，Java 测试只读 JSON——避免两处各写一份断言后互相漂移。
- classifier 用描述符表达（`{"return": "admin"}` / `{"returnRaw": 123}` / `{"throw": "boom"}`），
  两侧按同一描述符构造回调，避免「PHP 闭包 vs Java lambda」无法序列化的问题。
- 每条 resolve 用例同时记录 `probeParallel`（对同一输入跑 `probe()` 的结果），
  用于验证「`resolve()` 不抛时 `probe()` 给出同一层级」与「`probe()` 永不抛异常」两条契约。
- classifier 抛错的 `error` 字段含异常类名（PHP `RuntimeException` / Java `java.lang.RuntimeException`），
  这是两侧不可能相同的部分，Java 侧按后缀比对；其余字段一律整句比对。
