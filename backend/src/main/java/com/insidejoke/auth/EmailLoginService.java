package com.insidejoke.auth;

import com.insidejoke.common.ApiException;
import com.insidejoke.common.AppProperties;
import com.insidejoke.common.ErrorCode;
import com.insidejoke.common.Language;
import com.insidejoke.common.TokenUtils;
import com.insidejoke.mail.EmailMessage;
import com.insidejoke.mail.MailClient;
import com.insidejoke.web.RateLimitService;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Passwordless email sign-in: one email carries both a 6-digit code (for signing in on another device)
 * and a one-time link. Both expire after 15 minutes; 5 wrong codes lock the challenge.
 */
@Service
public class EmailLoginService {

    static final Duration TTL = Duration.ofMinutes(15);
    static final int MAX_ATTEMPTS = 5;
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,190}\\.[^\\s@]{2,63}$");
    private static final Pattern CODE = Pattern.compile("^\\d{6}$");
    private static final Logger log = LoggerFactory.getLogger(EmailLoginService.class);

    private final MagicLinkRepository challenges;
    private final UserService users;
    private final MailClient mail;
    private final RateLimitService limits;
    private final AppProperties props;
    private final Clock clock;

    public EmailLoginService(
            MagicLinkRepository challenges,
            UserService users,
            MailClient mail,
            RateLimitService limits,
            AppProperties props,
            Clock clock) {
        this.challenges = challenges;
        this.users = users;
        this.mail = mail;
        this.limits = limits;
        this.props = props;
        this.clock = clock;
    }

    public static boolean isValidEmail(String email) {
        return email != null
                && email.length() <= 254
                && EMAIL.matcher(email.trim()).matches();
    }

    public void requestCode(String rawEmail, String ip) {
        requestCode(rawEmail, ip, Language.EN);
    }

    /** Sends the code in the language the person is using the site in. */
    public void requestCode(String rawEmail, String ip, Language language) {
        if (!isValidEmail(rawEmail)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Enter a valid email address.",
                    Map.of("fields", Map.of("email", "invalid")));
        }
        String email = UserService.normalizeEmail(rawEmail);
        limits.check(RateLimitService.MAGIC_LINK_PER_IP, ip);
        limits.check(RateLimitService.MAGIC_LINK_PER_EMAIL, email);

        String token = TokenUtils.random(32);
        String code = TokenUtils.digits(6);
        byte[] tokenHash = TokenUtils.sha256(token);
        Instant now = clock.instant();
        challenges.supersede(email, now);
        challenges.insert(tokenHash, email, codeHash(tokenHash, code), now.plus(TTL), now, ip);

        String link = props.publicUrl() + "/auth/callback?token=" + token;
        try {
            mail.send(compose(email, code, link, language));
        } catch (MailClient.MailSendException e) {
            log.warn("Sign-in email could not be sent: {}", e.getMessage());
            challenges.consume(tokenHash, now);
            throw new ApiException(
                    ErrorCode.EMAIL_SEND_FAILED, "We couldn't send the email. Continue with Google instead.");
        }
    }

    public UserEntity verifyCode(String rawEmail, String code, String ip) {
        limits.check(RateLimitService.CODE_VERIFY_PER_IP, ip);
        if (!isValidEmail(rawEmail) || code == null || !CODE.matcher(code).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Enter the 6-digit code from the email.");
        }
        String email = UserService.normalizeEmail(rawEmail);
        MagicLinkRepository.Challenge ch = challenges
                .findLatestUnused(email)
                .orElseThrow(() -> new ApiException(ErrorCode.CODE_EXPIRED, "That code has expired. Send a new one."));
        Instant now = clock.instant();
        if (!now.isBefore(ch.expiresAt())) {
            throw new ApiException(ErrorCode.CODE_EXPIRED, "That code has expired. Send a new one.");
        }
        if (ch.attempts() >= MAX_ATTEMPTS) {
            throw new ApiException(ErrorCode.CODE_LOCKED);
        }
        if (!TokenUtils.constantTimeEquals(ch.codeHash(), codeHash(ch.tokenHash(), code))) {
            int attempts = challenges.incrementAttempts(ch.tokenHash());
            int left = Math.max(0, MAX_ATTEMPTS - attempts);
            if (left == 0) {
                throw new ApiException(ErrorCode.CODE_LOCKED);
            }
            throw new ApiException(ErrorCode.CODE_INVALID, "That code doesn't match.", Map.of("attemptsLeft", left));
        }
        if (!challenges.consume(ch.tokenHash(), now)) {
            throw new ApiException(ErrorCode.CODE_EXPIRED, "That code has already been used. Send a new one.");
        }
        return users.loginWithEmail(email);
    }

    public UserEntity verifyLink(String token) {
        if (token == null || token.length() < 20 || token.length() > 100) {
            throw new ApiException(ErrorCode.LINK_INVALID);
        }
        byte[] tokenHash = TokenUtils.sha256(token);
        Instant now = clock.instant();
        MagicLinkRepository.Challenge ch = challenges
                .findUnusedByToken(tokenHash)
                .filter(c -> now.isBefore(c.expiresAt()) && c.attempts() < MAX_ATTEMPTS)
                .orElseThrow(() -> new ApiException(ErrorCode.LINK_INVALID));
        if (!challenges.consume(tokenHash, now)) {
            throw new ApiException(ErrorCode.LINK_INVALID);
        }
        return users.loginWithEmail(ch.email());
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void purgeOld() {
        challenges.deleteOlderThan(clock.instant().minus(Duration.ofDays(1)));
    }

    static byte[] codeHash(byte[] tokenHash, String code) {
        return TokenUtils.sha256(tokenHash, code.getBytes(StandardCharsets.UTF_8));
    }

    private record Copy(String subject, String heading, String intro, String orLink, String expiry, String button) {}

    private static Copy copy(Language language) {
        return language == Language.RU
                ? new Copy(
                        "Ваш код Inside Joke: ",
                        "Ваш код для входа в Inside Joke:",
                        "Ваш код для входа в Inside Joke: ",
                        "Или откройте эту ссылку на устройстве, где вы собираете вечеринку:",
                        "Код и ссылка действуют 15 минут. Если вы не запрашивали вход, просто проигнорируйте это письмо.",
                        "Войти на этом устройстве")
                : new Copy(
                        "Your Inside Joke code: ",
                        "Your Inside Joke sign-in code:",
                        "Your Inside Joke sign-in code is ",
                        "Or open this link on the device where you're setting up the party:",
                        "The code and the link work for 15 minutes. If you didn't ask for this, ignore this email.",
                        "Sign in on this device");
    }

    private EmailMessage compose(String email, String code, String link, Language language) {
        Copy c = copy(language);
        String spaced = code.substring(0, 3) + " " + code.substring(3);
        String text = c.intro() + spaced + (language == Language.RU ? "\n\n" : ".\n\n") + c.orLink() + "\n" + link
                + "\n\n" + c.expiry();
        String html = "<div style=\"font-family:Arial,sans-serif;font-size:16px;color:#231942\">"
                + "<p>" + c.heading() + "</p>"
                + "<p style=\"font-size:36px;font-weight:bold;letter-spacing:6px\">" + spaced + "</p>"
                + "<p><a href=\"" + link + "\" style=\"background:#f5e663;color:#231942;padding:12px 20px;"
                + "border-radius:24px;text-decoration:none;font-weight:bold\">" + c.button() + "</a></p>"
                + "<p style=\"color:#6b5c8a;font-size:14px\">" + c.expiry() + "</p></div>";
        return new EmailMessage(email, c.subject() + spaced, text, html);
    }
}
