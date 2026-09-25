package com.insidejoke.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Development transport enabled with app.mail.provider=log: writes sign-in emails, codes included, to the log so a
 * local run works without a mail account. Allowed only with the "local" profile; anywhere else the server refuses to
 * start rather than print working sign-in codes to production logs.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "log")
public class LogMailClient implements MailClient {

    private static final Logger log = LoggerFactory.getLogger(LogMailClient.class);

    public LogMailClient(Environment environment) {
        if (!environment.acceptsProfiles(Profiles.of("local"))) {
            throw new IllegalStateException(
                    "MAIL_PROVIDER=log writes sign-in codes to the log and is allowed only with the "
                            + "local profile (SPRING_PROFILES_ACTIVE=local); use resend or smtp");
        }
    }

    @Override
    public void send(EmailMessage message) {
        log.info("Development email to {}: {}\n{}", message.to(), message.subject(), message.text());
    }
}
