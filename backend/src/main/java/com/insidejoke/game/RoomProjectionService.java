package com.insidejoke.game;

import com.insidejoke.common.AppProperties;
import com.insidejoke.common.Language;
import com.insidejoke.game.dto.AnswerProgressDto;
import com.insidejoke.game.dto.AssignmentDto;
import com.insidejoke.game.dto.AudienceDto;
import com.insidejoke.game.dto.FinaleDto;
import com.insidejoke.game.dto.HostDto;
import com.insidejoke.game.dto.IntakeDto;
import com.insidejoke.game.dto.KindVoteDto;
import com.insidejoke.game.dto.LobbyDto;
import com.insidejoke.game.dto.OptionDto;
import com.insidejoke.game.dto.PausedDto;
import com.insidejoke.game.dto.PlayerDto;
import com.insidejoke.game.dto.RoomSettingsDto;
import com.insidejoke.game.dto.RoomStateDto;
import com.insidejoke.game.dto.RoundDto;
import com.insidejoke.game.dto.WaitingDto;
import com.insidejoke.game.dto.YouDto;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Builds the snapshot each connection receives (blueprint 4.5): the full state filtered for one role.
 * Nobody ever sees the dossier; answer authors stay hidden until REVEAL; duel prompts stay off the shared
 * screen while players write. Must be called under the room lock.
 */
@Service
public class RoomProjectionService {

    private final AppProperties app;
    private final FallbackContentService fallback;
    private final GameProperties props;
    private final HostAiService ai;
    private final Clock clock;

    public RoomProjectionService(
            AppProperties app, FallbackContentService fallback, GameProperties props, HostAiService ai, Clock clock) {
        this.app = app;
        this.fallback = fallback;
        this.props = props;
        this.ai = ai;
        this.clock = clock;
    }

    public RoomStateDto project(RoomState r, Member m) {
        Role role = r.roleOf(m);
        boolean owner = role == Role.OWNER_SCREEN;
        boolean screen = owner || role == Role.SCREEN;
        boolean player = role == Role.PLAYER || role == Role.CAPTAIN;
        PlayerState me = player ? r.getPlayers().get(m.playerId()) : null;
        long now = clock.millis();

        String code = r.getSettings().hideCode() && (role == Role.AUDIENCE || role == Role.SCREEN) ? null : r.getCode();
        PausedDto paused = r.getPause() == null
                ? null
                : new PausedDto(
                        r.getPause(),
                        r.isWelcomeBack(),
                        r.getPausedRemainingMs() > 0 ? r.getPausedRemainingMs() : null);
        WaitingDto waiting = r.getPause() == PauseReason.WAITING_FOR_PLAYERS && r.getWaitDeadlineMs() != null
                ? new WaitingDto(
                        r.getWaitDeadlineMs(),
                        r.activePlayers().stream()
                                .filter(p -> !r.presentPlayers(clock.instant(), props.disconnectGrace())
                                                .contains(p)
                                        || !p.connected())
                                .map(PlayerState::getName)
                                .toList())
                : null;

        LobbyDto lobby = null;
        if (screen && (r.getPhase() == Phase.LOBBY || r.getPhase() == Phase.INTAKE)) {
            String base = app.publicUrl();
            // With a hidden code only the owner's own screen gets the join link; copies and viewers never see the code.
            String joinUrl = code == null && !owner ? null : base + "/j/" + r.getCode();
            lobby = new LobbyDto(
                    joinUrl,
                    r.getSettings().mode() == RoomMode.STREAMER ? base + "/w/" + r.getAudienceKey() : null,
                    r.getAudienceCount(),
                    props.minPlayers(),
                    props.maxPlayers(),
                    owner && r.isBotsAllowed() ? Boolean.TRUE : null);
        } else if (screen && r.getSettings().mode() == RoomMode.STREAMER) {
            lobby = new LobbyDto(
                    null,
                    app.publicUrl() + "/w/" + r.getAudienceKey(),
                    r.getAudienceCount(),
                    props.minPlayers(),
                    props.maxPlayers(),
                    null);
        }

        RoundDto round = roundView(r);
        return new RoomStateDto(
                r.getVersion(),
                now,
                code,
                r.getPhase(),
                r.getPause() == null && r.getPending() == null ? r.getDeadlineMs() : null,
                paused,
                waiting,
                r.getPending() != null,
                r.isLocked(),
                new RoomSettingsDto(
                        r.getSettings().tone(),
                        r.getSettings().length(),
                        r.getSettings().mode(),
                        r.getSettings().hideCode(),
                        r.getSettings().language().code(),
                        r.getSettings().company(),
                        // The owner's line about the group stays on the owner's screen: never on a stream.
                        owner ? r.getSettings().context() : null),
                lobby,
                role == Role.AUDIENCE ? null : players(r),
                you(r, role, me),
                host(r, m, role),
                r.getPhase() == Phase.INTAKE || me != null && me.getIntakeGame() == 0 ? intake(r, me) : null,
                r.getPhase() == Phase.ROUND_VOTE && role != Role.AUDIENCE ? kindVote(r, me) : null,
                round,
                r.getPhase() == Phase.FINALE && role != Role.AUDIENCE
                                || r.getPhase() == Phase.FINALE && r.getFinale() != null
                        ? finale(r)
                        : null,
                role == Role.AUDIENCE ? null : r.getDossier().size(),
                role == Role.AUDIENCE ? null : ai.secretsCheckable(),
                r.isStarting(),
                owner || role == Role.CAPTAIN ? r.getPaywall() : null,
                owner && r.getLastSessionId() != null ? r.getLastSessionId().toString() : null,
                role == Role.AUDIENCE ? new AudienceDto(r.audienceVoted(m.audienceId()), audienceTotal(r)) : null);
    }

    private static Integer audienceTotal(RoomState r) {
        VoteStepState step = r.getRound() == null ? null : r.getRound().currentVote();
        return step == null ? null : step.audienceTotal();
    }

    private List<PlayerDto> players(RoomState r) {
        List<PlayerDto> out = new ArrayList<>();
        for (PlayerState p : r.activePlayers()) {
            out.add(new PlayerDto(
                    p.getId(),
                    p.getName(),
                    p.getEmoji(),
                    p.connected(),
                    p.getScore(),
                    p.getId().equals(r.getCaptainId()),
                    status(r, p),
                    r.getPhase() == Phase.FINALE ? r.rankOf(p) : null,
                    p.isBot() ? Boolean.TRUE : null));
        }
        return out;
    }

    private static String status(RoomState r, PlayerState p) {
        switch (r.getPhase()) {
            case INTAKE:
                return p.getIntakeGame() > 0 ? "done" : "writing";
            case ROUND_VOTE:
                return r.getKindVotes().containsKey(p.getId()) ? "voted" : "choosing";
            case ANSWERING: {
                int total = 0;
                int answered = 0;
                for (DuelState d : r.getRound().getDuels()) {
                    if (d.involves(p.getId())) {
                        total++;
                        if (d.answerOf(p.getId()) != null) {
                            answered++;
                        }
                    }
                }
                return total == 0 ? "waiting" : answered == total ? "done" : answered + "/" + total;
            }
            case VOTING: {
                VoteStepState step = r.getRound() == null ? null : r.getRound().currentVote();
                if (step == null || !step.getEligible().contains(p.getId())) {
                    return "watching";
                }
                return step.getVotes().containsKey(p.getId()) ? "voted" : "voting";
            }
            default:
                return "ready";
        }
    }

    private YouDto you(RoomState r, Role role, PlayerState me) {
        if (me == null) {
            return new YouDto(
                    role, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
        }
        VoteStepState step = r.getRound() == null ? null : r.getRound().currentVote();
        boolean canVote = r.getPhase() == Phase.VOTING
                && step != null
                && step.getEligible().contains(me.getId());
        String myVote = step == null ? null : step.getVotes().get(me.getId());
        List<AssignmentDto> assignments = null;
        Boolean inDuel = null;
        if (r.getRound() != null && r.getRound().getKind() == RoundKind.ANSWER_DUEL) {
            if (r.getPhase() == Phase.ANSWERING) {
                assignments = r.getRound().getDuels().stream()
                        .filter(d -> d.involves(me.getId()))
                        .map(d -> new AssignmentDto(d.getId(), d.getPrompt(), d.answerOf(me.getId())))
                        .toList();
            }
            DuelState current = r.getRound().currentDuel();
            inDuel = current != null
                    && current.involves(me.getId())
                    && (r.getPhase() == Phase.VOTING || r.getPhase() == Phase.REVEAL);
        }
        Boolean subject = r.getRound() != null && r.getRound().getKind() == RoundKind.TRUTH_OR_AI
                ? me.getId().equals(r.getRound().getSubjectId())
                : null;
        Integer roundPoints = r.getPhase() == Phase.REVEAL && r.getRound() != null
                ? r.getRound().getPoints().getOrDefault(me.getId(), 0)
                : null;
        String title = r.getPhase() == Phase.FINALE && r.getFinale() != null
                ? r.getFinale().titles().get(me.getId())
                : null;
        return new YouDto(
                role,
                me.getId(),
                me.getName(),
                me.getEmoji(),
                me.getScore(),
                r.rankOf(me),
                canVote,
                myVote != null,
                myVote,
                me.secretsLeft(props.maxFactsPerPlayer()),
                me.getIntakeGame() == 0,
                assignments,
                subject,
                inDuel,
                roundPoints,
                title);
    }

    private static HostDto host(RoomState r, Member m, Role role) {
        HostLine line = r.getHostLine();
        if (line == null) {
            return null;
        }
        boolean aboutYou = m.playerId() != null && line.aboutPlayerIds().contains(m.playerId());
        boolean canSkip = !line.skipped()
                && (role == Role.OWNER_SCREEN || aboutYou && (role == Role.PLAYER || role == Role.CAPTAIN));
        boolean audio = role == Role.OWNER_SCREEN || role == Role.SCREEN;
        return new HostDto(
                line.id(),
                line.text(),
                audio ? line.audioId() : null,
                line.skipped(),
                m.playerId() == null ? null : aboutYou,
                canSkip);
    }

    private static IntakeDto intake(RoomState r, PlayerState me) {
        int total = r.activePlayers().size();
        int done = (int)
                r.activePlayers().stream().filter(p -> p.getIntakeGame() > 0).count();
        return new IntakeDto(me == null || me.getIntakeGame() == 0 ? r.getIntakeQuestions() : List.of(), done, total);
    }

    private static KindVoteDto kindVote(RoomState r, PlayerState me) {
        Map<RoundKind, List<String>> voters = new EnumMap<>(RoundKind.class);
        for (RoundKind k : RoundKind.values()) {
            voters.put(k, new ArrayList<>());
        }
        r.getKindVotes().forEach((playerId, kind) -> voters.get(kind).add(playerId));
        return new KindVoteDto(voters, me == null ? null : r.getKindVotes().get(me.getId()));
    }

    private RoundDto roundView(RoomState r) {
        RoundState round = r.getRound();
        if (round == null
                || !(r.getPhase() == Phase.ANSWERING || r.getPhase() == Phase.VOTING || r.getPhase() == Phase.REVEAL)) {
            return beforeRound(r);
        }
        boolean reveal = r.getPhase() == Phase.REVEAL;
        VoteStepState step = round.currentVote();
        VoteContext c = new VoteContext(
                round,
                reveal,
                step,
                step == null ? null : List.copyOf(step.getVotes().keySet()),
                step == null ? null : step.getEligible().size(),
                step == null || r.getSettings().mode() != RoomMode.STREAMER ? null : step.audienceTotal(),
                reveal ? Map.copyOf(round.getPoints()) : null);
        return switch (round.getKind()) {
            case ANSWER_DUEL -> duelRound(r, c);
            case WHO_OF_US -> whoOfUsRound(r, c);
            case TRUTH_OR_AI -> truthOrAiRound(r, c);
        };
    }

    /** What the voters and phase so far have in common, for the per-kind views below. */
    private record VoteContext(
            RoundState round,
            boolean reveal,
            VoteStepState step,
            List<String> voters,
            Integer eligible,
            Integer audienceTotal,
            Map<String, Integer> points) {}

    /** Before answering starts: the round-kind vote, or nothing. */
    private RoundDto beforeRound(RoomState r) {
        if (r.getPhase() == Phase.ROUND_VOTE
                || r.getPhase() == Phase.INTAKE
                || r.getPending() == RoomState.Pending.ROUND_CONTENT) {
            RoundKind upcoming = r.getPending() == RoomState.Pending.ROUND_CONTENT ? r.getPendingKind() : null;
            return new RoundDto(
                    Math.max(1, r.getRoundNumber()),
                    r.getRoundsTotal(),
                    upcoming,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        return null;
    }

    /** A duel: two answers per prompt, one vote step per duel. */
    private RoundDto duelRound(RoomState r, VoteContext c) {
        RoundState round = c.round();
        boolean reveal = c.reveal();
        List<String> voters = c.voters();
        Integer eligible = c.eligible();
        Integer audienceTotal = c.audienceTotal();
        Map<String, Integer> points = c.points();
        if (r.getPhase() == Phase.ANSWERING) {
            List<AnswerProgressDto> progress = new ArrayList<>();
            for (PlayerState p : r.activePlayers()) {
                int total = 0;
                int answered = 0;
                for (DuelState d : round.getDuels()) {
                    if (d.involves(p.getId())) {
                        total++;
                        answered += d.answerOf(p.getId()) != null ? 1 : 0;
                    }
                }
                if (total > 0) {
                    progress.add(new AnswerProgressDto(p.getId(), answered, total));
                }
            }
            return new RoundDto(
                    round.getNumber(),
                    r.getRoundsTotal(),
                    round.getKind(),
                    null,
                    round.getDuels().size(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    progress);
        }
        DuelState d = round.currentDuel();
        List<OptionDto> options = new ArrayList<>();
        boolean landslide = false;
        if (d != null) {
            int votesA = d.getVote() == null ? 0 : d.getVote().countFor("A");
            int votesB = d.getVote() == null ? 0 : d.getVote().countFor("B");
            landslide = reveal && d.usableA() && d.usableB() && votesA + votesB >= 2 && (votesA == 0 || votesB == 0);
            options.add(duelOption(
                    r,
                    d,
                    "A",
                    d.usableA() ? d.getAnswerA() : null,
                    d.getPlayerA(),
                    votesA,
                    d.getPointsA(),
                    votesA > votesB,
                    reveal,
                    audienceTotal != null));
            options.add(duelOption(
                    r,
                    d,
                    "B",
                    d.usableB() ? d.getAnswerB() : null,
                    d.getPlayerB(),
                    votesB,
                    d.getPointsB(),
                    votesB > votesA,
                    reveal,
                    audienceTotal != null));
        }
        boolean last = reveal && lastDuelOfRound(round);
        return new RoundDto(
                round.getNumber(),
                r.getRoundsTotal(),
                round.getKind(),
                round.getDuelIndex(),
                round.getDuels().size(),
                d == null ? null : d.getPrompt(),
                null,
                null,
                null,
                options,
                voters,
                eligible,
                reveal ? landslide : null,
                reveal ? last : null,
                points,
                audienceTotal,
                null);
    }

    /** Who of us: one question, a vote for a player. */
    private RoundDto whoOfUsRound(RoomState r, VoteContext c) {
        List<OptionDto> options = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        c.step().getVotes().values().forEach(id -> counts.merge(id, 1, Integer::sum));
        int best = counts.values().stream().max(Integer::compare).orElse(0);
        for (String id : c.step().getOptions()) {
            PlayerState p = r.getPlayers().get(id);
            options.add(new OptionDto(
                    id,
                    p.getName(),
                    id,
                    c.reveal() ? counts.getOrDefault(id, 0) : null,
                    c.audienceTotal() != null ? c.step().getAudience().getOrDefault(id, 0) : null,
                    null,
                    null,
                    c.reveal() ? best > 0 && counts.getOrDefault(id, 0) == best : null,
                    null));
        }
        return new RoundDto(
                c.round().getNumber(),
                r.getRoundsTotal(),
                c.round().getKind(),
                null,
                null,
                null,
                c.round().getQuestion(),
                null,
                null,
                options,
                c.voters(),
                c.eligible(),
                null,
                c.reveal() ? Boolean.TRUE : null,
                c.points(),
                c.audienceTotal(),
                null);
    }

    /** Truth or AI: a statement about one player, a vote for truth or fake. */
    private RoundDto truthOrAiRound(RoomState r, VoteContext c) {
        String correct = c.round().isStatementTrue() ? "truth" : "ai";
        List<OptionDto> options = new ArrayList<>();
        for (String id : List.of("truth", "ai")) {
            options.add(new OptionDto(
                    id,
                    id.equals("truth") ? "Truth" : "AI invention",
                    null,
                    c.reveal() ? c.step().countFor(id) : null,
                    c.audienceTotal() != null ? c.step().getAudience().getOrDefault(id, 0) : null,
                    null,
                    null,
                    null,
                    c.reveal() ? id.equals(correct) : null));
        }
        return new RoundDto(
                c.round().getNumber(),
                r.getRoundsTotal(),
                c.round().getKind(),
                null,
                null,
                null,
                null,
                c.round().getStatement(),
                c.round().getSubjectId(),
                options,
                c.voters(),
                c.eligible(),
                null,
                c.reveal() ? Boolean.TRUE : null,
                c.points(),
                c.audienceTotal(),
                null);
    }

    private OptionDto duelOption(
            RoomState r,
            DuelState d,
            String id,
            String text,
            String authorId,
            int votes,
            int points,
            boolean winner,
            boolean reveal,
            boolean audience) {
        Language lang = r.getSettings().language();
        String shown = text != null
                ? text
                : fallback.label(
                        lang,
                        "didntAnswer",
                        Map.of("name", r.getPlayers().get(authorId).getName()));
        if (text == null && !reveal) {
            shown = fallback.label(lang, "noAnswer", Map.of());
        }
        boolean blocked = "A".equals(id) ? d.isBlockedA() : d.isBlockedB();
        if (blocked) {
            shown = fallback.label(lang, "blockedAnswer", Map.of());
        }
        Integer audienceVotes =
                audience ? d.getVote() == null ? 0 : d.getVote().getAudience().getOrDefault(id, 0) : null;
        return new OptionDto(
                id,
                shown,
                null,
                reveal ? votes : null,
                audienceVotes,
                reveal ? authorId : null,
                reveal ? points : null,
                reveal ? winner : null,
                null);
    }

    private static boolean lastDuelOfRound(RoundState round) {
        for (int i = round.getDuelIndex() + 1; i < round.getDuels().size(); i++) {
            DuelState d = round.getDuels().get(i);
            if (d.usableA() || d.usableB()) {
                return false;
            }
        }
        return true;
    }

    private FinaleDto finale(RoomState r) {
        List<PlayerDto> standings = new ArrayList<>();
        for (PlayerState p : r.standings()) {
            standings.add(new PlayerDto(
                    p.getId(),
                    p.getName(),
                    p.getEmoji(),
                    p.connected(),
                    p.getScore(),
                    p.getId().equals(r.getCaptainId()),
                    "ready",
                    r.rankOf(p),
                    p.isBot() ? Boolean.TRUE : null));
        }
        int top = standings.isEmpty() ? 0 : standings.getFirst().score();
        List<String> winners = standings.stream()
                .filter(p -> p.score() == top && top > 0)
                .map(PlayerDto::id)
                .toList();
        Finale f = r.getFinale();
        return new FinaleDto(
                standings,
                f == null ? Map.of() : f.titles(),
                winners,
                r.getAnswerOfNightText(),
                r.getAnswerOfNightPrompt(),
                r.getAnswerOfNightAuthor(),
                f == null ? null : f.speech(),
                f != null);
    }
}
