package dev.trakr.api.shared.security;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wraps the auto-configured Hikari pool in {@link TenantDataSource}. A BeanPostProcessor, not a second
 * DataSource bean: Boot keeps configuring the pool from spring.datasource.* (and its metrics and health still
 * find Hikari behind the wrapper), and everything that injects the DataSource — JPA, jOOQ, JdbcClient, the
 * Modulith outbox — gets the wrapper without knowing.
 */
@Configuration(proxyBeanMethods = false)
class TenantDataSourceConfig {

    // static: a BeanPostProcessor is created before ordinary beans, so it must not need this config instance
    @Bean
    static BeanPostProcessor tenantDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof TenantDataSource)
                        && "dataSource".equals(beanName)) {
                    return new TenantDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    // first of all filters: everything after it (Spring Security, the controllers) knows it serves a request
    @Bean
    FilterRegistrationBean<TenantDataSource.RequestMarker> tenantRequestMarker() {
        FilterRegistrationBean<TenantDataSource.RequestMarker> registration =
                new FilterRegistrationBean<>(new TenantDataSource.RequestMarker());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
