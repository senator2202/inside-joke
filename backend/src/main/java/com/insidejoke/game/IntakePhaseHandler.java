package com.insidejoke.game;

import com.insidejoke.analytics.AnalyticsService;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.moderation.ContentRuleUtils;
import com.insidejoke.moderation.ModerationAction;
import com.insidejoke.moderation.ModerationStage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Intake: the three questions each player answers, and the secrets players give the host. Runs under the room
 * lock taken by {@link GameEngineService}.
 */
final class IntakePhaseHandler {

    private final GameRuntimeService runtime;
    private final GameProperties props;
    private final HostAiService ai;
    private final AnalyticsService analytics;

    IntakePhaseHandler(GameRuntimeService runtime, GameProperties props, HostAiService ai, AnalyticsService analytics) {
        this.runtime = runtime;
        this.props = props;
        this.ai = ai;
        this.analytics = analytics;
    }

    // ------------------------------------------------------------------ intake and secrets

    void submitIntake(RoomState r, PlayerState p, JsonNode data, ReplyHandler reply) {
        if (r.getPhase() != Phase.INTAKE && r.getPhase() != Phase.LOBBY) {
            throw new ApiException(ErrorCode.INVALID_PHASE);
        }
        JsonNode answers = data.path("answers");
        if (!answers.isArray() || answers.size() != 3) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Send three answers.");
        }
        List<String> cleaned = new ArrayList<>();
        List<Integer> rejected = new ArrayList<>();
        String category = null;
        for (int i = 0; i < 3; i++) {
            String a = ContentRuleUtils.clean(answers.get(i).asString(""));
            if (a.codePointCount(0, a.length()) > props.maxAnswerChars()) {
                throw new ApiException(
                        ErrorCode.VALIDATION_FAILED,
                        "Answers are limited to " + props.maxAnswerChars() + " characters.");
            }
            Optional<String> violation = a.isEmpty() ? Optional.empty() : ContentRuleUtils.checkPersonal(a);
            if (violation.isPresent()) {
                rejected.add(i);
                category = violation.get();
            }
            cleaned.add(a);
        }
        if (!rejected.isEmpty()) {
            runtime.recordModeration(r.getSessionId(), ModerationStage.INTAKE, category, ModerationAction.BLOCKED);
            throw new ApiException(
                    ErrorCode.MODERATION_BLOCKED,
                    "The host can't use that answer. Try another one.",
                    Map.of("rejected", rejected));
        }
        List<String> toCheck = cleaned.stream().filter(a -> !a.isEmpty()).toList();
        if (!toCheck.isEmpty()) {
            if (p.getModerationChecks() >= props.maxModerationChecksPerPlayer()) {
                throw new ApiException(ErrorCode.RATE_LIMITED, "Too many attempts this game.");
            }
            p.addModerationChecks(1);
        }
        HostAiService.CallContext ctx = runtime.ctx(r);
        String playerId = p.getId();
        runtime.async(
                r,
                () -> toCheck.isEmpty()
                        ? new HostAiService.Verdicts(true, List.of(), List.of())
                        : ai.moderate(ctx, ModerationStage.INTAKE, toCheck),
                (room, outcome) -> {
                    HostAiService.Verdicts v = outcome.value();
                    if (v == null || !v.available()) {
                        reply.error(new ApiException(
                                ErrorCode.MODERATION_UNAVAILABLE,
                                "Couldn't check your answers. Try again in a moment."));
                        return;
                    }
                    List<Integer> blocked = new ArrayList<>();
                    int k = 0;
                    for (int i = 0; i < 3; i++) {
                        if (!cleaned.get(i).isEmpty()) {
                            if (!v.allowed().get(k)) {
                                blocked.add(i);
                                runtime.recordModeration(
                                        room.getSessionId(),
                                        ModerationStage.INTAKE,
                                        v.categories().get(k),
                                        ModerationAction.BLOCKED);
                            }
                            k++;
                        }
                    }
                    if (!blocked.isEmpty()) {
                        reply.error(new ApiException(
                                ErrorCode.MODERATION_BLOCKED,
                                "The host can't use that answer. Try another one.",
                                Map.of("rejected", blocked)));
                        return;
                    }
                    PlayerState player = room.getPlayers().get(playerId);
                    if (player.isRemoved()) {
                        return;
                    }
                    for (int i = 0; i < 3; i++) {
                        player.getIntake()[i] = cleaned.get(i).isEmpty() ? null : cleaned.get(i);
                    }
                    player.setIntakeGame(Math.max(1, room.getGameNumber()));
                    analytics.track(
                            "intake_completed",
                            room.getCode() + ":" + playerId,
                            Map.of("answered", player.intakeAnswered()));
                    reply.ok();
                    if (room.getPhase() == Phase.INTAKE && room.getPause() == null && intakeComplete(room)) {
                        endIntake(room);
                    }
                });
    }

    boolean intakeComplete(RoomState r) {
        return runtime.presentPlayers(r).stream().allMatch(p -> p.getIntakeGame() > 0);
    }

    void addSecret(RoomState r, PlayerState author, JsonNode data, ReplyHandler reply) {
        String text = ContentRuleUtils.clean(data.path("text").asString(""));
        String aboutRaw = data.path("aboutPlayerId").isNull()
                        || data.path("aboutPlayerId").isMissingNode()
                ? null
                : data.path("aboutPlayerId").asString("");
        String aboutId = aboutRaw == null || aboutRaw.isEmpty()
                ? author.getId()
                : GameRuleUtils.activePlayer(r, aboutRaw).getId();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > props.maxFactChars()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Secrets are 1 to " + props.maxFactChars() + " characters.");
        }
        if (author.getFactsAdded() >= props.maxFactsPerPlayer()
                || author.getModerationChecks() >= props.maxModerationChecksPerPlayer()) {
            throw new ApiException(ErrorCode.DOSSIER_LIMIT);
        }
        if (!ai.secretsCheckable()) {
            throw new ApiException(
                    ErrorCode.MODERATION_UNAVAILABLE, "Secrets are off: there is no model to check them.");
        }
        Optional<String> violation = ContentRuleUtils.checkPersonal(text);
        if (violation.isPresent()) {
            runtime.recordModeration(
                    r.getSessionId(), ModerationStage.DOSSIER, violation.get(), ModerationAction.BLOCKED);
            throw new ApiException(ErrorCode.MODERATION_BLOCKED, "The host doesn't touch that topic.");
        }
        author.addFactsAdded(1);
        author.addModerationChecks(1);
        HostAiService.CallContext ctx = runtime.ctx(r);
        String authorId = author.getId();
        runtime.async(r, () -> ai.moderate(ctx, ModerationStage.DOSSIER, List.of(text)), (room, outcome) -> {
            PlayerState a = room.getPlayers().get(authorId);
            HostAiService.Verdicts v = outcome.value();
            if (v == null || !v.available()) {
                a.addFactsAdded(-1);
                reply.error(new ApiException(
                        ErrorCode.MODERATION_UNAVAILABLE, "Couldn't accept the secret. Try again in a bit."));
                return;
            }
            if (!v.allowed().getFirst()) {
                a.addFactsAdded(-1);
                runtime.recordModeration(
                        room.getSessionId(),
                        ModerationStage.DOSSIER,
                        v.categories().getFirst(),
                        ModerationAction.BLOCKED);
                reply.error(new ApiException(ErrorCode.MODERATION_BLOCKED, "The host doesn't touch that topic."));
                return;
            }
            room.addDossier(new DossierFact(room.nextId("f"), authorId, aboutId, text));
            analytics.track(
                    "dossier_added", room.getCode() + ":" + authorId, Map.of("about_self", aboutId.equals(authorId)));
            reply.ok(Map.of("secretsLeft", Math.max(0, props.maxFactsPerPlayer() - a.getFactsAdded())));
        });
    }

    int secretsLeft(PlayerState p) {
        return Math.max(0, props.maxFactsPerPlayer() - p.getFactsAdded());
    }
    // ------------------------------------------------------------------ rounds

    void endIntake(RoomState r) {
        r.setDeadlineMs(null);
        runtime.startRound(r, 1);
    }
}
