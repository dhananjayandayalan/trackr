package dev.trakr.api.identity.internal;

import dev.trakr.api.identity.WeakPasswordException;
import dev.trakr.api.shared.config.TrakrProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Forgot password → emailed one-time link → new password → signed out everywhere (09-security §1). */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final AppUserRepository users;
    private final PasswordResetRepository resets;
    private final PasswordPolicy passwordPolicy;
    private final PasswordEncoder passwordEncoder;
    private final JavaMailSender mailSender;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;
    private final TrakrProperties properties;
    private final Clock clock;
    private final String emailTemplate;

    PasswordResetService(AppUserRepository users, PasswordResetRepository resets, PasswordPolicy passwordPolicy,
            PasswordEncoder passwordEncoder, JavaMailSender mailSender,
            FindByIndexNameSessionRepository<? extends Session> sessions, TrakrProperties properties, Clock clock,
            @Value("classpath:mail/password-reset.txt") Resource emailTemplate) {
        this.users = users;
        this.resets = resets;
        this.passwordPolicy = passwordPolicy;
        this.passwordEncoder = passwordEncoder;
        this.mailSender = mailSender;
        this.sessions = sessions;
        this.properties = properties;
        this.clock = clock;
        try {
            this.emailTemplate = emailTemplate.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Emails a reset link if the address has an account. The caller always answers 202, so nobody learns which emails exist. */
    @Transactional
    public void requestReset(String email) {
        Optional<AppUser> user = users.findByEmail(email);
        if (user.isEmpty()) {
            return;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        resets.save(PasswordReset.issue(sha256(token), user.get().id(), clock.instant().plus(TOKEN_LIFETIME)));
        send(user.get(), token);
    }

    @Transactional
    public void reset(String token, String newPassword) {
        PasswordReset reset = resets.findById(sha256(token))
                .filter(candidate -> candidate.isUsable(clock.instant()))
                .orElseThrow(InvalidResetTokenException::new);
        List<String> violations = passwordPolicy.violations(newPassword);
        if (!violations.isEmpty()) {
            throw new WeakPasswordException(violations);
        }
        AppUser user = users.findById(reset.userId()).orElseThrow(InvalidResetTokenException::new);
        // dirty checking: both entities are managed, so the UPDATEs run at commit without a save()
        user.changePassword(passwordEncoder.encode(newPassword));
        reset.markUsed(clock.instant());

        // whoever knew the old password may hold a session: end every session of this user
        sessions.findByPrincipalName(user.id().toString()).keySet().forEach(sessions::deleteById);
        log.info("password reset userId={}", user.id());
    }

    private void send(AppUser user, String token) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.mail().from());
        message.setTo(user.email());
        message.setSubject("Reset your TRAKR password");
        message.setText(emailTemplate.formatted(user.displayName(),
                properties.app().origin().resolve("/reset-password?token=" + token)));
        try {
            mailSender.send(message);
        } catch (MailException e) {
            // still 202: a failure here must look the same as "no such email"
            log.warn("password reset email failed userId={}", user.id(), e);
        }
    }

    private static String sha256(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("every JVM ships SHA-256", e);
        }
    }
}
