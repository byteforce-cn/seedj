package cn.byteforce.seedj.core.security;

import static cn.byteforce.seedj.core.security.ScopeKeyType.DATE;
import static cn.byteforce.seedj.core.security.ScopeKeyType.LIST_STRING;
import static cn.byteforce.seedj.core.security.ScopeKeyType.LIST_UUID;
import static cn.byteforce.seedj.core.security.ScopeKeyType.STRING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScopeContextTest {

    private static final ScopeKey<List<String>> ORG_UNITS =
            new ScopeKey<>("org.units", LIST_STRING);

    private static final ScopeKey<String> DEPARTMENT = new ScopeKey<>("subject.department", STRING);

    private static Caller caller() {
        return new Caller("u-1", "alice", "default", null, null, null, null, null, null);
    }

    @Test
    void duplicateKeyFails() {
        var resolver =
                new DefaultContextResolver(
                        List.of(
                                (sink, caller) -> sink.put(ORG_UNITS, List.of("D01")),
                                (sink, caller) -> sink.put(ORG_UNITS, List.of("D02"))));

        assertThatThrownBy(() -> resolver.resolve(caller()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.units");
    }

    @Test
    void writersRunInOrder() {
        List<String> invocation = new ArrayList<>();
        ContextWriter late = writer(20, "late", sink -> sink.put(DEPARTMENT, "D02"), invocation);
        ContextWriter early =
                writer(10, "early", sink -> sink.put(ORG_UNITS, List.of("D01")), invocation);

        ScopeContext context = new DefaultContextResolver(List.of(late, early)).resolve(caller());

        assertThat(invocation).containsExactly("early", "late");
        assertThat(context.facts().keySet()).containsExactly("org.units", "subject.department");
        assertThat(context.get(ORG_UNITS)).contains(List.of("D01"));
        assertThat(context.get(DEPARTMENT)).contains("D02");
    }

    @Test
    void writerFailurePropagates() {
        RuntimeException boom = new RuntimeException("boom");
        var resolver =
                new DefaultContextResolver(
                        List.of(
                                (sink, caller) -> {
                                    throw boom;
                                }));

        assertThatThrownBy(() -> resolver.resolve(caller())).isSameAs(boom);
        assertThat(ScopeContextHolder.current()).isEmpty();
    }

    @Test
    void holderRestoresPreviousOnClose() {
        ScopeContext first = ScopeContext.of(Map.of("subject.department", "D01"));
        ScopeContext second = ScopeContext.of(Map.of("subject.department", "D02"));

        try (ScopeContextHolder.Scope ignored = ScopeContextHolder.open(first)) {
            assertThat(ScopeContextHolder.current()).contains(first);
            try (ScopeContextHolder.Scope nested = ScopeContextHolder.open(second)) {
                assertThat(ScopeContextHolder.current()).contains(second);
            }
            assertThat(ScopeContextHolder.current()).contains(first);
        }

        assertThat(ScopeContextHolder.current()).isEmpty();
    }

    @Test
    void holderCloseIsIdempotent() {
        ScopeContextHolder.Scope scope =
                ScopeContextHolder.open(ScopeContext.of(Map.of("subject.department", "D01")));

        scope.close();
        scope.close();

        assertThat(ScopeContextHolder.current()).isEmpty();
    }

    @Test
    void typedGetRejectsWrongType() {
        ScopeContext context =
                ScopeContext.of(
                        Map.of(
                                "org.units", List.of("D01"),
                                "subject.department", "D01",
                                "as.of", LocalDate.parse("2026-11-06")));

        assertThatThrownBy(() -> context.get(new ScopeKey<>("org.units", LIST_UUID)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("org.units");
        assertThatThrownBy(() -> context.get(new ScopeKey<>("subject.department", LIST_STRING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subject.department");
        assertThatThrownBy(() -> context.get(new ScopeKey<>("as.of", STRING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("as.of");
    }

    @Test
    void typedGetReturnsOnlyValidatedValues() {
        UUID unit = UUID.randomUUID();
        ScopeContext context =
                ScopeContext.of(
                        Map.of(
                                "org.units", List.of("D01"),
                                "org.ids", List.of(unit),
                                "as.of", LocalDate.parse("2026-11-06")));

        assertThat(context.get(ORG_UNITS)).contains(List.of("D01"));
        assertThat(context.get(new ScopeKey<>("org.ids", LIST_UUID))).contains(List.of(unit));
        assertThat(context.get(new ScopeKey<>("as.of", DATE)))
                .contains(LocalDate.parse("2026-11-06"));
        assertThat(context.get("missing")).isEmpty();
    }

    @Test
    void unknownKeyIsRejected() {
        ScopeKeyCatalog catalog = ScopeKeyCatalog.of(List.of(ORG_UNITS));

        assertThatThrownBy(() -> catalog.require("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing");
        assertThatThrownBy(() -> catalog.require(new ScopeKey<>("org.units", STRING)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("org.units");
        assertThat(catalog.require("org.units")).isEqualTo(ORG_UNITS);
        assertThat(catalog.keys()).containsEntry("org.units", LIST_STRING);
    }

    @Test
    void duplicateCatalogKeyWithDifferentTypeFails() {
        assertThatThrownBy(
                        () ->
                                ScopeKeyCatalog.of(
                                        List.of(ORG_UNITS, new ScopeKey<>("org.units", STRING))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("org.units");
        assertThat(
                        ScopeKeyCatalog.of(
                                        List.of(
                                                ORG_UNITS,
                                                new ScopeKey<>("org.units", LIST_STRING)))
                                .keys())
                .hasSize(1);
    }

    @Test
    void nullAndIllegalValuesAreRejected() {
        assertThatThrownBy(() -> ScopeContext.of(Map.of("org.units", "not-a-list")).get(ORG_UNITS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                ScopeContext.of(
                                        java.util.Collections.singletonMap("org.units", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScopeKey<>("Org Units", STRING))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ScopeKeyCatalog.of(List.of()).keys()).isEmpty();
    }

    private static ContextWriter writer(
            int order,
            String name,
            java.util.function.Consumer<ContextSink> action,
            List<String> invocation) {
        return new ContextWriter() {
            @Override
            public int order() {
                return order;
            }

            @Override
            public void write(ContextSink sink, Caller caller) {
                invocation.add(name);
                action.accept(sink);
            }
        };
    }
}
