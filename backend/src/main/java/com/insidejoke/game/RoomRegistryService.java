package com.insidejoke.game;

import com.insidejoke.common.TokenUtils;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import org.springframework.stereotype.Service;

/** All live rooms and the index from room tokens to rooms (blueprint 4.1, 4.6). */
@Service
public class RoomRegistryService {

    /** 20 consonants: no look-alike characters and no vowels, so codes never spell words. */
    @SuppressWarnings("SpellCheckingInspection")
    public static final String ALPHABET = "BCDFGHJKLMNPQRSTVWXZ";

    private final ConcurrentHashMap<String, RoomState> rooms = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> tokens = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> audienceKeys = new ConcurrentHashMap<>();

    static final int AUDIENCE_KEY_LENGTH = 10;

    /** Registers a room under a fresh code that is unique among live rooms. */
    RoomState register(BiFunction<String, String, RoomState> factory) {
        while (true) {
            String candidate = randomKey(4);
            String audienceKey = randomKey(AUDIENCE_KEY_LENGTH);
            if (audienceKeys.putIfAbsent(audienceKey, candidate) != null) {
                continue;
            }
            RoomState[] created = new RoomState[1];
            rooms.computeIfAbsent(candidate, c -> {
                created[0] = factory.apply(c, audienceKey);
                return created[0];
            });
            if (created[0] != null) {
                tokens.put(created[0].getOwnerToken(), candidate);
                return created[0];
            }
            audienceKeys.remove(audienceKey, candidate);
        }
    }

    private static String randomKey(int length) {
        StringBuilder key = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            key.append(ALPHABET.charAt(TokenUtils.randomInt(ALPHABET.length())));
        }
        return key.toString();
    }

    public Optional<RoomState> find(String code) {
        return code == null
                ? Optional.empty()
                : Optional.ofNullable(rooms.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    /** The room behind a viewers' link; room codes are not accepted here. */
    public Optional<RoomState> byAudienceKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        String code = audienceKeys.get(key.trim().toUpperCase(Locale.ROOT));
        return code == null ? Optional.empty() : Optional.ofNullable(rooms.get(code));
    }

    public Optional<RoomState> byToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        String code = tokens.get(token);
        return code == null ? Optional.empty() : Optional.ofNullable(rooms.get(code));
    }

    void indexToken(String token, RoomState room) {
        tokens.put(token, room.getCode());
    }

    void remove(RoomState room, Collection<String> roomTokens) {
        rooms.remove(room.getCode(), room);
        audienceKeys.remove(room.getAudienceKey(), room.getCode());
        roomTokens.forEach(tokens::remove);
    }

    public Collection<RoomState> all() {
        return rooms.values();
    }

    List<RoomState> ownedBy(UUID userId) {
        return rooms.values().stream()
                .filter(r -> r.getOwnerUserId().equals(userId))
                .toList();
    }
}
