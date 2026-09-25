package com.insidejoke.web;

import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Client-side routes of the single-page app: one or two path segments without a dot, other than the API, the
     * WebSocket, actuator, assets and sign-in paths. Unknown client routes get the SPA's own "page not found".
     */
    private static final String[] SPA_ROUTES = {
        "/",
        "/{first:(?!api$|ws$|actuator$|assets$|oauth2$)[^.]+}",
        "/{first:(?!api$|ws$|actuator$|assets$|oauth2$|login$)[^.]+}/{second:[^.]+}"
    };

    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        for (String route : SPA_ROUTES) {
            registry.addViewController(route).setViewName("forward:/index.html");
        }
    }

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        // Vite emits content-hashed file names, so assets can be cached forever.
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("classpath:/static/assets/")
                .setCacheControl(
                        CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
