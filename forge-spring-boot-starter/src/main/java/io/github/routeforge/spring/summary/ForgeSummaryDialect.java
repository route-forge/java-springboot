package io.github.routeforge.spring.summary;

import java.util.Set;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.dialect.AbstractDialect;
import org.thymeleaf.dialect.IExpressionObjectDialect;
import org.thymeleaf.expression.IExpressionObjectFactory;

/**
 * 内嵌摘要的<b>可选</b> Thymeleaf 方言（SPEC §5.4 决策「纯 Java API + 可选方言片段」）。
 *
 * <p>注册后暴露表达式对象 {@code #forgeSummary}，其 {@code summary} 属性给出与
 * {@link ForgeSummaryEmbed#script()} 同源的原始 {@code <script>} 串；宿主须用<b>{@code th:utext}</b>
 * （unescaped）输出——该串已按前端契约安全编码，再经 {@code th:text} 转义会把 {@code <} 变 {@code &lt;}
 * 令前端解析失败（{@link io.github.routeforge.core.summary.SummaryRenderer} 红线）。
 *
 * <pre>{@code
 * <head>
 *   <div th:utext="${#forgeSummary.summary}"></div>
 * </head>
 * }</pre>
 *
 * <p>只用核心 {@code thymeleaf} 的 dialect SPI（{@code compileOnly}），不依赖 thymeleaf-spring；Boot 会把
 * classpath 上任意 {@code IDialect} bean 自动注册进宿主 TemplateEngine。没有 Thymeleaf 的宿主根本不会加载本类
 * （见 {@code ForgeAutoConfiguration.ThymeleafSummaryConfiguration} 的 {@code @ConditionalOnClass}）。
 */
public class ForgeSummaryDialect extends AbstractDialect implements IExpressionObjectDialect {

    private static final long serialVersionUID = 1L;

    /** 方言名 / 表达式对象名（模板里 {@code #forgeSummary.summary}）。 */
    public static final String NAME = "forgeSummary";

    private final transient ForgeSummaryEmbed embed;

    public ForgeSummaryDialect(ForgeSummaryEmbed embed) {
        super(NAME);
        this.embed = embed;
    }

    @Override
    public IExpressionObjectFactory getExpressionObjectFactory() {
        return new SummaryExpressionObjectFactory(embed);
    }

    private static final class SummaryExpressionObjectFactory implements IExpressionObjectFactory {

        private final ForgeSummaryEmbed embed;

        SummaryExpressionObjectFactory(ForgeSummaryEmbed embed) {
            this.embed = embed;
        }

        @Override
        public Set<String> getAllExpressionObjectNames() {
            return Set.of(NAME);
        }

        @Override
        public Object buildObject(IExpressionContext context, String expressionObjectName) {
            return NAME.equals(expressionObjectName) ? new ForgeSummaryExpression(embed) : null;
        }

        @Override
        public boolean isCacheable(String expressionObjectName) {
            // 不缓存表达式对象：每次求值都现取，getSummary() 内部仍复用摘要缓存，保证热更/失效即时可见
            return false;
        }
    }

    /**
     * {@code #forgeSummary} 表达式对象。OGNL/SpringEL 以 bean 属性方式访问 {@code .summary} → {@link #getSummary()}。
     * 只挂一个属性，命名与模板 {@code #forgeSummary.summary} 对齐。
     */
    public static final class ForgeSummaryExpression {

        private final ForgeSummaryEmbed embed;

        ForgeSummaryExpression(ForgeSummaryEmbed embed) {
            this.embed = embed;
        }

        /** 原始内嵌摘要 {@code <script>}（未二次转义，宿主须以 unescaped 通道输出）。 */
        public String getSummary() {
            return embed.script();
        }
    }
}
