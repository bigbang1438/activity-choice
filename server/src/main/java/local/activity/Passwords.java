package local.activity;

import java.security.*;
import java.util.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class Passwords {
    private static final SecureRandom RANDOM=new SecureRandom();
    private static final int ITERATIONS=600_000;
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();
    static String hash(String password) {
        byte[] salt=new byte[24]; RANDOM.nextBytes(salt);
        return ITERATIONS+":"+ENCODER.encodeToString(salt)+":"+ENCODER.encodeToString(derive(password,salt,ITERATIONS));
    }
    static boolean matches(String password,String encoded) {
        try {
            String[] parts=encoded.split(":");
            byte[] expected=Base64.getUrlDecoder().decode(parts[2]);
            byte[] actual=derive(password,Base64.getUrlDecoder().decode(parts[1]),Integer.parseInt(parts[0]));
            return MessageDigest.isEqual(expected,actual);
        } catch (RuntimeException e) { return false; }
    }
    private static byte[] derive(String password,byte[] salt,int rounds) {
        PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt,rounds,256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException("Password hash unavailable",e); }
        finally { spec.clearPassword(); }
    }
}
