package com.insidejoke.support;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stand-in for the Anthropic Messages API and the speech endpoint. Replies are built from the real request,
 * so they reference the actual player and fact ids. Any text containing "BANNED" fails moderation.
 */
public final class FakeAi {

    public enum Mode {
        OK,
        ERROR,
        INVALID
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern COUNT = Pattern.compile("exactly (\\d+) fill-in prompts");
    private static final Pattern DATA = Pattern.compile("<player_data>\\s*(.*?)\\s*</player_data>", Pattern.DOTALL);
    public static final byte[] MP3 = "ID3-fake-mp3-bytes".getBytes(StandardCharsets.US_ASCII);

    private FakeAi() {}

    public static void install(FakeHttp fake, Mode mode) {
        install(fake, mode, 0);
    }

    /** Like {@link #install(FakeHttp, Mode)}, with every model reply held back by {@code delayMs}. */
    public static void install(FakeHttp fake, Mode mode, long delayMs) {
        fake.on("POST", "/anthropic/v1/messages", req -> reply(mode, req).delayed(delayMs));
        fake.on("POST", "/tts/v1/audio/speech", req -> FakeHttp.Reply.bytes(200, "audio/mpeg", MP3));
    }

    public static void install(FakeHttp fake) {
        install(fake, Mode.OK);
    }

    private static FakeHttp.Reply reply(Mode mode, FakeHttp.Recorded req) {
        return switch (mode) {
            case ERROR -> FakeHttp.Reply.json(529, "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}");
            case INVALID -> message("Sorry, I can't produce JSON today.");
            case OK -> message(answer(req.body()));
        };
    }

    private static FakeHttp.Reply message(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", "msg_test");
        body.put("type", "message");
        body.put("model", "claude-haiku-4-5");
        body.put("content", List.of(Map.of("type", "text", "text", text)));
        body.put("usage", Map.of("input_tokens", 400, "output_tokens", 120));
        return FakeHttp.Reply.json(200, JSON.writeValueAsString(body));
    }

    static String answer(String requestBody) {
        JsonNode request = JSON.readTree(requestBody);
        String system = request.path("system").asString();
        String user = request.path("messages").get(0).path("content").asString();
        Matcher m = DATA.matcher(user);
        JsonNode data = m.find() ? JSON.readTree(m.group(1)) : JSON.createObjectNode();
        if (system.contains("content for one upcoming round")) {
            return round(system, data);
        }
        if (system.contains("Safety check")) {
            List<Map<String, Object>> duels = new ArrayList<>();
            for (JsonNode d : data.path("duels")) {
                duels.add(Map.of(
                        "id",
                        d.path("id").asString(),
                        "blockA",
                        d.path("answerA").asString().contains("BANNED"),
                        "blockB",
                        d.path("answerB").asString().contains("BANNED"),
                        "reaction",
                        "AI reaction to " + d.path("id").asString()));
            }
            return JSON.writeValueAsString(Map.of("duels", duels));
        }
        if (system.contains("personal award title")) {
            Map<String, String> titles = new LinkedHashMap<>();
            for (JsonNode p : data.path("players")) {
                titles.put(
                        p.path("id").asString(),
                        "AI title for " + p.path("name").asString());
            }
            return JSON.writeValueAsString(Map.of("titles", titles, "speech", "AI closing speech."));
        }
        if (system.contains("You check texts")) {
            List<Map<String, Object>> results = new ArrayList<>();
            for (JsonNode t : data.path("texts")) {
                boolean banned = t.asString().contains("BANNED");
                results.add(Map.of("allowed", !banned, "category", banned ? "harmful_secret" : "ok"));
            }
            return JSON.writeValueAsString(Map.of("results", results));
        }
        return "{}";
    }

    private static String round(String system, JsonNode data) {
        Matcher m = COUNT.matcher(system);
        int count = m.find() ? Integer.parseInt(m.group(1)) : 3;
        List<JsonNode> players = new ArrayList<>();
        data.path("players").forEach(players::add);
        List<Map<String, Object>> prompts = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            JsonNode about = players.get(i % players.size());
            Map<String, Object> p = new LinkedHashMap<>();
            p.put(
                    "text",
                    "AI prompt " + (i + 1) + " for round " + data.path("round").asString());
            p.put("aboutPlayerId", i % 2 == 0 ? about.path("id").asString() : null);
            prompts.add(p);
        }
        JsonNode fact =
                data.path("dossier").isEmpty() ? null : data.path("dossier").get(0);
        String subject = fact != null
                ? fact.path("aboutPlayerId").asString()
                : players.getFirst().path("id").asString();
        Map<String, Object> truth = new LinkedHashMap<>();
        truth.put("aboutPlayerId", subject);
        truth.put("factId", fact == null ? null : fact.path("id").asString());
        truth.put("truthStatement", fact == null ? null : "TRUE STATEMENT from the dossier");
        truth.put("fakeStatement", "FAKE STATEMENT invented by the AI");
        truth.put("truthLine", "AI truth line");
        truth.put("fakeLine", "AI fake line");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("duelPrompts", prompts);
        out.put("whoOfUs", "Who of us would win an AI quiz?");
        out.put("truth", truth);
        return JSON.writeValueAsString(out);
    }
}
