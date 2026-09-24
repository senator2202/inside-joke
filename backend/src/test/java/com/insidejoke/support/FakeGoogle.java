package com.insidejoke.support;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Google OpenID provider on the fake server: signs real RS256 id_tokens and serves JWKS and userinfo. */
public final class FakeGoogle {

    public record Identity(String sub, String email, boolean emailVerified, String name) {}

    private static final RSAKey KEY = generate();
    private final Map<String, Identity> byCode = new ConcurrentHashMap<>();
    private final Map<String, String> nonceByCode = new ConcurrentHashMap<>();
    private final Map<String, Identity> byAccessToken = new ConcurrentHashMap<>();

    public FakeGoogle install(FakeHttp fake) {
        fake.on("GET", "/google/jwks", r -> FakeHttp.Reply.json(200, new JWKSet(KEY.toPublicJWK()).toString()));
        fake.on("POST", "/google/token", r -> {
            Map<String, String> form = parseQuery(r.body());
            String code = form.get("code");
            Identity id = byCode.get(code);
            if (id == null) {
                return FakeHttp.Reply.json(400, "{\"error\":\"invalid_grant\"}");
            }
            String accessToken = "at-" + code;
            byAccessToken.put(accessToken, id);
            String idToken = sign(fake.baseUrl() + "/google", id, nonceByCode.get(code));
            return FakeHttp.Reply.json(
                    200,
                    "{\"access_token\":\"" + accessToken + "\",\"token_type\":\"Bearer\","
                            + "\"expires_in\":3600,\"scope\":\"openid email profile\",\"id_token\":\"" + idToken
                            + "\"}");
        });
        fake.on("GET", "/google/userinfo", r -> {
            String auth = r.header("Authorization");
            Identity id = auth == null ? null : byAccessToken.get(auth.substring("Bearer ".length()));
            if (id == null) {
                return FakeHttp.Reply.json(401, "{}");
            }
            return FakeHttp.Reply.json(
                    200,
                    "{\"sub\":\"" + id.sub() + "\",\"email\":\"" + id.email() + "\",\"email_verified\":"
                            + id.emailVerified() + ",\"name\":\"" + id.name() + "\"}");
        });
        return this;
    }

    /**
     * Runs the browser part of the authorization code flow: starts login, lets the user "consent"
     * as {@code identity}, and returns the app's response to the redirect back.
     */
    public ApiClient.Resp login(ApiClient client, Identity identity) {
        ApiClient.Resp start = client.get("/oauth2/authorization/google");
        if (start.status() != 302) {
            throw new IllegalStateException("Expected redirect to Google, got " + start.status());
        }
        Map<String, String> params =
                parseQuery(URI.create(start.location().orElseThrow()).getRawQuery());
        String code = "code-" + UUID.randomUUID();
        byCode.put(code, identity);
        nonceByCode.put(code, params.get("nonce"));
        return client.get("/login/oauth2/code/google?code=" + code + "&state="
                + URLEncoder.encode(params.get("state"), StandardCharsets.UTF_8));
    }

    private static String sign(String issuer, Identity id, String nonce) {
        Date now = new Date();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(id.sub())
                .audience("test-google-client")
                .issueTime(now)
                .expirationTime(new Date(now.getTime() + 3_600_000))
                .claim("email", id.email())
                .claim("email_verified", id.emailVerified())
                .claim("name", id.name())
                .claim("nonce", nonce)
                .build();
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(KEY.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generate() {
        try {
            return new RSAKeyGenerator(2048).keyID("test-key").generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null) {
            return out;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(
                        URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }
}
