package com.mymarz.docs;

import com.mymarz.annotation.Marz;
import com.mymarz.core.ConfigSourceResolver;
import com.mymarz.core.MarzBeanPostProcessor;
import com.mymarz.core.MarzRegistry;
import com.mymarz.core.SelfRegistrar;
import com.mymarz.core.SourceStrategyResolver;
import com.mymarz.source.ConfigFormatParser;
import com.mymarz.type.TypeCoercer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * KAN-97: the documented quickstart used to crash at startup because its {@code @Marz}
 * fields were declared without {@code volatile} (which the library — correctly —
 * rejects). This test pins the documentation against the real code two ways:
 *
 * <ol>
 *   <li>A compiled copy of the README quickstart bean is run through the actual
 *       {@link MarzBeanPostProcessor}; if it ever lost {@code volatile} it would throw.</li>
 *   <li>A lint over {@code README.md} fails if any {@code @Marz} example field is not
 *       declared {@code volatile}.</li>
 * </ol>
 */
class ReadmeQuickstartSnippetTest {

    /**
     * Verbatim copy of the README "Quick Start" service. If the docs drift back to a
     * non-volatile field, this fixture would no longer compile-and-pass the BPP gate.
     */
    static class CheckoutService {

        @Marz(key = "feature.new-checkout.enabled", source = "file://config/marz.yml")
        private volatile boolean newCheckoutEnabled = false;

        @Marz(key = "rate.limit.max-requests", source = "file://config/marz.yml")
        private volatile int maxRequests = 100;

        public boolean isNewCheckoutEnabled() {
            return newCheckoutEnabled;
        }

        public int getMaxRequests() {
            return maxRequests;
        }
    }

    @Test
    @DisplayName("the README quickstart bean boots through the BeanPostProcessor without crashing")
    void quickstartBean_registersWithoutCrashing() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        TypeCoercer coercer = new TypeCoercer();
        MarzRegistry registry = new MarzRegistry(publisher, coercer);
        ConfigSourceResolver resolver = new ConfigSourceResolver(
                new ConfigFormatParser(), registry, new SourceStrategyResolver(), null, 5000L);
        MarzBeanPostProcessor bpp = new MarzBeanPostProcessor(registry, coercer, resolver, new SelfRegistrar());

        CheckoutService bean = new CheckoutService();

        // The original bug: this threw IllegalStateException("MUST be declared volatile").
        bpp.postProcessAfterInitialization(bean, "checkoutService");

        assertThat(registry.getRegisteredKeyCount()).isEqualTo(2);
        assertThat(bean.isNewCheckoutEnabled()).isFalse(); // field initializer retained, no crash
        assertThat(bean.getMaxRequests()).isEqualTo(100);
    }

    @Test
    @DisplayName("every @Marz example in README.md declares volatile")
    void readmeMarzExamples_areVolatile() throws IOException {
        Path readme = locateReadme();
        List<String> offenders = scanForNonVolatileMarzFields(Files.readAllLines(readme));

        assertThat(offenders)
                .as("README.md @Marz examples missing 'volatile' (would crash a user's app at startup)")
                .isEmpty();
    }

    // ── Self-tests for the lint itself, so the guard cannot silently miss a violation ──

    @Test
    @DisplayName("lint flags a package-private (no access modifier) non-volatile @Marz field")
    void lint_flagsPackagePrivateNonVolatile() {
        List<String> doc = List.of(
                "```java",
                "@Marz(key = \"x\", source = \"file://c.yml\")",
                "boolean flag = false;", // package-private, NOT volatile — runtime would crash on this
                "```");
        assertThat(scanForNonVolatileMarzFields(doc)).isNotEmpty();
    }

    @Test
    @DisplayName("lint passes a package-private volatile @Marz field")
    void lint_passesPackagePrivateVolatile() {
        List<String> doc = List.of(
                "```java",
                "@Marz(key = \"x\", source = \"file://c.yml\")",
                "volatile boolean flag = false;",
                "```");
        assertThat(scanForNonVolatileMarzFields(doc)).isEmpty();
    }

    @Test
    @DisplayName("lint catches a non-volatile field after a long multi-line @Marz annotation (no window blind spot)")
    void lint_handlesMultiLineAnnotation() {
        List<String> doc = List.of(
                "```java",
                "@Marz(",
                "    key = \"a\",", "    source = \"b\",", "    safetyNetInterval = 60000,",
                "    defaultValue = \"false\",", "    type = MarzType.INFERRED,",
                "    description = \"d\",", "    requiresApproval = false,",
                "    sensitive = false,", "    // a comment to push the field even further down",
                "    description = \"d2\"",
                ")",
                "private boolean darkModeEnabled;", // > 12 lines below @Marz(, NOT volatile
                "```");
        assertThat(scanForNonVolatileMarzFields(doc))
                .as("a multi-line annotation must not let a non-volatile field escape the lint")
                .isNotEmpty();
    }

    /**
     * Scan markdown for {@code @Marz} examples and return any whose annotated field is
     * not declared {@code volatile}. Robust to: package-private (no access modifier)
     * fields — the runtime requires {@code volatile}, not {@code private}; and
     * arbitrarily long multi-line annotations — the annotation is skipped by tracking
     * parenthesis depth, with no fixed line window. Only fenced {@code ```java} blocks
     * are considered.
     */
    static List<String> scanForNonVolatileMarzFields(List<String> lines) {
        List<String> offenders = new ArrayList<>();
        boolean inJava = false;

        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("```")) {
                inJava = trimmed.startsWith("```java");
                continue;
            }
            if (!inJava || !trimmed.contains("@Marz(")) {
                continue;
            }

            // Skip past the (possibly multi-line) @Marz(...) annotation via paren depth.
            int depth = 0;
            int j = i;
            for (; j < lines.size(); j++) {
                for (char c : lines.get(j).toCharArray()) {
                    if (c == '(') depth++;
                    else if (c == ')') depth--;
                }
                if (depth <= 0) break; // annotation closed on line j
            }

            // The annotated field is the next real declaration line (any access modifier,
            // including none). Skip blanks, comments, and stacked annotations.
            for (int k = j + 1; k < lines.size(); k++) {
                String decl = lines.get(k).trim();
                if (decl.startsWith("```")) break;                         // left the block w/o a field
                if (decl.isEmpty() || decl.startsWith("//") || decl.startsWith("*") || decl.startsWith("@")) {
                    continue;
                }
                if (decl.contains("(")) break;                             // a method, not a field — ignore
                if (!decl.contains("volatile")) {
                    offenders.add("line " + (k + 1) + ": " + decl);
                }
                break;
            }
        }
        return offenders;
    }

    /**
     * Locate {@code README.md} deterministically and FAIL (never silently skip) if it
     * cannot be found — a guard that self-disables is worse than no guard.
     */
    private static Path locateReadme() {
        Path direct = Path.of("README.md");
        if (Files.exists(direct)) {
            return direct;
        }
        try {
            Path p = Path.of(ReadmeQuickstartSnippetTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            for (int up = 0; up < 6 && p != null; up++) {
                Path candidate = p.resolve("README.md");
                if (Files.exists(candidate)) {
                    return candidate;
                }
                p = p.getParent();
            }
        } catch (Exception ignored) {
            // fall through to the hard failure below
        }
        throw new AssertionError("README.md could not be located from CWD ("
                + Path.of(".").toAbsolutePath() + ") or the test class code source. "
                + "The volatile lint must hard-fail rather than silently skip.");
    }
}
