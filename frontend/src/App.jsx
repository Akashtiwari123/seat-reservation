import { useEffect, useState } from "react";

export default function App() {
  const [user, setUser] = useState("alice");
  const [token, setToken] = useState("");
  const [admin, setAdmin] = useState("");
  const [name, setName] = useState("friday-night");
  const [count, setCount] = useState(60);
  const [showId, setShowId] = useState("");
  const [show, setShow] = useState(null);
  const [sel, setSel] = useState([]);
  const [mine, setMine] = useState([]);
  const [log, setLog] = useState([]);
  const note = (m) => setLog((l) => [new Date().toLocaleTimeString() + "  " + m, ...l].slice(0, 10));

  const call = async (path, method = "GET", body, tok = token) => {
    const r = await fetch(path, {
      method,
      headers: { "Content-Type": "application/json", ...(tok ? { Authorization: "Bearer " + tok } : {}) },
      body: body ? JSON.stringify(body) : undefined,
    });
    return { status: r.status, j: await r.json().catch(() => ({})) };
  };

  const login = async () => {
    const { j } = await call("/auth/token", "POST", { user_id: user }, "");
    setToken(j.token); note("signed in as " + user);
  };
  const createShow = async () => {
    const seats = Array.from({ length: Number(count) }, (_, i) => "A" + (i + 1));
    const { status, j } = await call("/shows", "POST", { name, seats, price_paise: 25000 }, admin);
    if (status === 201) { setShowId(j.id); note("created show " + j.id); } else note("create failed: " + (j.error || status));
  };
  const load = async () => {
    if (!showId) return;
    const { status, j } = await call("/shows/" + showId, "GET", null, "");
    if (status === 200) setShow(j);
  };
  useEffect(() => { load(); const t = setInterval(load, 2000); return () => clearInterval(t); }, [showId]);

  const reserve = async () => {
    const { status, j } = await call(`/shows/${showId}/reserve`, "POST", { seats: sel, idempotency_key: crypto.randomUUID() });
    if (status === 201) { setMine((m) => [j, ...m]); setSel([]); note("confirmed " + j.seats.join(",") + " for ₹" + j.amount_paise / 100); }
    else note(`declined (${status}): ${j.error} - ${j.message}`);
    load();
  };
  const cancel = async (id) => {
    const { status, j } = await call(`/reservations/${id}/cancel`, "POST");
    note(status === 200 ? "cancelled " + id.slice(0, 8) : `cancel failed: ${j.error}`);
    if (status === 200) setMine((m) => m.filter((x) => x.reservation_id !== id));
    load();
  };
  const toggle = (s) => setSel((c) => (c.includes(s) ? c.filter((x) => x !== s) : [...c, s]));

  return (
    <main>
      <h1>Seat reservation</h1>
      <section>
        <h2>Sign in</h2>
        <input value={user} onChange={(e) => setUser(e.target.value)} aria-label="User id" />
        <button onClick={login}>Sign in</button> {token && <span>signed in as {user}</span>}
      </section>
      <section>
        <h2>Create a show (admin)</h2>
        <input placeholder="Admin token" value={admin} onChange={(e) => setAdmin(e.target.value)} />
        <input value={name} onChange={(e) => setName(e.target.value)} aria-label="Show name" />
        <input type="number" value={count} onChange={(e) => setCount(e.target.value)} aria-label="Seat count" style={{ width: 70 }} />
        <button onClick={createShow}>Create show</button>
        <div>Open an existing show: <input placeholder="Show id" value={showId} onChange={(e) => setShowId(e.target.value)} /></div>
      </section>
      {show && (
        <section>
          <h2>{show.name} · ₹{show.price_paise / 100} per seat · limit {show.per_user_limit} per person</h2>
          <p>{show.counts.available} available, {show.counts.held} held, {show.counts.confirmed} confirmed of {show.total_seats}</p>
          <div className="grid">
            {(show.seats || []).slice(0, 600).map((s) => (
              <button key={s.seat} disabled={s.status !== "available"} onClick={() => toggle(s.seat)}
                className={"seat " + s.status + (sel.includes(s.seat) ? " sel" : "")}>{s.seat}</button>
            ))}
          </div>
          <p><button onClick={reserve} disabled={!token || !sel.length}>Reserve {sel.length || ""} seat{sel.length === 1 ? "" : "s"}</button>
            {!token && " Sign in first."}</p>
        </section>
      )}
      {mine.length > 0 && (
        <section>
          <h2>Your reservations</h2>
          {mine.map((r) => (
            <div key={r.reservation_id}>{r.seats.join(", ")} · ₹{r.amount_paise / 100}
              <button onClick={() => cancel(r.reservation_id)}>Cancel</button></div>
          ))}
        </section>
      )}
      <section><h2>Activity</h2><div className="log">{log.join("\n") || "Nothing yet."}</div></section>
    </main>
  );
}
