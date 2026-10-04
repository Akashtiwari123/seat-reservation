package com.example.seats;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SeatService {
  public record Outcome(Map<String, Object> body, boolean replay) {}

  private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final MeterRegistry reg;
  private final Counter confirmed, cancelled;

  public SeatService(JdbcTemplate jdbc, PlatformTransactionManager tm, MeterRegistry reg) {
    this.jdbc = jdbc; this.tx = new TransactionTemplate(tm); this.reg = reg;
    this.confirmed = Counter.builder("reservations_confirmed").description("Reservations confirmed").register(reg);
    this.cancelled = Counter.builder("reservations_cancelled").description("Reservations cancelled").register(reg);
    for (String r : List.of("seat_taken", "per_user_limit", "idempotent_replay", "idempotency_conflict")) declined(r, 0);
  }

  private void declined(String reason, double n) {
    Counter c = Counter.builder("reservations_declined").description("Declined reservations by reason").tag("reason", reason).register(reg);
    if (n > 0) c.increment(n);
  }

  private static ApiException bad(String code, String msg) { return new ApiException(400, code, msg); }

  private static List<String> norm(List<String> in, int max) {
    if (in == null || in.isEmpty()) throw bad("seats_required", "seats must be a non-empty array");
    if (in.size() > max) throw bad("too_many_seats", "at most " + max + " seats per request");
    for (String s : in) if (s == null || !LABEL.matcher(s).matches()) throw bad("bad_seat_label", "seat labels must match [A-Za-z0-9_-]{1,32}");
    Set<String> set = new TreeSet<>(in); // sorted => deterministic lock order
    if (set.size() != in.size()) throw bad("duplicate_seats", "duplicate seats in request");
    return new ArrayList<>(set);
  }

  private <T> T withRetry(Supplier<T> s) {
    for (int i = 0; ; i++) {
      try { return s.get(); }
      catch (PessimisticLockingFailureException e) { if (i >= 3) throw e; } // deadlock victim / lock timeout: safe to retry, nothing committed
    }
  }

  // ---------------------------------------------------------------- create show
  public Map<String, Object> createShow(String name, List<String> seatsIn, Object price, Integer limit) {
    if (name == null || name.isBlank())
     throw bad("name_required", "name is required");

    if (!(price instanceof Integer || price instanceof Long) || ((Number) price).longValue() <= 0)
      throw bad("bad_price", "price_paise must be a positive integer (paise)");

    int lim = limit == null ? 4 : limit;
    if (lim < 1) throw bad("bad_limit", "per_user_limit must be >= 1");

    if (seatsIn == null || seatsIn.isEmpty() || seatsIn.size() > 100_000)
     throw bad("seats_required", "1..100000 seats required");
    
    for (String s : seatsIn) if (s == null || !LABEL.matcher(s).matches())
     throw bad("bad_seat_label", "seat labels must match [A-Za-z0-9_-]{1,32}");
    
    if (new HashSet<>(seatsIn).size() != seatsIn.size()){
      throw bad("duplicate_seats", "duplicate seats");
    } 
    
    String id = UUID.randomUUID().toString();
    tx.executeWithoutResult(st -> {
      jdbc.update("insert into shows(id,name,price_paise,per_user_limit) values (?,?,?,?)", id, name, ((Number) price).longValue(), lim);
      PreparedStatementCreator psc = con -> {
        PreparedStatement ps = con.prepareStatement(
            "insert into seats(show_id,label,ord,status) select ?, t.label, t.ord, 'available' from unnest(?::text[]) with ordinality as t(label, ord)");
        ps.setString(1, id);
        ps.setArray(2, con.createArrayOf("text", seatsIn.toArray()));
        return ps;
      };
      jdbc.update(psc);
    });
    return showState(id, true);
  }

  // ---------------------------------------------------------------- show state
  public Map<String, Object> showState(String id, boolean includeSeats) {
    List<Map<String, Object>> s = jdbc.queryForList("select name, price_paise, per_user_limit from shows where id=?", id);
    if (s.isEmpty()) throw new ApiException(404, "show_not_found", "no such show");
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("available", 0L); counts.put("held", 0L); counts.put("confirmed", 0L);
    List<Map<String, String>> seats = new ArrayList<>();
    // One statement == one snapshot, so counts always sum to total_seats.
    if (includeSeats) {
      jdbc.query("select label, status from seats where show_id=? order by ord", rs -> {
        String st = rs.getString(2);
        seats.add(Map.of("seat", rs.getString(1), "status", st));
        counts.merge(st, 1L, Long::sum);
      }, id);
    } else {
      jdbc.query("select status, count(*) from seats where show_id=? group by status", rs -> { counts.put(rs.getString(1), rs.getLong(2)); }, id);
    }
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id); m.put("name", s.get(0).get("name"));
    m.put("price_paise", s.get(0).get("price_paise")); m.put("per_user_limit", s.get(0).get("per_user_limit"));
    m.put("total_seats", total); m.put("counts", counts);
    if (includeSeats) m.put("seats", seats);
    return m;
  }

  // ---------------------------------------------------------------- reserve
  public Outcome reserve(String showId, String userId, List<String> seatsIn, String key) {
    List<String> seats = norm(seatsIn, 20);
    if (key == null || key.isBlank() || key.length() > 128) throw bad("idempotency_key_required", "idempotency_key (header or body) is required, max 128 chars");
    try {
      Outcome o = withRetry(() -> tx.execute(st -> doReserve(showId, userId, seats, key)));
      if (o.replay()) declined("idempotent_replay", 1); else confirmed.increment();
      return o;
    } catch (ApiException e) {
      if (e.status == 409) declined(e.code, 1);
      throw e;
    }
  }

  private Outcome doReserve(String showId, String userId, List<String> seats, String key) {
    // (1) Serialize this user's requests: makes idempotency lookup + per-user limit check race-free.
    jdbc.queryForList("select pg_advisory_xact_lock(hashtextextended(?, 0))", userId);

    // (2) Idempotency: same (user,key) => original reservation, or 409 if the request differs.
    String hash = showId + "|" + String.join(",", seats);
    List<Map<String, Object>> prior = jdbc.queryForList(
        "select id, show_id, seats, amount_paise, status, request_hash from reservations where user_id=? and idempotency_key=?", userId, key);
    if (!prior.isEmpty()) {
      Map<String, Object> p = prior.get(0);
      if (!hash.equals(p.get("request_hash")))
        throw new ApiException(409, "idempotency_conflict", "idempotency key already used with a different request");
      return new Outcome(body((String) p.get("id"), (String) p.get("show_id"), userId,
          Arrays.asList(((String) p.get("seats")).split(",")), ((Number) p.get("amount_paise")).longValue(), (String) p.get("status")), true);
    }

    List<Map<String, Object>> show = jdbc.queryForList("select price_paise, per_user_limit from shows where id=?", showId);
    if (show.isEmpty()) throw new ApiException(404, "show_not_found", "no such show");
    long price = ((Number) show.get(0).get("price_paise")).longValue();
    int limit = ((Number) show.get(0).get("per_user_limit")).intValue();

    // (3) Per-user limit (safe: we hold this user's advisory lock).
    Integer held = jdbc.queryForObject(
        "select count(*) from seats where show_id=? and user_id=? and status in ('held','confirmed')", Integer.class, showId, userId);
    if (held + seats.size() > limit)
      throw new ApiException(409, "per_user_limit", "per-user limit of " + limit + " seats exceeded");

    // (4) THE atomic decision: lock the requested seat rows in sorted order, then check state under the lock.
    PreparedStatementCreator lock = con -> {
      PreparedStatement ps = con.prepareStatement(
          "select label, status from seats where show_id=? and label = any(?) order by label for update");
      ps.setString(1, showId);
      ps.setArray(2, con.createArrayOf("text", seats.toArray()));
      return ps;
    };
    List<String[]> rows = jdbc.query(lock, (rs, i) -> new String[] {rs.getString(1), rs.getString(2)});
    if (rows.size() != seats.size()) throw new ApiException(404, "seat_not_found", "one or more seats do not exist in this show");
    for (String[] r : rows)
      if (!"available".equals(r[1])) throw new ApiException(409, "seat_taken", "seat " + r[0] + " is already taken"); // all-or-nothing

    String rid = UUID.randomUUID().toString();
    long amount = price * seats.size();
    jdbc.update("insert into reservations(id,show_id,user_id,idempotency_key,request_hash,seats,amount_paise,status) values (?,?,?,?,?,?,?,'confirmed')",
        rid, showId, userId, key, hash, String.join(",", seats), amount);
    PreparedStatementCreator upd = con -> {
      PreparedStatement ps = con.prepareStatement(
          "update seats set status='confirmed', user_id=?, reservation_id=? where show_id=? and label = any(?) and status='available'");
      ps.setString(1, userId); ps.setString(2, rid); ps.setString(3, showId);
      ps.setArray(4, con.createArrayOf("text", seats.toArray()));
      return ps;
    };
    if (jdbc.update(upd) != seats.size()) throw new IllegalStateException("seat state changed under row lock"); // defensive; unreachable
    return new Outcome(body(rid, showId, userId, seats, amount, "confirmed"), false);
  }

  // ---------------------------------------------------------------- cancel
  public Map<String, Object> cancel(String rid, String userId) {
    Map<String, Object> out = withRetry(() -> tx.execute(st -> {
      List<Map<String, Object>> r = jdbc.queryForList(
          "select id, show_id, user_id, seats, amount_paise, status from reservations where id=? for update", rid);
      if (r.isEmpty()) throw new ApiException(404, "reservation_not_found", "no such reservation");
      Map<String, Object> p = r.get(0);
      if (!userId.equals(p.get("user_id"))) throw new ApiException(403, "forbidden", "only the owner can cancel a reservation");
      boolean first = "confirmed".equals(p.get("status"));
      if (first) {
        // Guarded on reservation_id: can never touch a seat that now belongs to someone else.
        jdbc.update("update seats set status='available', user_id=null, reservation_id=null where reservation_id=? and status='confirmed'", rid);
        jdbc.update("update reservations set status='cancelled' where id=?", rid);
      }
      Map<String, Object> b = body(rid, (String) p.get("show_id"), userId, Arrays.asList(((String) p.get("seats")).split(",")),
          ((Number) p.get("amount_paise")).longValue(), "cancelled");
      b.put("_first", first);
      return b;
    }));
    if (Boolean.TRUE.equals(out.remove("_first"))) cancelled.increment();
    return out;
  }

  private static Map<String, Object> body(String rid, String showId, String uid, List<String> seats, long amount, String status) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("reservation_id", rid); m.put("show_id", showId); m.put("user_id", uid);
    m.put("seats", seats); m.put("amount_paise", amount); m.put("status", status);
    return m;
  }

  // ---------------------------------------------------------------- readiness
  public void pingDb() { jdbc.queryForObject("select 1", Integer.class); }
}
