package com.insidejoke.support;

import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** Settings records with their production defaults ({@code @DefaultValue}), built without a Spring context. */
public final class PropertyDefaults {

    private PropertyDefaults() {}

    public static <T> T of(String prefix, Class<T> type) {
        return new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate(prefix, type);
    }
}
