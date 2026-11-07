package com.gdgoc.dashboard.letter;

import com.gdgoc.dashboard.common.ForbiddenException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Service
public class SignedUrlService {

    private static final String HMAC_ALGO = "HmacSHA256";
    private final byte[] signingSecret;

    public SignedUrlService(@Value("${app.storage.signing-secret:gdgoc-signed-url-hmac-secret-key-2026-2027}") String secret) {
        this.signingSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public record VerifiedTokenPayload(Long letterId, Long memberId, Instant expiresAt) {}

    public String generateSignedToken(Long letterId, Long memberId, Instant expiresAt) {
        String payload = letterId + ":" + memberId + ":" + expiresAt.toEpochMilli();
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = computeHmacBase64Url(payload);
        return encodedPayload + "." + signature;
    }

    public VerifiedTokenPayload verifyAndParseToken(String token) {
        if (token == null || !token.contains(".")) {
            throw new ForbiddenException("Invalid signed download token");
        }
        String[] parts = token.split("\\.", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new ForbiddenException("Malformed signed download token");
        }
        String payload;
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(parts[0]);
            payload = new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ForbiddenException("Malformed base64 payload in signed token");
        }

        String expectedSig = computeHmacBase64Url(payload);
        if (!MessageDigest.isEqual(
                expectedSig.getBytes(StandardCharsets.UTF_8),
                parts[1].getBytes(StandardCharsets.UTF_8))) {
            throw new ForbiddenException("Invalid HMAC signature on signed download token");
        }

        String[] fields = payload.split(":");
        if (fields.length != 3) {
            throw new ForbiddenException("Invalid payload fields in signed download token");
        }
        try {
            Long letterId = Long.parseLong(fields[0]);
            Long memberId = Long.parseLong(fields[1]);
            long expiresAtMillis = Long.parseLong(fields[2]);
            Instant expiresAt = Instant.ofEpochMilli(expiresAtMillis);
            if (Instant.now().isAfter(expiresAt)) {
                throw new ForbiddenException("Signed download URL has expired");
            }
            return new VerifiedTokenPayload(letterId, memberId, expiresAt);
        } catch (NumberFormatException e) {
            throw new ForbiddenException("Corrupted numeric fields in signed token");
        }
    }

    private String computeHmacBase64Url(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(signingSecret, HMAC_ALGO));
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC-SHA256", e);
        }
    }
}
