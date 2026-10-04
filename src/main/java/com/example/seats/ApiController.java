package com.example.seats;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ApiController {
  record CreateShow(String name, List<String> seats, Object pricePaise, Integer perUserLimit) {}
  record ReserveBody(List<String> seats, String idempotencyKey) {}

  private static final Pattern USER = Pattern.compile("^[A-Za-z0-9_.@-]{1,64}$");
  private final SeatService seatService;
  private final Auth auth;

  public ApiController(SeatService seatService, Auth auth) { this.seatService = seatService; this.auth = auth; }

  private static String user(HttpServletRequest r) {
    Object u = r.getAttribute("uid");
    if (u == null) throw new ApiException(401, "unauthorized", "valid user bearer token required");
    return (String) u;
  }

  /** Demo token issuer: anyone may mint a token for a user id. Identity thereafter comes only from the token. */
  @PostMapping("/auth/token")
  public Map<String, Object> token(@RequestBody Map<String, Object> body) {
    Object u = body.get("user_id");
    if (!(u instanceof String s) || !USER.matcher(s).matches())
      throw new ApiException(400, "bad_user_id", "user_id must match [A-Za-z0-9_.@-]{1,64}");
    return Map.of("user_id", s, "token", auth.mint(s));
  }

  @PostMapping("/shows")
  public ResponseEntity<Map<String, Object>> create(HttpServletRequest req, @RequestBody CreateShow b) {
    if (req.getAttribute("admin") == null)
      throw new ApiException(req.getHeader("Authorization") == null ? 401 : 403, "admin_required", "admin token required");
    
    return ResponseEntity.status(201).body(seatService.createShow(b.name(), b.seats(), b.pricePaise(), b.perUserLimit()));
  }

  @GetMapping("/shows/{id}")
  public Map<String, Object> show(@PathVariable String id, @RequestParam(name = "include_seats", defaultValue = "true") boolean includeSeats) {
    return seatService.showState(id, includeSeats);
  }

  @PostMapping("/shows/{id}/reserve")
  public ResponseEntity<Map<String, Object>> reserve(HttpServletRequest req, @PathVariable String id,
      @RequestHeader(name = "Idempotency-Key", required = false) String hdrKey, @RequestBody ReserveBody b) {
    String uid = user(req); // body fields like user_id are ignored by construction
    String key = hdrKey != null && !hdrKey.isBlank() ? hdrKey : b.idempotencyKey();
    SeatService.Outcome o = seatService.reserve(id, uid, b.seats(), key);
    
    return ResponseEntity.status(o.replay() ? 200 : 201).header("Idempotent-Replay", String.valueOf(o.replay())).body(o.body());
  }

  @PostMapping("/reservations/{id}/cancel")
  public Map<String, Object> cancel(HttpServletRequest req, @PathVariable String id) {
    return seatService.cancel(id, user(req));
  }

  @GetMapping("/healthz")
  public Map<String, String> live() { return Map.of("status", "ok"); }

  /** Readiness: really talks to the DB (2s budget) and fails closed with 503. */
  @GetMapping("/readyz")
  public ResponseEntity<Map<String, String>> ready() {
    try {
      CompletableFuture.runAsync(seatService::pingDb).get(2, TimeUnit.SECONDS);
      return ResponseEntity.ok(Map.of("status", "ready"));
    } catch (Exception e) {
      return ResponseEntity.status(503).body(Map.of("status", "not_ready", "reason", "database unreachable"));
    }
  }
}
