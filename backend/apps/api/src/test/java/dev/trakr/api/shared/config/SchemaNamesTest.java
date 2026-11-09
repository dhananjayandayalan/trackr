package dev.trakr.api.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.ClassUtils;

/**
 * springdoc names a schema after the class's simple name, and when two classes share one (BoardView.Column
 * and ProjectDetails.Column) it keeps one of them without a word: the other endpoint's TypeScript type is
 * then silently wrong. Every record in the api must therefore have a schema name of its own; a nested
 * record with a common name gets one with {@code @Schema(name = …)}.
 */
class SchemaNamesTest {

    @Test
    void every_record_has_its_own_schema_name() throws ClassNotFoundException {
        // BLOCK 1: every record under dev.trakr.api; nested ones count too (they are static, so "independent")
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return true;
            }
        };
        TypeFilter records = (reader, factory) -> "java.lang.Record".equals(reader.getClassMetadata().getSuperClassName());
        scanner.addIncludeFilter(records);

        Map<String, List<String>> byName = new TreeMap<>();
        for (var definition : scanner.findCandidateComponents("dev.trakr.api")) {
            Class<?> type = ClassUtils.forName(definition.getBeanClassName(), getClass().getClassLoader());
            // what can reach the api: records outside internal packages, and internal ones a module returns
            // anyway (they carry @Schema). JPA composite keys (ProjectMember.Key ...) are Serializable and never do.
            boolean internal = type.getPackageName().contains(".internal") && !type.isAnnotationPresent(Schema.class);
            if (internal || Serializable.class.isAssignableFrom(type)) {
                continue;
            }
            byName.computeIfAbsent(schemaName(type), name -> new java.util.ArrayList<>()).add(type.getName());
        }

        // BLOCK 2: no name may stand for two classes
        Map<String, List<String>> clashes = byName.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        assertThat(byName).as("the scan found the api's records").containsKey("BoardColumn");
        assertThat(clashes).as("schema names used by more than one record; add @Schema(name = ...)").isEmpty();
    }

    // what swagger-core's TypeNameResolver does: @Schema(name) if present, else the simple name
    private static String schemaName(Class<?> type) {
        Schema schema = type.getAnnotation(Schema.class);
        return schema != null && !schema.name().isBlank() ? schema.name() : type.getSimpleName();
    }
}
