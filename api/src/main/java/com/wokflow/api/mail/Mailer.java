package com.wokflow.api.mail;

import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends transactional email over SMTP (Mailpit locally, SES SMTP in AWS). Without a configured
 * SMTP host it only logs that a message would have been sent, never its content (it may hold a
 * reset link).
 */
@Component
public class Mailer {
    private static final Logger log = LoggerFactory.getLogger(Mailer.class);

    private final JavaMailSender sender;
    private final String from;

    public Mailer(ObjectProvider<JavaMailSender> sender, org.springframework.core.env.Environment env) {
        this.sender = sender.getIfAvailable();
        this.from = env.getProperty("wokflow.mail.from", "Wokflow <no-reply@wokflow.local>");
    }

    public void send(String to, String subject, String text) {
        if (sender == null) {
            log.info("No SMTP server configured: not sending \"{}\" to {}", subject, mask(to));
            return;
        }
        try {
            var msg = sender.createMimeMessage();
            var h = new MimeMessageHelper(msg, false, StandardCharsets.UTF_8.name());
            h.setFrom(new InternetAddress(from));
            h.setTo(to); // a single, validated address: no header injection through the recipient
            h.setSubject(subject.replaceAll("[\\r\\n]", " "));
            h.setText(text);
            sender.send(msg);
        } catch (Exception e) {
            log.warn("Could not send \"{}\" to {}: {}", subject, mask(to), e.toString());
        }
    }

    static String mask(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
