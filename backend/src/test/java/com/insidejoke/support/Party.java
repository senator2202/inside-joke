package com.insidejoke.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A signed-in host with a room, the owner's shared screen and a number of player phones, all over real HTTP and WebSockets. */
public final class Party implements AutoCloseable {

    /** A player's phone: the player's id, name and room token, and the socket the party opened and will close. */
    public static final class Phone {
        private final String id;
        private final String name;
        private final String token;
        private final GameSocket socket;

        Phone(String id, String name, String token, GameSocket socket) {
            this.id = id;
            this.name = name;
            this.token = token;
            this.socket = socket;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }

        public String token() {
            return token;
        }

        /** The phone's connection; {@link Party#close()} closes it. */
        public GameSocket getSocket() {
            return socket;
        }
    }

    public final ApiClient host;
    public final UUID hostId;
    public final String code;
    public final String screenToken;
    public final GameSocket screen;
    public final List<Phone> phones = new ArrayList<>();
    private final int port;
    private final JsonMapper json;

    private Party(
            ApiClient host,
            UUID hostId,
            String code,
            String screenToken,
            GameSocket screen,
            int port,
            JsonMapper json) {
        this.host = host;
        this.hostId = hostId;
        this.code = code;
        this.screenToken = screenToken;
        this.screen = screen;
        this.port = port;
        this.json = json;
    }

    public static ApiClient signIn(int port, JsonMapper json, FakeHttp fake, String email) {
        FakeResend.accept(fake);
        ApiClient client = new ApiClient(port, json);
        expect(client.post("/api/auth/magic-link", Map.of("email", email)), 202, 200, 204);
        expect(
                client.post("/api/auth/email-code/verify", Map.of("email", email, "code", FakeResend.lastCode(fake))),
                200,
                204);
        return client;
    }

    public static Party create(int port, JsonMapper json, FakeHttp fake, Map<String, Object> settings, int players) {
        String email = "host-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        ApiClient host = signIn(port, json, fake, email);
        UUID hostId = UUID.fromString(host.get("/api/me").json().path("id").asString());
        ApiClient.Resp created = host.post("/api/rooms", settings);
        expect(created, 201);
        String code = created.json().path("code").asString();
        String screenToken = created.json().path("screenToken").asString();
        Party party =
                new Party(host, hostId, code, screenToken, GameSocket.connect(port, json, screenToken), port, json);
        for (int i = 0; i < players; i++) {
            party.join("Player" + (i + 1));
        }
        return party;
    }

    public static Party create(int port, JsonMapper json, FakeHttp fake, int players) {
        return create(port, json, fake, Map.of("tone", "CHEEKY", "length", "SHORT", "mode", "STANDARD"), players);
    }

    public void join(String name) {
        ApiClient guest = new ApiClient(port, json);
        ApiClient.Resp joined = guest.post("/api/rooms/" + code + "/players", Map.of("name", name, "emoji", "🦊"));
        expect(joined, 201);
        String token = joined.json().path("playerToken").asString();
        Phone phone = new Phone(
                joined.json().path("playerId").asString(), name, token, GameSocket.connect(port, json, token));
        phones.add(phone);
    }

    public Phone phone(String id) {
        return phones.stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    /**
     * The phone of the current captain, according to the shared screen. Waits for a state that names one: the captain
     * arrives with a broadcast that may still be on its way (updates are coalesced for 50 ms).
     */
    public Phone captain() {
        JsonNode state = screen.state(s -> captainId(s) != null);
        return phone(captainId(state));
    }

    private static String captainId(JsonNode state) {
        for (JsonNode p : state.path("players")) {
            if (p.path("captain").asBoolean()) {
                return p.path("id").asString();
            }
        }
        return null;
    }

    /** Every player fills in the intake; waits until the first round starts. */
    public void completeIntake() {
        screen.phase("INTAKE");
        for (Phone p : phones) {
            p.getSocket()
                    .ok(
                            "intake.submit",
                            Map.of("answers", List.of("I eat cereal with orange juice", "Karaoke", "Lost my shoe")));
        }
    }

    static void expect(ApiClient.Resp resp, int... statuses) {
        for (int s : statuses) {
            if (resp.status() == s) {
                return;
            }
        }
        throw new AssertionError("Unexpected HTTP " + resp.status() + ": " + resp.body());
    }

    @Override
    public void close() {
        screen.abort();
        phones.forEach(p -> p.getSocket().abort());
    }
}
