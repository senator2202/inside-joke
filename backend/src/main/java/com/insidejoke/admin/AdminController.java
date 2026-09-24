package com.insidejoke.admin;

import com.insidejoke.admin.dto.AdminMetricsDto;
import com.insidejoke.admin.dto.AdminUserDto;
import com.insidejoke.admin.dto.DrainRequestDto;
import com.insidejoke.admin.dto.DrainStateDto;
import com.insidejoke.admin.dto.FailedWebhookDto;
import com.insidejoke.admin.dto.GrantPassRequestDto;
import com.insidejoke.admin.dto.NotAppliedEventDto;
import com.insidejoke.admin.dto.PageDto;
import com.insidejoke.admin.dto.PassLogEntryDto;
import com.insidejoke.admin.dto.PassRevokedDto;
import com.insidejoke.admin.dto.PassSummaryDto;
import com.insidejoke.admin.dto.SettingValueRequestDto;
import com.insidejoke.auth.AppPrincipal;
import com.insidejoke.auth.UserEntity;
import com.insidejoke.auth.UserService;
import com.insidejoke.billing.EntitlementEntity;
import com.insidejoke.billing.EntitlementRepository;
import com.insidejoke.billing.GameAccessService;
import com.insidejoke.billing.PaddleEventService;
import com.insidejoke.billing.Product;
import com.insidejoke.billing.RevokeReason;
import com.insidejoke.billing.WebhookEventRepository;
import com.insidejoke.billing.dto.WebhookOutcomeDto;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.game.GameEngineService;
import com.insidejoke.game.GameSessionRepository;
import com.insidejoke.settings.AppSettingsService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.BooleanNode;

/** Admin API (blueprint 8); every path under /api/admin requires the ADMIN role (SecurityConfig). */
@RestController
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);
    private static final int MAX_RANGE_DAYS = 366;

    private final AdminMetricsService metrics;
    private final PassReportService passReports;
    private final UserService users;
    private final GameAccessService access;
    private final EntitlementRepository entitlements;
    private final AppSettingsService settings;
    private final GameEngineService engine;
    private final WebhookEventRepository webhooks;
    private final PaddleEventService paddleEvents;
    private final GameSessionRepository gameSessions;
    private final JsonMapper json;
    private final Clock clock;

    public AdminController(
            AdminMetricsService metrics,
            PassReportService passReports,
            UserService users,
            GameAccessService access,
            EntitlementRepository entitlements,
            AppSettingsService settings,
            GameEngineService engine,
            WebhookEventRepository webhooks,
            PaddleEventService paddleEvents,
            GameSessionRepository gameSessions,
            JsonMapper json,
            Clock clock) {
        this.metrics = metrics;
        this.passReports = passReports;
        this.users = users;
        this.access = access;
        this.entitlements = entitlements;
        this.settings = settings;
        this.engine = engine;
        this.webhooks = webhooks;
        this.paddleEvents = paddleEvents;
        this.gameSessions = gameSessions;
        this.json = json;
        this.clock = clock;
    }

    @GetMapping("/api/admin/metrics")
    public AdminMetricsDto metrics(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate end = to != null ? to : LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate start = from != null ? from : end.minusDays(29);
        if (start.isAfter(end) || ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Pick a range of 1 to " + MAX_RANGE_DAYS + " days.",
                    Map.of("fields", Map.of("from", "must be on or before 'to' and at most a year earlier")));
        }
        return metrics.metrics(start, end);
    }

    @GetMapping("/api/admin/users")
    public List<AdminUserDto> users(@RequestParam(defaultValue = "") String email) {
        String fragment = email.trim();
        if (fragment.length() < 2) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Type at least 2 characters of the email.",
                    Map.of("fields", Map.of("email", "at least 2 characters")));
        }
        return users.search(fragment, 20).stream().map(this::view).toList();
    }

    @PostMapping("/api/admin/users/{id}/passes")
    public AdminUserDto grant(
            @PathVariable UUID id, @RequestBody GrantPassRequestDto body, @AuthenticationPrincipal AppPrincipal admin) {
        Product product;
        try {
            product = Product.valueOf(body.type() == null ? "" : body.type());
        } catch (IllegalArgumentException e) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Unknown pass type.",
                    Map.of("fields", Map.of("type", "PARTY_PASS or HOST_PASS")));
        }
        Duration validity = body.days() == null ? product.validity() : Duration.ofDays(body.days());
        if (validity.toDays() < 1 || validity.toDays() > MAX_RANGE_DAYS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Days must be between 1 and " + MAX_RANGE_DAYS + ".",
                    Map.of("fields", Map.of("days", "1 to " + MAX_RANGE_DAYS)));
        }
        UserEntity user =
                users.findActive(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such user."));
        Instant now = clock.instant();
        EntitlementEntity pass = entitlements.grant(user.id(), product, now, now.plus(validity), admin.id(), now);
        log.info("Admin {} granted {} {} to {} until {}", admin.id(), product, pass.id(), user.id(), pass.endsAt());
        return view(user);
    }

    @PostMapping("/api/admin/passes/{id}/revoke")
    public PassRevokedDto revoke(@PathVariable UUID id, @AuthenticationPrincipal AppPrincipal admin) {
        if (entitlements.revoke(id, clock.instant(), RevokeReason.ADMIN, admin.id()) == 0) {
            throw new ApiException(ErrorCode.NOT_FOUND, "No active pass with that id.");
        }
        log.info("Admin {} revoked pass {}", admin.id(), id);
        return new PassRevokedDto(true);
    }

    @GetMapping("/api/admin/settings")
    public Map<String, Object> settings() {
        return settings.get().asMap();
    }

    @PutMapping("/api/admin/settings/{key}")
    public Map<String, Object> updateSetting(@PathVariable String key, @RequestBody SettingValueRequestDto body) {
        return settings.update(key, body.value()).asMap();
    }

    @PostMapping("/api/admin/drain")
    public DrainStateDto drain(@RequestBody(required = false) DrainRequestDto body) {
        boolean enabled = body == null || body.enabled() == null || body.enabled();
        settings.update("drain_mode", BooleanNode.valueOf(enabled));
        return new DrainStateDto(settings.get().drainMode(), engine.liveStats());
    }

    /** The pass log: every pass, bought or granted, filtered, sorted and paginated on the server. */
    @GetMapping("/api/admin/passes")
    public PageDto<PassLogEntryDto> passes(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String email,
            @RequestParam(defaultValue = "created") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return passReports.passes(
                PassReportService.filter(type, source, status, from, to, email), sort, dir, page, size);
    }

    /** Totals for the same filters as the pass log (the status filter doesn't apply: the summary splits by status itself). */
    @GetMapping("/api/admin/passes/summary")
    public PassSummaryDto passSummary(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String email) {
        return passReports.summary(PassReportService.filter(type, source, null, from, to, email));
    }

    /** Refunds and chargebacks that arrived but were not applied, with the reason; by default only where the pass is still held. */
    @GetMapping("/api/admin/webhooks/not-applied")
    public PageDto<NotAppliedEventDto> notApplied(
            @RequestParam(defaultValue = "true") boolean stillHeld,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return passReports.notApplied(stillHeld, page, size);
    }

    @GetMapping("/api/admin/webhooks/failed")
    public List<FailedWebhookDto> failedWebhooks() {
        return webhooks.listUnprocessed(50).stream()
                .map(p -> new FailedWebhookDto(p.provider(), p.eventId(), p.eventType(), p.receivedAt(), p.lastError()))
                .toList();
    }

    @PostMapping("/api/admin/webhooks/{eventId}/replay")
    public WebhookOutcomeDto replay(@PathVariable String eventId) {
        String payload = webhooks.payload(PaddleEventService.PROVIDER, eventId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "No such event."));
        JsonNode event;
        try {
            event = json.readTree(payload);
        } catch (JacksonException e) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Stored payload is not JSON.");
        }
        return paddleEvents.process(eventId, event.path("event_type").asString(""), event);
    }

    private AdminUserDto view(UserEntity u) {
        int games = gameSessions.countByHost(u.id());
        return new AdminUserDto(
                u.id(),
                u.email(),
                u.displayName(),
                u.role(),
                u.googleSub() != null,
                u.createdAt(),
                u.lastLoginAt(),
                games,
                access.status(u.id()).passes());
    }
}
