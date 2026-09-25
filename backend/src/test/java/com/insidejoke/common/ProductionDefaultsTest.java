package com.insidejoke.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** Secrets have no defaults outside the local profile: a production server without them must not start. */
class ProductionDefaultsTest {

    private static Properties yaml(String file) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(file));
        return factory.getObject();
    }

    private static StandardEnvironment environment(Properties... files) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (int i = 0; i < files.length; i++) {
            env.getPropertySources().addFirst(new PropertiesPropertySource("file" + i, files[i]));
        }
        return env;
    }

    @Test
    void theDatabasePasswordHasNoDefaultOutsideTheLocalProfile() {
        StandardEnvironment production = environment(yaml("application.yml"));
        assertThatThrownBy(() -> production.getProperty("spring.datasource.password"))
                .hasMessageContaining("DATABASE_PASSWORD");
    }

    @Test
    void theLocalProfileKeepsTheDevelopmentPassword() {
        StandardEnvironment local = environment(yaml("application.yml"), yaml("application-local.yml"));
        assertThat(local.getProperty("spring.datasource.password")).isEqualTo("insidejoke");
    }

    @Test
    void aRealPasswordWins() {
        Properties env = new Properties();
        env.setProperty("DATABASE_PASSWORD", "from-the-environment");
        StandardEnvironment production = environment(yaml("application.yml"), env);
        assertThat(production.getProperty("spring.datasource.password")).isEqualTo("from-the-environment");
    }
}
