package com.zalo.server.util;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** Băm mật khẩu PBKDF2-HMAC-SHA256 (có sẵn trong JDK, không cần thư viện ngoài). Định dạng: pbkdf2$iter$salt$hash. */
public final class PasswordHasher {
    private static final int ITER = 65_536, KEY_BITS = 256;
    private static final SecureRandom RNG = new SecureRandom();

    private PasswordHasher() {}

    public static String hash(String password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] h = pbkdf2(password.toCharArray(), salt, ITER);
        return "pbkdf2$" + ITER + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(h);
    }

    public static boolean verify(String password, String stored) {
        try {
            String[] p = stored.split("\\$");
            int iter = Integer.parseInt(p[1]);
            byte[] salt = Base64.getDecoder().decode(p[2]);
            byte[] expected = Base64.getDecoder().decode(p[3]);
            return MessageDigest.isEqual(expected, pbkdf2(password.toCharArray(), salt, iter));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] pw, byte[] salt, int iter) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(new PBEKeySpec(pw, salt, iter, KEY_BITS)).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
