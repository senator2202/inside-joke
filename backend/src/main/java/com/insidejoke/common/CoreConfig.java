package com.insidejoke.common;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CoreConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Shared client for all outgoing API calls (Anthropic, TTS, Paddle, Resend, PostHog). */
    @Bean(destroyMethod = "close")
    public HttpClient httpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Virtual threads for blocking network calls that must never run under a room lock. */
    @Bean(destroyMethod = "close")
    public ExecutorService ioExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /** One shared timer thread for phase deadlines, broadcast coalescing and housekeeping. */
    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService gameScheduler() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "game-timer");
            t.setDaemon(true);
            return t;
        });
    }
}
