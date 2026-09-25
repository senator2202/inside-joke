package com.insidejoke.room;

import com.insidejoke.auth.AppPrincipal;
import com.insidejoke.common.ApiException;
import com.insidejoke.common.AppProperties;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.common.Language;
import com.insidejoke.game.GameEngineService;
import com.insidejoke.game.GameLength;
import com.insidejoke.game.RoomMode;
import com.insidejoke.game.RoomSettings;
import com.insidejoke.game.RoomState;
import com.insidejoke.game.Tone;
import com.insidejoke.game.dto.RoomStatusDto;
import com.insidejoke.room.dto.AudienceTokenDto;
import com.insidejoke.room.dto.CreateRoomRequestDto;
import com.insidejoke.room.dto.CreatedRoomDto;
import com.insidejoke.room.dto.JoinRequestDto;
import com.insidejoke.room.dto.JoinedDto;
import com.insidejoke.room.dto.ScreenTokenDto;
import com.insidejoke.web.ClientIpUtils;
import com.insidejoke.web.RateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Room REST API (blueprint 8): create, look up, and get tokens for players, screen copies and viewers. */
@RestController
public class RoomController {

    private final GameEngineService engine;
    private final RateLimitService limiter;
    private final AppProperties app;

    public RoomController(GameEngineService engine, RateLimitService limiter, AppProperties app) {
        this.engine = engine;
        this.limiter = limiter;
        this.app = app;
    }

    @PostMapping("/api/rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedRoomDto create(@AuthenticationPrincipal AppPrincipal user, @RequestBody CreateRoomRequestDto body) {
        limiter.check(RateLimitService.ROOM_CREATE_PER_USER, user.id().toString());
        Tone tone = parse(Tone.class, body.tone(), "tone");
        GameLength length = parse(GameLength.class, body.length(), "length");
        RoomMode mode = body.mode() == null ? RoomMode.STANDARD : parse(RoomMode.class, body.mode(), "mode");
        Language language = body.language() == null
                ? Language.EN
                : Language.fromCode(body.language()).orElseThrow(() -> invalid("language", "Choose en or ru."));
        if (tone == Tone.SPICY && !Boolean.TRUE.equals(body.adultsConfirmed())) {
            throw invalid("adultsConfirmed", "Confirm that every player is over 18.");
        }
        boolean hideCode = mode == RoomMode.STREAMER && Boolean.TRUE.equals(body.hideCode());
        RoomState room = engine.createRoom(user.id(), new RoomSettings(tone, length, mode, hideCode, language));
        return new CreatedRoomDto(room.getCode(), room.getOwnerToken(), app.publicUrl() + "/j/" + room.getCode());
    }

    @GetMapping("/api/rooms/{code}")
    public RoomStatusDto status(@PathVariable String code, HttpServletRequest request) {
        limiter.check(RateLimitService.ROOM_LOOKUP_PER_IP, ClientIpUtils.of(request));
        return engine.status(room(code));
    }

    /** Lets the owner reopen their shared screen on another device or after clearing storage (S1 error state). */
    @GetMapping("/api/rooms/{code}/owner-token")
    public ScreenTokenDto ownerToken(@AuthenticationPrincipal AppPrincipal user, @PathVariable String code) {
        RoomState room = room(code);
        if (!room.getOwnerUserId().equals(user.id())) {
            throw new ApiException(ErrorCode.FORBIDDEN, "This isn't your room.");
        }
        return new ScreenTokenDto(room.getOwnerToken());
    }

    @PostMapping("/api/rooms/{code}/players")
    @ResponseStatus(HttpStatus.CREATED)
    public JoinedDto join(@PathVariable String code, @RequestBody JoinRequestDto body, HttpServletRequest request) {
        limiter.check(RateLimitService.ROOM_JOIN_PER_IP, ClientIpUtils.of(request));
        GameEngineService.JoinedPlayer joined =
                engine.joinPlayer(room(code), body.name() == null ? "" : body.name(), body.emoji());
        return new JoinedDto(joined.playerId(), joined.token());
    }

    @PostMapping("/api/rooms/{code}/screens")
    @ResponseStatus(HttpStatus.CREATED)
    public ScreenTokenDto screen(@PathVariable String code, HttpServletRequest request) {
        limiter.check(RateLimitService.ROOM_JOIN_PER_IP, ClientIpUtils.of(request));
        return new ScreenTokenDto(engine.joinScreen(room(code)));
    }

    /** Viewers join by the audience key from their link, never by the room code (which a streamer may hide). */
    @PostMapping("/api/audiences/{key}/viewers")
    @ResponseStatus(HttpStatus.CREATED)
    public AudienceTokenDto audience(@PathVariable String key, HttpServletRequest request) {
        limiter.check(RateLimitService.ROOM_JOIN_PER_IP, ClientIpUtils.of(request));
        RoomState room = engine.findByAudienceKey(key).orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));
        return new AudienceTokenDto(engine.joinAudience(room));
    }

    // The endpoint serves a fixed list, so of course it always returns the same value.
    @SuppressWarnings("SameReturnValue")
    @GetMapping("/api/rooms/emojis")
    public List<String> emojis() {
        return GameEngineService.EMOJIS;
    }

    private RoomState room(String code) {
        return engine.find(code).orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        if (value == null) {
            throw invalid(field, "Required.");
        }
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw invalid(field, "Unknown value.");
        }
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message, Map.of("fields", Map.of(field, message)));
    }
}
