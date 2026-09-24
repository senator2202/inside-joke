package com.insidejoke.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Development transport enabled with app.mail.provider=log (the "dev" profile): writes sign-in emails
 * to the log so a local run works without a Resend account. Never enable in production.
 */
@Component
@ConditionalOnProperty(name = "app.mail.provider", havingValue = "log")
public class LogMailClient implements MailClient {

    private static final Logger log = LoggerFactory.getLogger(LogMailClient.class);

    @Override
    public void send(EmailMessage message) {
        log.info("Development email to {}: {}\n{}", message.to(), message.subject(), message.text());
    }
}
