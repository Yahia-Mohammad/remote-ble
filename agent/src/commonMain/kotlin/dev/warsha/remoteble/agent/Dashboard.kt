package dev.warsha.remoteble.agent

import dev.warsha.remoteble.log.Logger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * The agent's live status dashboard: a single responsive (mobile-friendly) HTML page
 * served at `/`, backed by a JSON snapshot at `/api/state` that the page polls. Shows
 * connected RemoteBLE clients, peripheral ownership, and a rolling activity log.
 *
 * Read-only status surface. Management mutations are deliberately absent in the 0.9.0 release:
 * shared-mode and live dashboard settings are not safe to expose without an authenticated operator
 * plane.
 */
fun Routing.dashboardRoutes(
    monitor: AgentMonitor,
    registry: PeripheralRegistry? = null,
    strictMode: StrictModeState? = null,
) {
    get("/") { call.respondText(DASHBOARD_HTML, ContentType.Text.Html) }
    get("/api/state") {
        val leases = registry?.snapshot().orEmpty()
        call.respondText(monitor.snapshotJson(leases, registry?.settings()), ContentType.Application.Json)
    }
    // Identifier strict-mode status. There is intentionally no mutation endpoint in 0.9.0.
    get("/api/strict") {
        if (strictMode == null) call.respond(HttpStatusCode.NotFound)
        else call.respondText(strictMode.enabled.toString())
    }
    get("/api/log-level") {
        call.respondText(Logger.level?.name?.lowercase() ?: "off")
    }
}

private val DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>RemoteBLE Agent</title>
<style>
  :root {
    --bg:#0d1117; --panel:#161b22; --border:#30363d; --text:#e6edf3;
    --muted:#8b949e; --accent:#3fb950; --accent2:#58a6ff; --warn:#d29922; --mono:ui-monospace,SFMono-Regular,Menlo,monospace;
  }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:var(--text);
    font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif; }
  header { position:sticky; top:0; background:var(--panel); border-bottom:1px solid var(--border);
    padding:12px 16px; display:flex; align-items:center; gap:12px; flex-wrap:wrap; z-index:10; }
  header h1 { font-size:16px; margin:0; display:flex; align-items:center; gap:8px; }
  .dot { width:9px; height:9px; border-radius:50%; background:var(--accent); box-shadow:0 0 8px var(--accent); }
  .dot.stale { background:var(--warn); box-shadow:0 0 8px var(--warn); }
  .stats { margin-left:auto; display:flex; gap:16px; flex-wrap:wrap; font-size:13px; color:var(--muted); }
  .stats b { color:var(--text); }
  main { padding:16px; display:grid; gap:16px; grid-template-columns:1fr 1fr; max-width:1200px; margin:0 auto; }
  .panel { background:var(--panel); border:1px solid var(--border); border-radius:10px; overflow:hidden; }
  .panel.full { grid-column:1 / -1; }
  .panel h2 { font-size:13px; text-transform:uppercase; letter-spacing:.05em; color:var(--muted);
    margin:0; padding:12px 14px; border-bottom:1px solid var(--border); display:flex; justify-content:space-between; }
  .panel h2 .count { color:var(--accent2); }
  .rows { padding:6px 0; max-height:46vh; overflow:auto; }
  .row { padding:9px 14px; border-bottom:1px solid #21262d; display:flex; align-items:baseline; gap:10px; }
  .row:last-child { border-bottom:none; }
  .row .name { font-weight:600; }
  .row .meta { color:var(--muted); font-size:12px; font-family:var(--mono); }
  .row .ago { margin-left:auto; color:var(--muted); font-size:12px; white-space:nowrap; }
  .empty { padding:22px 14px; color:var(--muted); font-size:13px; text-align:center; }
  .row.grace { opacity:.55; }
  .tag { font-size:11px; padding:1px 6px; border-radius:10px; border:1px solid var(--border); color:var(--muted); }
  .tag.excl { color:var(--warn); border-color:var(--warn); }
  .tag.grace { color:var(--warn); border-color:var(--warn); }
  .log { font-family:var(--mono); font-size:12.5px; }
  .log .row { padding:5px 14px; border-bottom:none; }
  .log .t { color:var(--muted); }
  pre { margin:0; }
  @media (max-width:760px) {
    main { grid-template-columns:1fr; padding:10px; gap:10px; }
    .stats { width:100%; margin-left:0; }
    .rows { max-height:38vh; }
  }
</style>
</head>
<body>
<header>
  <h1><span class="dot" id="dot"></span> RemoteBLE Agent</h1>
  <div class="stats">
    <span>uptime <b id="uptime">—</b></span>
    <span>clients <b id="cN">0</b></span>
    <span>owned <b id="dN">0</b></span>
    <span>grace <b id="grace">—</b></span>
  </div>
</header>
<main>
  <section class="panel">
    <h2>Connected clients <span class="count" id="cCount">0</span></h2>
    <div class="rows" id="clients"></div>
  </section>
  <section class="panel">
    <h2>Peripheral ownership <span class="count" id="dCount">0</span></h2>
    <div class="rows" id="devices"></div>
  </section>
  <section class="panel full">
    <h2>Activity log <span class="count" id="lCount">0</span></h2>
    <div class="rows log" id="logs"></div>
  </section>
</main>
<script>
  const ${'$'} = id => document.getElementById(id);
  function ago(ms, now) {
    let s = Math.max(0, Math.round((now - ms) / 1000));
    if (s < 60) return s + "s";
    let m = Math.floor(s / 60); if (m < 60) return m + "m " + (s % 60) + "s";
    let h = Math.floor(m / 60); return h + "h " + (m % 60) + "m";
  }
  function esc(s) { return (s ?? "").replace(/[&<>]/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;'}[c])); }
  function time(ms) { return new Date(ms).toLocaleTimeString(); }

  const short = id => (id && id.length > 8) ? id.slice(0, 8) : id;

  function render(s) {
    const now = s.nowMs;
    ${'$'}("uptime").textContent = ago(s.startedAtMs, now);
    ${'$'}("cN").textContent = ${'$'}("cCount").textContent = s.clients.length;
    ${'$'}("dN").textContent = ${'$'}("dCount").textContent = s.leases.length;
    ${'$'}("lCount").textContent = s.logs.length;
    if (s.settings) {
      const sec = ms => Math.round(ms / 1000) + "s";
      ${'$'}("grace").textContent = "lease " + sec(s.settings.leaseGraceMs) +
        " · link " + sec(s.settings.transportGraceMs);
    }

    ${'$'}("clients").innerHTML = s.clients.length ? s.clients.map(c =>
      `<div class="row"><span class="name">#${'$'}{c.id}</span>` +
      `<span class="meta">${'$'}{esc(c.address)}</span>` +
      `<span class="ago">${'$'}{ago(c.connectedAtMs, now)}</span></div>`).join("")
      : `<div class="empty">No clients connected.</div>`;

    ${'$'}("devices").innerHTML = s.leases.length ? s.leases.map(l => {
      const state = l.inGrace ? `<span class="tag grace">releasing…</span>` : "";
      const excl = `<span class="tag excl">exclusive</span>`;
      return `<div class="row${'$'}{l.inGrace ? " grace" : ""}"><span class="name">${'$'}{esc(l.name || "(unnamed)")}</span>` +
        `<span class="meta">${'$'}{esc(l.handle)} · via ${'$'}{esc(short(l.owner))}</span>` +
        ` ${'$'}{state} ${'$'}{excl}</div>`;
    }).join("")
      : `<div class="empty">No peripherals owned. Clients can still scan.</div>`;

    ${'$'}("logs").innerHTML = s.logs.length ? s.logs.slice().reverse().map(l =>
      `<div class="row"><span class="t">${'$'}{time(l.atMs)}</span>&nbsp; ${'$'}{esc(l.message)}</div>`).join("")
      : `<div class="empty">No activity yet.</div>`;
  }

  async function poll() {
    try {
      const r = await fetch("/api/state", { cache: "no-store" });
      render(await r.json());
      ${'$'}("dot").classList.remove("stale");
    } catch (e) {
      ${'$'}("dot").classList.add("stale");
    }
  }
  poll();
  setInterval(poll, 1000);
</script>
</body>
</html>
""".trimIndent()
