package com.example.seats;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Correlation id + token-derived identity (never read from the body) + one structured access log line. */
@Component
public class RequestFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger("http");
  private final Auth auth;

  public RequestFilter(Auth auth) { this.auth = auth; }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String rid = req.getHeader("X-Request-Id");
    if (rid == null || rid.isBlank() || rid.length() > 64) rid = UUID.randomUUID().toString();
    MDC.put("request_id", rid);
    res.setHeader("X-Request-Id", rid);
    String h = req.getHeader("Authorization");
    if (h != null && h.startsWith("Bearer ")) {
      String t = h.substring(7).trim();
      if (auth.isAdmin(t)) req.setAttribute("admin", true);
      else {
        String uid = auth.verify(t);
        if (uid != null) { req.setAttribute("uid", uid); MDC.put("user_id", uid); }
      }
    }
    long t0 = System.nanoTime();
    try { chain.doFilter(req, res); }
    finally {
      MDC.put("status", String.valueOf(res.getStatus()));
      MDC.put("duration_ms", String.valueOf((System.nanoTime() - t0) / 1_000_000));
      log.info("{} {}", req.getMethod(), req.getRequestURI());
      MDC.clear();
    }
  }
}
