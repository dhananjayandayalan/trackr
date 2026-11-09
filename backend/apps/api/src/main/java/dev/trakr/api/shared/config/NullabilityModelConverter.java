package dev.trakr.api.shared.config;

import com.fasterxml.jackson.databind.JavaType;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The nullability rule of 03-api §5, applied to every record in the OpenAPI document: a component
 * without JSpecify {@code @Nullable} is <b>required</b>, one with it is <b>nullable</b>
 * ({@code type: [string, "null"]}, or {@code oneOf: [$ref, null]} for an object). Without this, springdoc
 * marks nothing required and the generated TypeScript makes every field optional — {@code me.id} would be
 * {@code string | undefined} everywhere.
 *
 * <p>springdoc registers ModelConverter beans with swagger-core; each converter sees the schema the next
 * one in the chain produced and may adjust it.
 */
@Component
class NullabilityModelConverter implements ModelConverter {

    @Override
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public @Nullable Schema resolve(AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
        if (!chain.hasNext()) {
            return null;
        }
        Schema resolved = chain.next().resolve(type, context, chain);
        JavaType javaType = Json.mapper().constructType(type.getType());
        Class<?> raw = javaType == null ? null : javaType.getRawClass();
        if (resolved == null || raw == null || !raw.isRecord()) {
            return resolved;
        }
        // a model is usually handed back as a $ref; the definition with the properties sits in the context
        Schema model = resolved;
        if (resolved.get$ref() != null) {
            String name = resolved.get$ref().substring(resolved.get$ref().lastIndexOf('/') + 1);
            model = context.getDefinedModels().get(name);
        }
        if (model == null || model.getProperties() == null) {
            return resolved;
        }
        Map<String, Schema> properties = model.getProperties();
        List<String> required = new ArrayList<>(model.getRequired() == null ? List.of() : model.getRequired());
        for (RecordComponent component : raw.getRecordComponents()) {
            String name = component.getName();
            Schema property = properties.get(name);
            if (property == null) {
                continue;
            }
            // JSpecify's @Nullable is a type-use annotation: it sits on the component's type, not the component
            if (component.getAnnotatedType().isAnnotationPresent(Nullable.class)) {
                properties.put(name, nullable(property));
            } else if (!required.contains(name)) {
                required.add(name);
            }
        }
        model.setRequired(required.isEmpty() ? null : required);
        return resolved;
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Schema nullable(Schema property) {
        if (property.get$ref() != null) {
            // a $ref can't carry other keywords in a way every tool reads: wrap it
            return new Schema().oneOf(List.of(property, new Schema().types(Set.of("null"))));
        }
        Set<String> types = new LinkedHashSet<>();
        if (property.getTypes() != null) {
            types.addAll(property.getTypes());
        } else if (property.getType() != null) {
            types.add(property.getType());
        }
        if (!types.isEmpty()) {
            types.add("null");
            property.setTypes(types);
        }
        return property;
    }
}
