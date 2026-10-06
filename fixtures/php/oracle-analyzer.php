<?php

declare(strict_types=1);

/**
 * 参照器：分析器（rows / tier_counts / warnings 全口径 / unnamed 视图 / list --json）。
 *
 * 生成：php fixtures/php/oracle-analyzer.php > fixtures/php/expected/analyzer.json
 *
 * 每个用例把 analyze() 的产物与由它派生的四种渲染结果一起冻结，全部以 json_encode 文本保存：
 * 「空集合是 [] 还是 {}」在这条契约上是有意义的差异，反序列化成 PHP 数组再比对会被抹平。
 */

require __DIR__ . '/bootstrap.php';

use RouteForge\Common\Alias\AliasResolver;
use RouteForge\Common\Analyzer\RouteAnalyzer;
use RouteForge\Common\Contract\ForgeExceptionContract;
use RouteForge\Common\Contract\RouteNormalizerInterface;
use RouteForge\Common\Dto\RouteInfo;
use RouteForge\Common\Filter\RouteNameFilter;
use RouteForge\Common\Repository\RouteRepository;
use RouteForge\Common\Tier\TierResolver;

/** 恒等归一化：用于验证 analyzeRoutes() 与 analyze() 完全同果。 */
final class AnalyzerIdentityNormalizer implements RouteForge\Common\Contract\RouteNormalizerInterface
{
    public function normalize(mixed $route): RouteInfo
    {
        return $route;
    }
}

$CASES = [
    // 基础：多层级 + 未命中的命名路由 + 各种来源的未命名路由
    'mixed-named-and-unnamed' => [
        'levels' => [
            'admin' => ['match' => ['prefix' => ['admin']]],
            'client' => ['match' => ['middleware' => ['auth']]],
            'empty' => ['match' => ['prefix' => ['never-used-prefix']]],
        ],
        'infos' => [
            info('admin.users.index', 'admin/users'),
            info('client.orders.index', 'client/orders', ['middleware' => ['auth']]),
            info('loose.route', 'loose/route'),
            info(null, 'admin/unnamed'),
            info(null, 'other/unnamed'),
            info(null, 'client/unnamed', ['middleware' => ['auth']]),
        ],
    ],
    // 未命名走 classifier / 层级名无效 / classifier 抛错
    'unnamed-sources' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]], 'client' => []],
        'classifier' => ['return' => 'client'],
        'infos' => [
            info(null, 'x/one'),
            info('named.one', 'x/two'),
        ],
    ],
    'unnamed-explicit-tier' => [
        'levels' => ['admin' => []],
        'infos' => [info(null, 'x/one', ['tier' => 'admin'])],
    ],
    'unnamed-unknown-tier' => [
        'levels' => ['admin' => []],
        'infos' => [info(null, 'x/one', ['tier' => 'ghost'])],
    ],
    'unnamed-classifier-error' => [
        'levels' => ['admin' => []],
        'classifier' => ['throw' => 'boom'],
        'infos' => [info(null, 'x/one')],
    ],
    'unnamed-classifier-unknown-tier' => [
        'levels' => ['admin' => []],
        'classifier' => ['return' => 'ghost'],
        'infos' => [info(null, 'x/one')],
    ],
    // 同名跨层级重复注册 + 别名跟随每个层级
    'duplicate-name-across-tiers-with-alias' => [
        'levels' => [
            'admin' => ['match' => ['prefix' => ['admin']]],
            'manage' => ['match' => ['prefix' => ['manage']]],
        ],
        'aliases' => ['legacy.thing' => 'dup.thing'],
        'infos' => [
            info('dup.thing', 'admin/dup'),
            info('dup.thing', 'manage/dup'),
        ],
    ],
    // 别名：撞车 / 重复声明 / 宏与 config 冲突 / 无名路由上的声明
    'alias-warnings' => [
        'levels' => ['client' => ['match' => ['prefix' => ['client']]]],
        'aliases' => ['client.dup' => 'client.x', 'ghost.alias' => 'client.x'],
        'infos' => [
            info('client.x', 'client/x', ['forgeAliases' => ['client.dup']]),
            info('client.dup', 'client/dup'),
            info('client.y', 'client/y', ['forgeAliases' => ['client.dup']]),
            info(null, 'client/unnamed', ['forgeAliases' => ['orphan.alias']]),
        ],
    ],
    // 排除：包自身端点（名字前缀 + URI 前缀两个维度）
    'exclusions' => [
        'levels' => ['manage' => ['match' => ['middleware' => ['manage']]]],
        'uriPrefixes' => ['/_forge/routes'],
        'infos' => [
            info('forge.routes.manage', 'whatever/a'),
            info('forge.manager.api.routes', 'whatever/b'),
            info(null, '_forge/routes/manage', ['middleware' => ['manage']]),
            info('manage.reports', 'manage/reports', ['middleware' => ['manage']]),
        ],
    ],
    // 严格模式：RF_BE_001 被吞、行按 unassigned 落位、violations 全量
    'strict-mode-keeps-table-readable' => [
        'levels' => ['admin' => ['match' => ['prefix' => ['admin']]]],
        'strict' => true,
        'infos' => [
            info('admin.users', 'admin/users'),
            info('loose.route', 'loose/route'),
            info(null, 'admin/unnamed'),
        ],
    ],
    // 参数与默认值形态
    'parameters-and-defaults' => [
        'levels' => ['client' => ['match' => ['prefix' => ['client']]]],
        'infos' => [
            info('client.show', 'client/{id}', ['parameters' => ['id']]),
            info('client.list', 'client/posts/{page?}', ['parameters' => ['page'], 'parameterDefaults' => ['page' => '1']]),
            info('client.empty', 'client/empty', ['parameterDefaults' => []]),
        ],
    ],
];

function runAnalyzerCase(string $caseName, array $case): array
{
    $levels = $case['levels'];
    $resolver = new TierResolver(
        $levels,
        makeClassifier($case['classifier'] ?? null),
        (bool) ($case['strict'] ?? false),
        new CollectingLogger(),
    );
    $filter = new RouteNameFilter(RouteNameFilter::FORGE_PREFIXES, $case['uriPrefixes'] ?? []);
    $analyzer = new RouteAnalyzer($resolver, new AliasResolver($case['aliases'] ?? [], $filter), $filter);

    $enc = static fn (mixed $value): string => json_encode($value, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);

    $out = [
        'name' => $caseName,
        'input' => [
            'name' => $caseName,
            'levels' => $levels,
            'strict' => (bool) ($case['strict'] ?? false),
            'classifier' => $case['classifier'] ?? null,
            'aliases' => (object) ($case['aliases'] ?? []),
            'uriPrefixes' => $case['uriPrefixes'] ?? [],
            'infos' => array_map('infoAsArray', $case['infos']),
        ],
        'outputs' => [],
        'errors' => [],
    ];

    try {
        $result = $analyzer->analyze($case['infos']);
    } catch (ForgeExceptionContract $e) {
        $out['errors']['analyze'] = [
            'code' => $e->code(),
            'httpStatus' => $e->httpStatus(),
            'message' => $e->getMessage(),
        ];

        return $out;
    }

    // analyzeRoutes() 必须与 analyze() 同果（同一套优先级与文案，不各写一份）
    $viaNormalizer = $analyzer->analyzeRoutes($case['infos'], new AnalyzerIdentityNormalizer());
    $out['outputs']['analyzeRoutesSameAsAnalyze'] = $enc($viaNormalizer) === $enc($result);

    $out['outputs']['rows'] = $enc($result['rows']);
    $out['outputs']['tier_counts'] = $enc($result['tier_counts']);
    $out['outputs']['warnings'] = $enc($result['warnings']);
    $out['outputs']['aliases'] = $enc((object) $result['aliases']);
    $out['outputs']['collisions'] = $enc((object) $result['collisions']);
    $out['outputs']['unnamed'] = $enc($result['unnamed']);
    $out['outputs']['violations'] = $enc($result['violations']);
    $out['outputs']['violationCount'] = $enc(RouteForge\Common\Support\StrictViolationScanner::count($result['violations']));
    $out['outputs']['unnamedWarnings'] = $enc(RouteAnalyzer::unnamedWarnings($result['unnamed'], $levels));

    $levelNames = array_keys($levels);
    $filters = [null, ...$levelNames, RouteRepository::UNASSIGNED_LEVEL, 'unresolved', '不存在的层级'];
    foreach ($filters as $index => $levelFilter) {
        $out['outputs']["formatUnnamed#$index:" . ($levelFilter ?? 'all')]
            = $enc(RouteAnalyzer::formatUnnamed($result['unnamed'], $levels, $levelFilter));
    }

    $combos = [
        ['level' => null, 'unassigned' => false, 'aliases' => false],
        ['level' => $levelNames[0] ?? null, 'unassigned' => false, 'aliases' => false],
        ['level' => null, 'unassigned' => true, 'aliases' => false],
        ['level' => null, 'unassigned' => false, 'aliases' => true],
        ['level' => 'unassigned', 'unassigned' => true, 'aliases' => false],
    ];
    foreach ($combos as $index => $combo) {
        $rows = $analyzer->filterRows($result['rows'], $combo['level'], $combo['unassigned'], $combo['aliases']);
        $out['outputs']["listPayload#$index"] = $enc($analyzer->listPayload(
            $levelNames,
            $rows,
            $result['tier_counts'],
            $result['warnings'],
            $combo['level'],
            $combo['unassigned'],
            $combo['aliases'],
        ));
    }

    return $out;
}

$casesOut = [];
foreach ($CASES as $name => $case) {
    $casesOut[] = runAnalyzerCase($name, $case);
}

dump([
    'provenance' => provenance('fixtures/php/oracle-analyzer.php'),
    'cases' => $casesOut,
]);
