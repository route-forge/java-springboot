package io.github.routeforge.spring.scan;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.annotation.security.DenyAll;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.method.HandlerMethod;

/**
 * 守卫注解 → {@code middleware} 标签的派生规则表，逐行对着 SPEC §4.4 的表断言。
 *
 * <p>注解一律用 Spring Security / JSR-250 的<b>真身</b>（{@code testImplementation} 引进来），不是自造同名替身：
 * 派生器按注解类型全名匹配，替身会把「名字写错」这类缺陷直接测成绿灯。
 */
class GuardLabelsTest {

    /** 一条规则一个方法，方法名即规则，方便失败时直接定位到 SPEC 的表行。 */
    static class Predicates {

        @PreAuthorize("hasRole('ADMIN')")
        void role() {
        }

        @PreAuthorize("hasAuthority('system:user:list')")
        void authority() {
        }

        @PreAuthorize("hasRole('A') and hasAuthority('p:read')")
        void conjunction() {
        }

        @PreAuthorize("hasRole('A') && hasAuthority('p:read')")
        void conjunctionSymbol() {
        }

        @PreAuthorize("hasAnyRole('A','B')")
        void anyRole() {
        }

        @PreAuthorize("hasRole('A') or hasRole('B')")
        void disjunction() {
        }

        @PreAuthorize("hasRole('A') || hasRole('B')")
        void disjunctionSymbol() {
        }

        @PreAuthorize("hasRole('A') and #id == authentication.name")
        void mixedWithParameter() {
        }

        @PreAuthorize("@ss.hasPermi('system:user:list')")
        void customBeanExpression() {
        }

        @PreAuthorize("isAuthenticated()")
        void authenticated() {
        }

        @PreAuthorize("permitAll()")
        void permitAllPredicate() {
        }

        @PreAuthorize("denyAll()")
        void denyAllPredicate() {
        }

        @PreAuthorize("hasRole( 'SPACED' )")
        void whitespaceInsideParens() {
        }

        @PreAuthorize("hasAuthority('a and b')")
        void separatorInsideQuotes() {
        }

        @PreAuthorize("hasRole('ADMIN')")
        @RolesAllowed("AUDIT")
        void twoDifferentGuards() {
        }

        @PreAuthorize("hasAuthority('')")
        void emptyAuthority() {
        }

        @PreAuthorize("hasRole(\"DOUBLE\")")
        void doubleQuoted() {
        }

        @PreAuthorize("hasRole('ROLE_ADMIN')")
        void roleAlreadyPrefixed() {
        }

        @Secured("ROLE_VET")
        void securedSingle() {
        }

        @Secured({"A", "B"})
        void securedMulti() {
        }

        @RolesAllowed("AUDIT")
        void rolesAllowedSingle() {
        }

        @PermitAll
        void permitAllMarker() {
        }

        @DenyAll
        void denyAllMarker() {
        }

        void noGuardAtAll() {
        }
    }

    /** 类级守卫：整个 handler 群都归到同一标签下，这是「注解派生」最常见的收益来源。 */
    @PreAuthorize("hasRole('USER')")
    static class ClassGuarded {

        void inherited() {
        }

        @PreAuthorize("hasRole('ADMIN')")
        void overridden() {
        }
    }

    /** 宿主自建的组合注解——meta-annotate 必须照样读出来，否则企业里的 {@code @AdminOnly} 全落空。 */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.METHOD, ElementType.TYPE})
    @PreAuthorize("hasRole('SUPER')")
    @interface AdminOnly {
    }

    static class ComposedAnnotation {

        @AdminOnly
        void viaMetaAnnotation() {
        }
    }

    /** 注解在接口方法上（Spring Security 会看接口），扫描侧也得看得到。 */
    interface GuardedApi {

        @PreAuthorize("hasRole('API')")
        void handle();
    }

    static class ImplementingController implements GuardedApi {

        @Override
        public void handle() {
        }
    }

    /** 守卫标在抽象基类<b>类型</b>上、handler 方法继承自它——controller 抽基类的常见写法。 */
    @PreAuthorize("hasRole('BASE')")
    static abstract class AbstractGuarded {

        void handle() {
        }
    }

    static class ConcreteGuarded extends AbstractGuarded {
    }

    @Test
    @DisplayName("hasRole / hasAuthority → 注解里的字面角色名与权限码")
    void simplePredicatesBecomeLabels() {
        assertThat(labelsOf("role")).containsExactly("ADMIN");
        assertThat(labelsOf("authority")).containsExactly("system:user:list");
        assertThat(labelsOf("doubleQuoted")).containsExactly("DOUBLE");
        assertThat(labelsOf("whitespaceInsideParens")).containsExactly("SPACED");
    }

    @Test
    @DisplayName("不加工前缀：hasRole('ROLE_ADMIN') 原样出 ROLE_ADMIN，不与 hasRole('ADMIN') 归一")
    void prefixesAreNeverRewritten() {
        assertThat(labelsOf("roleAlreadyPrefixed")).containsExactly("ROLE_ADMIN");
        assertThat(labelsOf("securedSingle")).containsExactly("ROLE_VET");
    }

    @Test
    @DisplayName("顶层 and 是合取（都要求），拆成标签并集；&& 与 and 同义")
    void conjunctionSplitsIntoMultipleLabels() {
        assertThat(labelsOf("conjunction")).containsExactly("A", "p:read");
        assertThat(labelsOf("conjunctionSymbol")).containsExactly("A", "p:read");
    }

    @Test
    @DisplayName("或语义一律不拆——拆了会把归类放宽到不属于它的人，属于读错")
    void disjunctionFallsBackToRawExpression() {
        assertThat(labelsOf("anyRole"))
                .containsExactly(GuardLabels.EXPRESSION_PREFIX + "hasAnyRole('A','B')");
        assertThat(labelsOf("disjunction"))
                .containsExactly(GuardLabels.EXPRESSION_PREFIX + "hasRole('A') or hasRole('B')");
        assertThat(labelsOf("disjunctionSymbol"))
                .containsExactly(GuardLabels.EXPRESSION_PREFIX + "hasRole('A') || hasRole('B')");
    }

    @Test
    @DisplayName("合取里混入认不出的项 → 整条原样，不做「能认多少算多少」的部分派生")
    void partiallyUnderstoodConjunctionFallsBackWhole() {
        assertThat(labelsOf("mixedWithParameter"))
                .containsExactly(GuardLabels.EXPRESSION_PREFIX + "hasRole('A') and #id == authentication.name");
    }

    @Test
    @DisplayName("若依式的 @bean.method('码') 不在白名单内，整条原样下发")
    void customBeanExpressionsAreKeptVerbatim() {
        assertThat(labelsOf("customBeanExpression"))
                .containsExactly(GuardLabels.EXPRESSION_PREFIX + "@ss.hasPermi('system:user:list')");
    }

    @Test
    @DisplayName("只要求登录 / 放行 / 拒绝 → 保留字面量标签")
    void statelessPredicatesUseReservedLabels() {
        assertThat(labelsOf("authenticated")).containsExactly(GuardLabels.AUTHENTICATED);
        assertThat(labelsOf("permitAllPredicate")).containsExactly(GuardLabels.PERMIT_ALL);
        assertThat(labelsOf("denyAllPredicate")).containsExactly(GuardLabels.DENY_ALL);
        assertThat(labelsOf("permitAllMarker")).containsExactly(GuardLabels.PERMIT_ALL);
        assertThat(labelsOf("denyAllMarker")).containsExactly(GuardLabels.DENY_ALL);
    }

    @Test
    @DisplayName("引号里的 and 是一个权限名，不是两个合取项")
    void separatorsInsideQuotesAreNotBoundaries() {
        assertThat(labelsOf("separatorInsideQuotes")).containsExactly("a and b");
    }

    @Test
    @DisplayName("空名字（hasAuthority('')）没有归类价值 → 整条原样，不产出空标签")
    void emptyLiteralFallsBack() {
        assertThat(labelsOf("emptyAuthority"))
                .containsExactly(GuardLabels.EXPRESSION_PREFIX + "hasAuthority('')");
    }

    @Test
    @DisplayName("@Secured 多值不猜它是或还是与（未实测），原样兜底")
    void multiValueSecuredFallsBack() {
        assertThat(labelsOf("securedMulti")).containsExactly(GuardLabels.EXPRESSION_PREFIX + "@Secured({A, B})");
        assertThat(labelsOf("rolesAllowedSingle")).containsExactly("AUDIT");
    }

    @Test
    @DisplayName("同一 handler 上多种守卫注解时取并集：每种注解各自有独立拦截器，运行期全部通过才放行")
    void differentGuardAnnotationsUnionTheirLabels() {
        assertThat(labelsOf("twoDifferentGuards")).containsExactly("ADMIN", "AUDIT");
    }

    @Test
    @DisplayName("没有任何守卫注解时一个标签都不产出（派生通道不得给老宿主凭空加字段）")
    void noAnnotationsNoLabels() {
        assertThat(labelsOf("noGuardAtAll")).isEmpty();
    }

    /** 方法声明在基类、守卫标在具体的 bean 类型上——只看 handler 方法元素的类层级会漏掉这一类。 */
    static abstract class BareHandler {

        void handle() {
        }
    }

    @PreAuthorize("hasRole('CONCRETE')")
    static class GuardedConcreteHandler extends BareHandler {
    }

    @Test
    @DisplayName("类级守卫继承到方法，方法级就近覆盖——与 Spring Security 的解析口径一致")
    void classLevelIsInheritedAndMethodLevelOverrides() {
        assertThat(derivedFrom(new ClassGuarded(), "inherited")).containsExactly("USER");
        assertThat(derivedFrom(new ClassGuarded(), "overridden")).containsExactly("ADMIN");
    }

    @Test
    @DisplayName("守卫标在抽象基类或具体 bean 类型上都要读到（controller 抽基类的常见形态）")
    void superclassAndBeanTypeAnnotationsCoverInheritedHandlers() {
        assertThat(derivedFrom(new ConcreteGuarded(), "handle")).containsExactly("BASE");
        assertThat(derivedFrom(new GuardedConcreteHandler(), "handle")).containsExactly("CONCRETE");
    }

    @Test
    @DisplayName("组合注解（meta-annotate @PreAuthorize）照样读出，企业里的 @AdminOnly 不落空")
    void metaAnnotatedComposeIsFollowed() {
        assertThat(derivedFrom(new ComposedAnnotation(), "viaMetaAnnotation")).containsExactly("SUPER");
    }

    @Test
    @DisplayName("接口方法上的守卫也算（对齐 Spring Security 会看接口这一点）")
    void interfaceMethodAnnotationIsFound() {
        assertThat(derivedFrom(new ImplementingController(), "handle")).containsExactly("API");
    }

    @Test
    @DisplayName("实测钉住：方法元素自己读不到声明类上的注解，类级守卫全靠 derive 的 bean 类型那一轮")
    void methodElementAloneDoesNotSeeDeclaringClassAnnotations() {
        Method inherited = methodOf(ClassGuarded.class, "inherited");

        assertThat(GuardLabels.labelsOf(inherited)).isEmpty();
        assertThat(derivedFrom(new ClassGuarded(), "inherited")).containsExactly("USER");
    }

    private static List<String> labelsOf(String methodName) {
        return GuardLabels.labelsOf(methodOf(Predicates.class, methodName));
    }

    private static List<String> derivedFrom(Object handlerBean, String methodName) {
        return GuardLabels.derive(new HandlerMethod(handlerBean, methodOf(handlerBean.getClass(), methodName)));
    }

    /** 按类查找、一路向上到父类：handler 方法常常声明在抽象基类里。 */
    private static Method methodOf(Class<?> fixture, String methodName) {
        Method method = ReflectionUtils.findMethod(fixture, methodName);
        assertThat(method).as("夹具缺方法 " + fixture.getSimpleName() + "#" + methodName).isNotNull();
        return method;
    }
}
