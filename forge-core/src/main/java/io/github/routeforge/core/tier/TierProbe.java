package io.github.routeforge.core.tier;

/**
 * 只读层级探测结果（{@link TierResolver#probe}）。
 *
 * @param level     探测到的层级；未归级或层级名无效时为 {@code null}
 * @param source    归级来源，供告警文案指错
 * @param requested 层级名校验失败时的原始值（显式 tier 或 classifier 返回值），否则 {@code null}
 * @param error     classifier 抛错时的原始消息，否则 {@code null}
 */
public record TierProbe(String level, Source source, String requested, String error) {

    public boolean matched() {
        return level != null;
    }

    /**
     * 来源标记。
     *
     * <p>wire 名与 PHP 侧字符串逐字一致——它会出现在 {@code --unnamed} 视图、warnings 文案与
     * {@code RF_BE_009} 的结构化 violations 里，是跨语言对等的一部分，不可本地化。
     */
    public enum Source {
        EXPLICIT("explicit"),
        EXPLICIT_UNKNOWN_LEVEL("explicit-unknown-level"),
        CLASSIFIER("classifier"),
        CLASSIFIER_UNKNOWN_TIER("classifier-unknown-tier"),
        CLASSIFIER_ERROR("classifier-error"),
        MATCH("match"),
        NONE("none");

        private final String wireName;

        Source(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }
}
