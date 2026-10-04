package com.example.seats;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Stateless HMAC-signed tokens: base64url(userId).base64url(HMAC-SHA256(userId)). */
@Component
public class Auth {
  private final byte[] secret;
  private final byte[] admin;

  public Auth(@Value("${app.token-secret}") String secret, @Value("${app.admin-token}") String admin) {
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.admin = admin.getBytes(StandardCharsets.UTF_8);
  }

  private String hmac(String s) {
    try {
      Mac m = Mac.getInstance("HmacSHA256");
      m.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(s.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  public String mint(String userId) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(userId.getBytes(StandardCharsets.UTF_8)) + "." + hmac(userId);
  }

  /** Returns the userId if the signature is valid, else null. */
  public String verify(String token) {
    try {
      int i = token.indexOf('.');
      if (i <= 0) return null;
      String uid = new String(Base64.getUrlDecoder().decode(token.substring(0, i)), StandardCharsets.UTF_8);
      boolean ok = MessageDigest.isEqual(hmac(uid).getBytes(StandardCharsets.UTF_8),
          token.substring(i + 1).getBytes(StandardCharsets.UTF_8));
      return ok ? uid : null;
    } catch (Exception e) { return null; }
  }

  public boolean isAdmin(String token) {
    return MessageDigest.isEqual(admin, token.getBytes(StandardCharsets.UTF_8));
  }
}
