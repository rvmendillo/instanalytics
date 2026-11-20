package com.rvmendillo.igstats.auth;

import com.rvmendillo.igstats.config.IgProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM for tokens at rest.
 *
 * <p>A long-lived token is full account access for 60 days. If the database
 * leaks, the tokens should not be usable from it. Key comes from IG_TOKEN_KEY;
 * generate one with {@code openssl rand -base64 32}.
 */
@Component
public class TokenCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public TokenCipher(IgProperties props) {
        if (props.tokenKey() == null || props.tokenKey().isBlank()) {
            throw new IllegalStateException(
                    "IG_TOKEN_KEY is not set. Generate one: openssl rand -base64 32");
        }
        byte[] raw = Base64.getDecoder().decode(props.tokenKey());
        if (raw.length != 32) {
            throw new IllegalStateException("IG_TOKEN_KEY must decode to 32 bytes, got " + raw.length);
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("token encryption failed", e);
        }
    }

    public String decrypt(String encoded) {
        try {
            ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(encoded));
            byte[] iv = new byte[IV_BYTES];
            buf.get(iv);
            byte[] ct = new byte[buf.remaining()];
            buf.get(ct);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("token decryption failed - wrong IG_TOKEN_KEY?", e);
        }
    }
}
