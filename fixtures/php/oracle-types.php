<?php

declare(strict_types=1);

/**
 * 参照器：类型生成、内嵌摘要渲染、以及三种 JSON 形态。
 *
 * 生成：php fixtures/php/oracle-types.php > fixtures/php/expected/types.json
 *
 * 这三样产物的价值全在「字节」上：d.ts 是要 commit 进前端仓的契约文本，内嵌摘要是要塞进 HTML 的脚本，
 * JSON 形态决定两者是否逐字相同。所以期望值一律存原始文本，不做任何形式的规整。
 *
 * d.ts 的文件头「生成时间」按定义每次不同，Java 侧比对时替换为占位符（见 oracle 的 timestamp 约定）。
 */

require __DIR__ . '/bootstrap.php';

use RouteForge\Common\Alias\AliasResolver;
use RouteForge\Common\Analyzer\RouteAnalyzer;
use RouteForge\Common\Filter\RouteNameFilter;
use RouteForge\Common\Summary\SummaryRenderer;
use RouteForge\Common\Support\JsSafeEncoder;
use RouteForge\Common\Tier\TierResolver;
use RouteForge\Common\Type\TypeGenerator;


// ---------------------------------------------------------------------------
// 类型生成用例：rows 由分析器产出（与命令行同源），targets 模拟 --level 收集结果
// ---------------------------------------------------------------------------
$TYPE_CASES = [
    'all-levels-with-optional-and-body' => [
        'levels' => [
            'admin' => ['match' => ['prefix' => ['admin']]],
            'client' => ['match' => ['prefix' => ['client']]],
            'empty' => ['match' => ['prefix' => ['never-used']]],
        ],
        'aliases' => ['admin.users.list' => 'admin.users.index'],
        'infos' => [
            info('admin.users.index', 'admin/users', ['methods' => ['GET', 'HEAD']]),
            info('admin.posts.update', 'admin/posts/{page?}', ['methods' => ['PUT'], 'parameters' => ['page'], 'parameterDefaults' => ['page' => '1']]),
            info('client.orders.store', 'client/orders', ['methods' => ['POST']]),
            info('client.orders.destroy', 'client/orders/{order}', ['methods' => ['DELETE'], 'parameters' => ['order']]),
            info('loose.route', 'loose/route'),
        ],
        'targets' => null,
        'endpointPrefix' => '/_forge/routes',
    ],
    'level-filter-single' => [
        'levels' => [
            'admin' => ['match' => ['prefix' => ['admin']]],
            'client' => ['match' => ['prefix' => ['client']]],
        ],
        'infos' => [
            info('admin.users', 'admin/users'),
            info('client.orders', 'client/orders'),
        ],
        'targets' => ['admin'],
        'endpointPrefix' => '/forge/routes/',
    ],
    'keys-needing-quotes' => [
        'levels' => ['v1.0' => ['match' => ['prefix' => ['api']]]],
        'infos' => [info('api.users.index', 'api/users')],
        'targets' => null,
        'endpointPrefix' => '/_forge/routes',
    ],
    'head-only-route-falls-back-to-get' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'infos' => [info('admin.head', 'admin/head', ['methods' => ['HEAD']])],
        'targets' => null,
        'endpointPrefix' => '/_forge/routes',
    ],
    'empty-table' => [
        'levels' => [],
        'infos' => [],
        'targets' => null,
        'endpointPrefix' => '/_forge/routes',
    ],
];

$typeCasesOut = [];
foreach ($TYPE_CASES as $caseName => $case) {
    $resolver = new TierResolver($case['levels'], null, false, new CollectingLogger());
    $analyzer = new RouteAnalyzer($resolver, new AliasResolver($case['aliases'] ?? []), new RouteNameFilter());
    $analysis = $analyzer->analyze($case['infos']);
    $generator = new TypeGenerator();

    $targets = $case['targets'] ?? array_keys($case['levels']);
    $routesByLevel = $generator->collectTargets($analysis['rows'], $targets);

    $typeCasesOut[] = [
        'name' => $caseName,
        // 输入一并冻结为 JSON 文本：Java 侧要重建同一份 rows（{} 与 [] 的形态在文本里是无歧义的，
        // 若解码成 PHP 数组再导出就会被抹平）
        'input' => [
            'levels' => $case['levels'],
            'aliases' => (object) ($case['aliases'] ?? []),
            'targets' => $targets,
            'endpointPrefix' => $case['endpointPrefix'],
            'infos' => array_map('infoAsArray', $case['infos']),
        ],
        'expected' => [
            // PHP 侧 generateDts 只有两个参数、时间在内部取，这里按行归一为占位符；
            // Java 侧把时间做成显式入参（可测），比对时同样替换成占位符
            'dts' => preg_replace('/^\/\/ 生成时间: .*$/m', '// 生成时间: {{TIMESTAMP}}',
                $generator->generateDts($routesByLevel, $case['endpointPrefix'])),
            'json' => $generator->generateJson($routesByLevel),
        ],
    ];
}

// ---------------------------------------------------------------------------
// 内嵌摘要渲染：输入是一份摘要结构（与摘要端点同形态），比对整段 <script>
// ---------------------------------------------------------------------------
$SUMMARY_FIXTURES = [
    'plain' => [
        'schemeVersion' => 1,
        'levels' => ['admin' => ['description' => '系统管理接口', 'load' => 'lazy', 'route_count' => 2,
            'route' => ['uri' => '/_forge/routes/admin', 'methods' => ['GET', 'HEAD']]]],
        'config' => ['strict_mode' => false, 'endpoint_prefix' => '/_forge/routes', 'url_prefix' => null, 'cache_ttl' => 3600],
    ],
    'xss-probe' => [
        'schemeVersion' => 1,
        'levels' => ['a' => ['description' => '</script><img src=x onerror=alert(1)> "引号" & \'单引号\' 中文 😀 /path',
            'load' => 'eager', 'route_count' => 0, 'route' => ['uri' => "/_forge/routes/a\n\t", 'methods' => ['GET']]]],
        'config' => ['strict_mode' => true, 'endpoint_prefix' => '/_forge/routes', 'url_prefix' => 'https://x.example/a&b', 'cache_ttl' => null],
    ],
    'empty-levels' => ['schemeVersion' => 1, 'levels' => [], 'config' => ['strict_mode' => false, 'endpoint_prefix' => '/_forge/routes', 'url_prefix' => '', 'cache_ttl' => 0]],
];

$summaryOut = [];
foreach ($SUMMARY_FIXTURES as $name => $summary) {
    $summaryOut[] = [
        'name' => $name,
        // 输入以 JSON 文本冻结：Java 侧解析出同一份结构再渲染，
        // 否则「空数组到底是 [] 还是 {}」这种形态歧义会在重建输入时被猜错
        'inputJson' => json_encode($summary, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
        'expected' => [
            'html' => SummaryRenderer::render($summary),
            // 第一层单独冻结：HEX 转义漏项光看整段 HTML 不易定位
            'firstLayer' => json_encode($summary, JSON_HEX_TAG | JSON_HEX_APOS | JSON_HEX_AMP | JSON_HEX_QUOT | JSON_UNESCAPED_UNICODE),
        ],
    ];
}

// ---------------------------------------------------------------------------
// 三种 JSON 形态：把 JsonWriter 的每个转义分支都压到
// ---------------------------------------------------------------------------
$NASTY = [
    'ascii' => 'plain text',
    'slash' => 'https://api.example.com/v1/a/b',
    'quotes' => "双引\"号 单引'号 反斜杠\\号",
    'control' => "换行\n回车\r制表\t退格\x08换页\x0c单元\x1f",
    'non-ascii' => '运营管理接口 😀 全角：',
    'empty-map' => new stdClass(),
    'empty-list' => [],
    'nested' => ['a' => ['b' => [1, 2, new stdClass()]], 'c' => true, 'd' => null, 'e' => 3.5],
    'keys-with-special' => ['带 空格' => 1, 'a.b' => 2, 'ok_key' => 3],
];

// 每种形态的 flags 与 Java JsonWriter.Style 的开关逐项对应：
// PHP_PRETTY = PRETTY|UNESCAPED_SLASHES；PHP_COMPACT = 默认（转义斜杠与非 ASCII）；
// PHP_HEX_COMPACT = HEX_*|UNESCAPED_UNICODE。
$jsonOut = [
    'pretty' => json_encode($NASTY, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES),
    'compact' => json_encode($NASTY),
    'hexCompact' => json_encode($NASTY, JSON_HEX_TAG | JSON_HEX_APOS | JSON_HEX_AMP | JSON_HEX_QUOT | JSON_UNESCAPED_UNICODE),
];

dump([
    'provenance' => provenance('fixtures/php/oracle-types.php'),
    'typeCases' => $typeCasesOut,
    'summaryCases' => $summaryOut,
    // 输入以文本给出：Java 侧解析出同一份结构后再按三种形态重出
    'jsonInput' => json_encode($NASTY, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES),
    'jsonForms' => $jsonOut,
]);
