import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;
import java.util.stream.*;

/** On-sale stampede. Usage: java scripts/Burst.java <BASE_URL>   (env ADMIN_TOKEN, default "admin-token"; JDK 17+) */
public class Burst {
  record Res(int status, String body) {
    String field(String k) { Matcher m = Pattern.compile("\"" + k + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body); return m.find() ? m.group(1) : null; }
    long num(String k) { Matcher m = Pattern.compile("\"" + k + "\"\\s*:\\s*(\\d+)").matcher(body); return m.find() ? Long.parseLong(m.group(1)) : -1; }
    String label() {
      if (status == 201) return "201 confirmed";
      if (status == 200) return "200 idempotent-replay";
      if (status >= 500) return status + " SERVER-ERROR (" + field("error") + ")";
      return status + " declined: " + field("error");
    }
  }

  static HttpClient http;
  static String base;
  static final Map<String, LongAdder> dist = new ConcurrentHashMap<>();
  static final List<String> failures = Collections.synchronizedList(new ArrayList<>());
  static final Map<String, String> tokens = new ConcurrentHashMap<>();
  static String show;

  static Res call(String method, String path, String token, String json) {
    try {
      var b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120)).header("Content-Type", "application/json");
      if (token != null) b.header("Authorization", "Bearer " + token);
      b.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
      var r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
      return new Res(r.statusCode(), r.body());
    } catch (Exception e) { return new Res(599, "{\"error\":\"client_" + e.getClass().getSimpleName() + "\"}"); }
  }

  static Res reserve(String user, String key, String... seats) {
    String body = "{\"seats\":[" + Arrays.stream(seats).map(s -> "\"" + s + "\"").collect(Collectors.joining(",")) + "],\"idempotency_key\":\"" + key + "\"}";
    Res r = call("POST", "/shows/" + show + "/reserve", tokens.get(user), body);
    dist.computeIfAbsent(r.label(), k -> new LongAdder()).increment();
    return r;
  }

  static void check(boolean ok, String what) { if (!ok) failures.add(what); }

  public static void main(String[] args) throws Exception {
    base = args[0].replaceAll("/+$", "");
    String admin = System.getenv().getOrDefault("ADMIN_TOKEN", "admin-token");
    http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(30)).build();
    ExecutorService ex = Executors.newFixedThreadPool(Integer.getInteger("threads", 600));
    ExecutorService ex2 = Executors.newCachedThreadPool(); // nested parallel retries (separate pool avoids starvation)
    final int N = 3000, HOT = 5, PER_HOT = 500, SPREAD = 15000, RETRY = 500, MISMATCH = 100;

    // --- create show
    String seatsJson = IntStream.rangeClosed(1, N).mapToObj(i -> "\"S" + i + "\"").collect(Collectors.joining(","));
    Res created = call("POST", "/shows", admin, "{\"name\":\"burst-" + System.currentTimeMillis() + "\",\"seats\":[" + seatsJson + "],\"price_paise\":25000,\"per_user_limit\":4}");
    if (created.status() != 201) { System.err.println("cannot create show: " + created.status() + " " + created.body()); System.exit(2); }
    show = created.field("id");
    System.out.println("show " + show + " with " + N + " seats");

    // --- plan jobs (seat pools are disjoint so expectations are exact)
    List<String> users = new ArrayList<>();
    List<Runnable> jobs = new ArrayList<>();
    Map<String, AtomicInteger> hotWins = new ConcurrentHashMap<>();
    Random rnd = new Random();
    for (int h = 1; h <= HOT; h++) {
      final String seat = "S" + h; hotWins.put(seat, new AtomicInteger());
      for (int i = 0; i < PER_HOT; i++) {
        final String u = "hot" + h + "_" + i; users.add(u);
        jobs.add(() -> { if (reserve(u, "k-" + u, seat).status() == 201) hotWins.get(seat).incrementAndGet(); });
      }
    }
    for (int i = 0; i < SPREAD; i++) { // S6..S2000, heavy collisions
      final String u = "sp" + i, seat = "S" + (HOT + 1 + rnd.nextInt(2000 - HOT)); users.add(u);
      jobs.add(() -> reserve(u, "k-" + u, seat));
    }
    AtomicInteger dupWins = new AtomicInteger();
    for (int i = 0; i < RETRY; i++) { // same key, same seat, fired twice in parallel
      final String u = "rt" + i, seat = "S" + (2001 + i); users.add(u);
      jobs.add(() -> {
        Future<Res> f = ex2.submit(() -> reserve(u, "k-" + u, seat));
        Res a = reserve(u, "k-" + u, seat);
        Res b; try { b = f.get(); } catch (Exception e) { b = new Res(599, "{}"); }
        if (a.status() == 201 && b.status() == 201) dupWins.incrementAndGet();
        check(a.status() < 500 && b.status() < 500, "retry pair got 5xx");
      });
    }
    for (int i = 0; i < MISMATCH; i++) { // same key, different seats => 409 idempotency_conflict
      final String u = "mm" + i, A = "S" + (2501 + 2 * i), B = "S" + (2502 + 2 * i); users.add(u);
      jobs.add(() -> {
        Res r1 = reserve(u, "same-key", A), r2 = reserve(u, "same-key", B);
        check(r1.status() == 201 && r2.status() == 409 && "idempotency_conflict".equals(r2.field("error")), "same-key-different-seats not rejected for " + u);
      });
    }
    users.add("lim"); AtomicInteger limWins = new AtomicInteger();
    for (int j = 0; j < 10; j++) { // one user, 10 parallel reserves, limit 4
      final int jj = j;
      jobs.add(() -> { if (reserve("lim", "lim-" + jj, "S" + (2801 + jj)).status() == 201) limWins.incrementAndGet(); });
    }
    users.addAll(List.of("alice", "victim", "bob"));

    // --- mint tokens
    System.out.println("minting " + users.size() + " tokens...");
    List<Future<?>> mint = new ArrayList<>();
    for (String u : users) mint.add(ex.submit(() -> { Res r = call("POST", "/auth/token", null, "{\"user_id\":\"" + u + "\"}"); tokens.put(u, r.field("token")); }));
    for (Future<?> f : mint) f.get();

    // --- invariant sampler during the burst
    AtomicBoolean running = new AtomicBoolean(true); AtomicInteger samples = new AtomicInteger();
    Thread sampler = new Thread(() -> {
      while (running.get()) {
        Res r = call("GET", "/shows/" + show + "?include_seats=false", null, null);
        if (r.status() == 200) {
          samples.incrementAndGet();
          check(r.num("available") + r.num("held") + r.num("confirmed") == N, "INVARIANT BROKEN mid-burst: " + r.body());
        } else if (r.status() >= 500) check(false, "5xx on GET show mid-burst");
        try { Thread.sleep(150); } catch (InterruptedException e) { return; }
      }
    });
    sampler.start();

    // --- fire
    Collections.shuffle(jobs);
    System.out.println("firing " + jobs.size() + " jobs (+ retry duplicates)...");
    long t0 = System.nanoTime();
    List<Future<?>> fs = new ArrayList<>();
    for (Runnable r : jobs) fs.add(ex.submit(r));
    for (Future<?> f : fs) f.get();
    double secs = (System.nanoTime() - t0) / 1e9;
    running.set(false); sampler.join();

    // --- identity spoofing + cancel + re-book
    String body = "{\"seats\":[\"S2811\"],\"idempotency_key\":\"sp1\",\"user_id\":\"victim\"}";
    Res a = call("POST", "/shows/" + show + "/reserve", tokens.get("alice"), body);
    dist.computeIfAbsent(a.label(), k -> new LongAdder()).increment();
    check(a.status() == 201 && "alice".equals(a.field("user_id")), "spoofed user_id was honoured: " + a.body());
    String rid = a.field("reservation_id");
    check(call("POST", "/reservations/" + rid + "/cancel", tokens.get("victim"), null).status() == 403, "non-owner cancel was not 403");
    check(call("POST", "/reservations/" + rid + "/cancel", tokens.get("alice"), null).status() == 200, "owner cancel failed");
    Res bob = reserve("bob", "bob-1", "S2811");
    check(bob.status() == 201, "released seat not re-bookable");

    // --- verdicts
    for (var e : hotWins.entrySet()) check(e.getValue().get() == 1, "hot seat " + e.getKey() + " had " + e.getValue().get() + " winners");
    check(dupWins.get() == 0, "same-key parallel retries produced two 201s");
    check(limWins.get() <= 4, "per-user limit exceeded: " + limWins.get());
    long confirmed201 = dist.getOrDefault("201 confirmed", new LongAdder()).sum();
    long fiveXX = dist.entrySet().stream().filter(e -> e.getKey().contains("SERVER-ERROR")).mapToLong(e -> e.getValue().sum()).sum();
    check(fiveXX == 0, fiveXX + " server errors");

    Res fin = call("GET", "/shows/" + show + "?include_seats=false", null, null);
    long av = fin.num("available"), he = fin.num("held"), co = fin.num("confirmed");
    long expectConfirmed = confirmed201 - 1; // alice's reservation was cancelled
    check(av + he + co == N, "final invariant broken");
    check(co == expectConfirmed, "confirmed seats " + co + " != successful reservations " + expectConfirmed);

    System.out.printf("%n=== OUTCOME DISTRIBUTION (%.1fs) ===%n", secs);
    new TreeMap<>(dist).forEach((k, v) -> System.out.printf("%8d  %s%n", v.sum(), k));
    System.out.println("\n=== HOT SEATS ===");
    hotWins.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> System.out.println(e.getKey() + ": winners=" + e.getValue() + " (expect 1)"));
    System.out.println("\n=== CHECKS ===");
    System.out.println("limit user wins: " + limWins + " (max 4) | duplicate-key double wins: " + dupWins + " (expect 0) | invariant samples mid-burst: " + samples);
    System.out.println("\n=== FINAL RECONCILIATION ===");
    System.out.printf("available=%d held=%d confirmed=%d sum=%d total=%d | 201s=%d minus 1 cancelled = %d%n", av, he, co, av + he + co, N, confirmed201, expectConfirmed);

    Thread.sleep(1500); // seats_available gauge refreshes every 1s
    System.out.println("\n=== /metrics ===");
    for (String line : call("GET", "/metrics", null, null).body().split("\n"))
      if ((line.startsWith("reservations_") || line.startsWith("seats_available")) && !line.startsWith("#") && (line.contains(show) || !line.startsWith("seats_available")))
        System.out.println(line);

    System.out.println(failures.isEmpty() ? "\nRESULT: PASS" : "\nRESULT: FAIL\n  - " + String.join("\n  - ", new TreeSet<>(failures)));
    ex.shutdownNow(); ex2.shutdownNow();
    System.exit(failures.isEmpty() ? 0 : 1);
  }
}
