package dev.trakr.api.project.web;

import dev.trakr.api.project.Template;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/**
 * {@code ?template=scrum} → Template.SCRUM. Spring's default enum conversion calls valueOf("scrum")
 * and fails; Spring Boot registers every Converter bean with Spring MVC, so this one is used instead.
 * A bad value becomes a 400, not a 500.
 */
@Component
class TemplateParamConverter implements Converter<String, Template> {

    @Override
    public Template convert(String source) {
        return Template.fromValue(source);
    }
}
