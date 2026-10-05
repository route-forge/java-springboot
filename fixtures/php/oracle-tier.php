<?php

declare(strict_types=1);

/**
 * 跨语言对等参照器（oracle）。
 *
 * 用真实 php-common 代码跑一批用例，把输入与输出一起冻结成 JSON，交给 Java 侧的
 * TierResolverOracleTest 断言。用例定义只写在这里一份：Java 侧照文档自己编断言，
 * 等于把「我以为的语义」测了一遍，测不到 PHP 侧的真实语义。
 *
 * 用法（在本机，PHP 8.5 可用）：
 *   php fixtures/php/oracle-tier.php > fixtures/php/expected/tier-resolver.json
 *
 * 重新生成前请确认 php-common 的版本（见文件头输出的 commit）。
 */

$commonSrc = getenv('ROUTE_FORGE_COMMON_SRC') ?: 'G:/Web/php-common/src';

// php-common 无 vendor，这里自带极简 PSR-4 装载；psr/log 只有接口签名需要，用桩补齐。
spl_autoload_register(function (string $class) use ($commonSrc): void {
    if (str_starts_with($class, 'RouteForge\\Common\\')) {
        $path = $commonSrc . '/' . str_replace('\\', '/', substr($class, strlen('RouteForge\\Common\\'))) . '.php';
        if (is_file($path)) {
            require $path;
        }
    }
});

if (!interface_exists(Psr\Log\LoggerInterface::class)) {
    eval('namespace Psr\Log; interface LoggerInterface {
        public function emergency($m, array $c = []); public function alert($m, array $c = []);
        public function critical($m, array $c = []); public function error($m, array $c = []);
        public function warning($m, array $c = []); public function notice($m, array $c = []);
        public function info($m, array $c = []); public function debug($m, array $c = []);
        public function log($l, $m, array $c = []);
    }');
}

use RouteForge\Common\Contract\ForgeExceptionContract;
use RouteForge\Common\Dto\RouteInfo;
use RouteForge\Common\Tier\TierResolver;

/** 收集 warning 的 logger 桩：顺序与内容都要能被 Java 侧复现。 */
final class CollectingLogger implements Psr\Log\LoggerInterface
{
    /** @var list<string> */
    public array $messages = [];

    public function emergency($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function alert($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function critical($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function error($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function warning($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function notice($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function info($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function debug($m, array $c = []): void
    {
        $this->log(null, $m, $c);
    }

    public function log($level, $message, array $context = []): void
    {
        $this->messages[] = (string) $message;
    }
}

function route(?string $name, string $uri, array $middleware = [], ?string $tier = null): RouteInfo
{
    return new RouteInfo(
        name: $name,
        uri: $uri,
        methods: ['GET', 'HEAD'],
        parameters: [],
        parameterDefaults: [],
        middleware: $middleware,
        tier: $tier,
        forgeAliases: [],
        source: null,
    );
}

function levels(array $definitions): array
{
    return $definitions;
}

/**
 * 用例表。
 *
 * classifier 用描述符表达（Java 侧按同一描述符构造 lambda）：
 *   ["return" => "admin"] / ["throw" => "boom"] / ["returnRaw" => 123]
 */
$cases = [
    // --- 优先级 1：显式 tier ---
    'explicit-tier-wins-over-match' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['api']]]]),
        'route' => route('a.b', 'api/orders', tier: 'admin'),
    ],
    'explicit-tier-wins-over-classifier' => [
        'levels' => levels(['admin' => [], 'client' => []]),
        'classifier' => ['return' => 'client'],
        'route' => route('a.b', 'x', tier: 'admin'),
    ],
    'explicit-unknown-level' => [
        'levels' => levels(['admin' => [], 'client' => []]),
        'route' => route('a.b', 'x', tier: 'manage'),
    ],
    // 实证：显式层级名拼错的 RF_BE_002 对「无名路由」不可达——前置守卫（有 tier 无 name）先返回，
    // 因此 RF_BE_002 消息里的 (uri) 兜底分支只在 classifier 的 RF_BE_006 路径上才可能走到。
    'tier-without-name-preempts-unknown-level-check' => [
        'levels' => levels(['admin' => []]),
        'route' => route(null, 'api/x', tier: 'ghost'),
    ],
    'explicit-empty-tier-is-not-explicit' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['api']]]]),
        'route' => route('a.b', 'api/x', tier: ''),
    ],

    // --- 有 tier 无 name ---
    'tier-without-name-strict' => [
        'strict' => true,
        'levels' => levels(['admin' => []]),
        'route' => route(null, 'api/x', tier: 'admin'),
    ],
    'tier-without-name-lenient' => [
        'levels' => levels(['admin' => []]),
        'route' => route(null, 'api/x', tier: 'admin'),
    ],

    // --- 优先级 2：classifier ---
    'classifier-returning-tier' => [
        'levels' => levels(['admin' => [], 'client' => []]),
        'classifier' => ['return' => 'client'],
        'route' => route('a.b', 'x'),
    ],
    'classifier-null-falls-through-to-match' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['api']]], 'client' => []]),
        'classifier' => ['return' => null],
        'route' => route('a.b', 'api/x'),
    ],
    'classifier-empty-string-is-not-a-statement' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['api']]]]),
        'classifier' => ['return' => ''],
        'route' => route('a.b', 'api/x'),
    ],
    'classifier-non-string-is-not-a-statement' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['api']]]]),
        'classifier' => ['returnRaw' => 123],
        'route' => route('a.b', 'api/x'),
    ],
    'classifier-unknown-tier' => [
        'levels' => levels(['admin' => []]),
        'classifier' => ['return' => 'ghost'],
        'route' => route('a.b', 'x'),
    ],
    // 无名 + classifier 表态给了未知层级：RF_BE_006 消息走 (uri) 兜底形态
    'classifier-unknown-tier-unnamed-uses-uri-in-message' => [
        'levels' => levels(['admin' => []]),
        'classifier' => ['return' => 'ghost'],
        'route' => route(null, 'api/x'),
    ],
    'classifier-throws' => [
        'levels' => levels(['admin' => []]),
        'classifier' => ['throw' => 'boom'],
        'route' => route('a.b', 'x'),
    ],

    // --- 优先级 3：prefix 匹配 ---
    'prefix-exact-match' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['admin']]]]),
        'route' => route('a.b', 'admin'),
    ],
    'prefix-segment-boundary-administrator-not-matched' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['admin']]]]),
        'route' => route('a.b', 'administrator/users'),
    ],
    'prefix-nested-segment-matched' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['admin']]]]),
        'route' => route('a.b', 'admin/users/index'),
    ],
    'prefix-single-string-normalized' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => 'admin']]]),
        'route' => route('a.b', 'admin/users'),
    ],
    'prefix-empty-string-skipped' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['']]]]),
        'route' => route('a.b', 'anything'),
    ],
    'prefix-with-leading-slash-does-not-match-laravel-uri' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['/admin']]]]),
        'route' => route('a.b', 'admin/users'),
    ],
    'prefix-null-entry-matches-slash-prefixed-uri-only' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => [null]]]]),
        'route' => route('a.b', 'admin/users'),
    ],
    'prefix-null-entry-matches-slash-prefixed-uri-positive' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => [null]]]]),
        'route' => route('a.b', '/admin/users'),
    ],
    'prefix-numeric-entry-stringified' => [
        'levels' => levels(['v1' => ['match' => ['prefix' => [1]]]]),
        'route' => route('a.b', '1/orders'),
    ],

    // --- 优先级 3：middleware 匹配 ---
    'middleware-any-hit' => [
        'levels' => levels(['client' => ['match' => ['middleware' => ['auth', 'throttle']]]]),
        'route' => route('a.b', 'x', ['auth']),
    ],
    'middleware-any-miss' => [
        'levels' => levels(['client' => ['match' => ['middleware' => ['auth']]]]),
        'route' => route('a.b', 'x', ['guest']),
    ],
    'middleware-all-requires-every-entry' => [
        'levels' => levels(['admin' => ['match' => ['middleware' => ['auth', 'admin'], 'middleware_match' => 'all']]]),
        'route' => route('a.b', 'x', ['auth']),
    ],
    'middleware-all-hit' => [
        'levels' => levels(['admin' => ['match' => ['middleware' => ['auth', 'admin'], 'middleware_match' => 'all']]]),
        'route' => route('a.b', 'x', ['auth', 'admin', 'extra']),
    ],
    'middleware-strict-type-comparison-numeric-config' => [
        'levels' => levels(['admin' => ['match' => ['middleware' => [0]]]]),
        'route' => route('a.b', 'x', ['0']),
    ],
    'middleware-dnf-and-or' => [
        'levels' => levels(['admin' => ['match' => [
            'middleware' => ['auth', 'admin', 'super_admin'],
            'middleware_match' => [[0, 1], [2]],
        ]]]),
        'route' => route('a.b', 'x', ['super_admin']),
    ],
    'middleware-dnf-first-clause-miss-second-hit' => [
        'levels' => levels(['admin' => ['match' => [
            'middleware' => ['auth', 'admin'],
            'middleware_match' => [[0, 1], [1]],
        ]]]),
        'route' => route('a.b', 'x', ['admin']),
    ],
    'middleware-dnf-out-of-range-index-fails-clause' => [
        'levels' => levels(['admin' => ['match' => [
            'middleware' => ['auth'],
            'middleware_match' => [[5]],
        ]]]),
        'route' => route('a.b', 'x', ['auth']),
    ],
    'middleware-dnf-empty-clause-skipped' => [
        'levels' => levels(['admin' => ['match' => [
            'middleware' => ['auth', 'admin'],
            'middleware_match' => [[], [0]],
        ]]]),
        'route' => route('a.b', 'x', ['auth']),
    ],
    'middleware-dnf-non-array-clause-skipped' => [
        'levels' => levels(['admin' => ['match' => [
            'middleware' => ['auth'],
            'middleware_match' => ['nope', [0]],
        ]]]),
        'route' => route('a.b', 'x', ['auth']),
    ],
    'middleware-dnf-string-index-coerced' => [
        'levels' => levels(['admin' => ['match' => [
            'middleware' => ['auth', 'admin'],
            'middleware_match' => [['1']],
        ]]]),
        'route' => route('a.b', 'x', ['admin']),
    ],
    'middleware-match-unknown-string-degrades-to-any' => [
        'levels' => levels(['admin' => ['match' => ['middleware' => ['auth', 'admin'], 'middleware_match' => 'whatever']]]),
        'route' => route('a.b', 'x', ['auth']),
    ],
    'middleware-match-invalid-type-warns-and-degrades' => [
        'levels' => levels(['admin' => ['match' => ['middleware' => ['auth', 'admin'], 'middleware_match' => 7]]]),
        'route' => route('a.b', 'x', ['auth']),
    ],
    'middleware-match-invalid-type-warns-even-without-middleware' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['api'], 'middleware_match' => true]]]),
        'route' => route('a.b', 'api/x'),
    ],
    'prefix-and-middleware-are-or-related' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['zzz'], 'middleware' => ['auth']]]]),
        'route' => route('a.b', 'api/x', ['auth']),
    ],
    'empty-match-block-never-hits' => [
        'levels' => levels(['admin' => ['match' => []]]),
        'route' => route('a.b', 'anything'),
    ],
    'level-without-match-key-never-hits' => [
        'levels' => levels(['admin' => ['description' => '无规则']]),
        'route' => route('a.b', 'anything'),
    ],

    // --- last-wins ---
    'last-wins-takes-later-level' => [
        'levels' => levels([
            'client' => ['match' => ['prefix' => ['api']]],
            'admin' => ['match' => ['prefix' => ['api/admin']]],
        ]),
        'route' => route('a.b', 'api/admin/users'),
    ],
    'last-wins-reversed-order-gives-other-level' => [
        'levels' => levels([
            'admin' => ['match' => ['prefix' => ['api/admin']]],
            'client' => ['match' => ['prefix' => ['api']]],
        ]),
        'route' => route('a.b', 'api/admin/users'),
    ],

    // --- 兜底与严格模式 ---
    'unmatched-lenient-goes-unassigned' => [
        'levels' => levels(['admin' => ['match' => ['prefix' => ['admin']]]]),
        'route' => route('a.b', 'client/orders'),
    ],
    'unmatched-strict-throws' => [
        'strict' => true,
        'levels' => levels(['admin' => ['match' => ['prefix' => ['admin']]]]),
        'route' => route('a.b', 'client/orders'),
    ],
    // 实证：无名 + 无 tier + 严格模式仍走到第 4 级，此时消息里的名字位被 PHP 的 null 插值成空串
    // （"Route  (uri)" 是两个空格）。适配层若先按 isUnnamed 过滤就永远看不到这个形态。
    'unmatched-strict-unnamed-null-name-interpolates-empty' => [
        'strict' => true,
        'levels' => levels(['admin' => ['match' => ['prefix' => ['admin']]]]),
        'route' => route(null, 'client/orders'),
    ],

    // --- probe：与 resolve 同结果，且任何失败都不抛 ---
    'probe-explicit' => [
        'probe' => true,
        'levels' => levels(['admin' => []]),
        'route' => route(null, 'api/x', tier: 'admin'),
    ],
    'probe-explicit-unknown-level' => [
        'probe' => true,
        'levels' => levels(['admin' => []]),
        'route' => route(null, 'api/x', tier: 'ghost'),
    ],
    'probe-classifier-error' => [
        'probe' => true,
        'levels' => levels(['admin' => []]),
        'classifier' => ['throw' => 'boom'],
        'route' => route(null, 'api/x'),
    ],
    'probe-classifier-unknown-tier' => [
        'probe' => true,
        'levels' => levels(['admin' => []]),
        'classifier' => ['return' => 'ghost'],
        'route' => route(null, 'api/x'),
    ],
    'probe-match-hit' => [
        'probe' => true,
        'levels' => levels(['admin' => ['match' => ['prefix' => ['api']]]]),
        'route' => route(null, 'api/x'),
    ],
    'probe-none' => [
        'probe' => true,
        'levels' => levels(['admin' => ['match' => ['prefix' => ['admin']]]]),
        'route' => route(null, 'api/x'),
    ],
    'probe-strict-mode-never-throws-on-unassigned' => [
        'probe' => true,
        'strict' => true,
        'levels' => levels(['admin' => ['match' => ['prefix' => ['admin']]]]),
        'route' => route(null, 'api/x'),
    ],
    'probe-strict-mode-never-throws-on-tier-without-name' => [
        'probe' => true,
        'strict' => true,
        'levels' => levels(['admin' => []]),
        'route' => route(null, 'api/x', tier: 'admin'),
    ],
];

$commit = trim((string) @shell_exec('git -C ' . escapeshellarg(dirname($commonSrc)) . ' rev-parse --short HEAD 2>NUL'));

$out = [
    'provenance' => [
        'generator' => 'fixtures/php/oracle-tier.php',
        'php' => PHP_VERSION,
        'phpCommonCommit' => $commit !== '' ? $commit : 'unknown',
        'note' => 'expected 由真实 php-common 代码产出，Java 侧不得按文档自编断言',
    ],
    'cases' => [],
];

foreach ($cases as $name => $case) {
    $logger = new CollectingLogger();
    // classifier 抛错用例固定抛 RuntimeException，Java 侧亦抛 unchecked 异常，
    // 使 probe 的 error 字段可按「冒号后的消息」这一不变部分对齐（类名两侧必然不同）。
    $classifier = match (true) {
        isset($case['classifier']['return']) => fn (RouteInfo $r) => $case['classifier']['return'],
        isset($case['classifier']['returnRaw']) => fn (RouteInfo $r) => $case['classifier']['returnRaw'],
        isset($case['classifier']['throw']) => function (RouteInfo $r) use ($case) {
            throw new RuntimeException($case['classifier']['throw']);
        },
        default => null,
    };

    $resolver = new TierResolver(
        $case['levels'] ?? [],
        $classifier,
        (bool) ($case['strict'] ?? false),
        $logger,
    );

    $input = [
        'name' => $name,
        'strict' => (bool) ($case['strict'] ?? false),
        'levels' => $case['levels'] ?? [],
        'classifier' => $case['classifier'] ?? null,
        'route' => [
            'name' => $case['route']->name,
            'uri' => $case['route']->uri,
            'middleware' => $case['route']->middleware,
            'tier' => $case['route']->tier,
        ],
    ];

    $probe = (bool) ($case['probe'] ?? false);
    // warnings 必须在调用之后取：resolve/probe 过程中才产生告警，提前快照会永远得到空数组
    $expected = [];

    try {
        $result = $probe ? $resolver->probe($case['route']) : $resolver->resolve($case['route']);
        $expected['mode'] = $probe ? 'probe' : 'resolve';
        $expected['result'] = $result;
        $expected['exception'] = null;
    } catch (ForgeExceptionContract $e) {
        $expected['mode'] = $probe ? 'probe' : 'resolve';
        $expected['result'] = null;
        $expected['exception'] = [
            'code' => $e->code(),
            'httpStatus' => $e->httpStatus(),
            'message' => $e->getMessage(),
        ];
    }

    // 调用之后才取告警：resolve/probe 过程中才产生
    $expected['warnings'] = $logger->messages;

    // resolve 路径额外记录 probe 的平行结果，供 Java 侧断言「不抛时同 level」的一致性约定
    if (!$probe) {
        try {
            $expected['probeParallel'] = $resolver->probe($case['route']);
        } catch (Throwable $e) {
            $expected['probeParallel'] = 'THREW: ' . $e::class;
        }
    }

    $out['cases'][] = ['input' => $input, 'expected' => $expected];
}

$json = json_encode($out, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
// JSON_PRETTY_PRINT 用 4 空格缩进，与 Java 侧生成脚本约定一致
fwrite(STDOUT, $json . PHP_EOL);
