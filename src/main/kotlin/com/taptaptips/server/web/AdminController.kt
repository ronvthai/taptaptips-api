package com.taptaptips.server.web

import com.taptaptips.server.service.RuntimeSettings
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Admin page for runtime settings: https://tip.taptaptips.com/admin
 *
 * Protected by one password, ADMIN_PASSWORD in Render (at least 16 characters;
 * the page stays locked until it's set). The browser sends it as the
 * X-Admin-Key header on every call; 5 wrong tries from one address locks that
 * address out for 15 minutes. Every change is logged with who/when.
 *
 * All /admin paths must be permitAll in SecurityConfig — this controller does
 * its own password check instead of the app's JWT login.
 */
@RestController
class AdminController(
    private val settings: RuntimeSettings,
    @Value("\${admin.password:}") private val adminPassword: String
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val failures = ConcurrentHashMap<String, ArrayDeque<Instant>>()

    companion object {
        private const val MIN_PASSWORD_LENGTH = 16
        private const val MAX_FAILURES = 5
        private val LOCKOUT: Duration = Duration.ofMinutes(15)
    }

    data class SetRequest(val key: String = "", val value: String = "")
    data class KeyRequest(val key: String = "")

    // ── Page ─────────────────────────────────────────────────────────────────

    @GetMapping("/admin", produces = [MediaType.TEXT_HTML_VALUE])
    fun page(): ResponseEntity<String> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header("X-Robots-Tag", "noindex, nofollow")
            .header("X-Frame-Options", "DENY")
            .contentType(MediaType.TEXT_HTML)
            .body(PAGE)

    // ── API ──────────────────────────────────────────────────────────────────

    @PostMapping("/admin/api/login")
    fun login(req: HttpServletRequest): ResponseEntity<Any> =
        guard(req) { ResponseEntity.ok(mapOf("ok" to true)) }

    @GetMapping("/admin/api/settings")
    fun list(req: HttpServletRequest): ResponseEntity<Any> =
        guard(req) { ResponseEntity.ok(settings.list()) }

    @PostMapping("/admin/api/settings")
    fun set(req: HttpServletRequest, @RequestBody body: SetRequest): ResponseEntity<Any> =
        guard(req) {
            try {
                settings.set(body.key, body.value, "admin@" + clientIp(req))
                ResponseEntity.ok(settings.list())
            } catch (e: IllegalArgumentException) {
                ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Not allowed.")))
            }
        }

    @PostMapping("/admin/api/settings/reset")
    fun reset(req: HttpServletRequest, @RequestBody body: KeyRequest): ResponseEntity<Any> =
        guard(req) {
            try {
                settings.reset(body.key, "admin@" + clientIp(req))
                ResponseEntity.ok(settings.list())
            } catch (e: IllegalArgumentException) {
                ResponseEntity.badRequest().body(mapOf("error" to (e.message ?: "Not allowed.")))
            }
        }

    // ── Password check ───────────────────────────────────────────────────────

    private fun guard(req: HttpServletRequest, action: () -> ResponseEntity<Any>): ResponseEntity<Any> {
        val expected = adminPassword.trim()
        if (expected.length < MIN_PASSWORD_LENGTH) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("error" to
                "The admin page is locked. Set ADMIN_PASSWORD in Render (at least $MIN_PASSWORD_LENGTH characters)."))
        }
        val ip = clientIp(req)
        if (lockedOut(ip)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(mapOf("error" to "Too many wrong passwords. Try again in 15 minutes."))
        }
        val given = req.getHeader("X-Admin-Key") ?: ""
        if (!sameSecret(given, expected)) {
            recordFailure(ip)
            log.warn("🔒 Admin: wrong password from $ip")
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "Wrong password."))
        }
        failures.remove(ip)
        return action()
    }

    /** Constant-time compare (hashing first so different lengths don't leak). */
    private fun sameSecret(a: String, b: String): Boolean {
        val sha = MessageDigest.getInstance("SHA-256")
        return MessageDigest.isEqual(sha.digest(a.toByteArray()), sha.digest(b.toByteArray()))
    }

    private fun lockedOut(ip: String): Boolean {
        val q = failures[ip] ?: return false
        synchronized(q) {
            val cutoff = Instant.now().minus(LOCKOUT)
            while (q.isNotEmpty() && q.peekFirst().isBefore(cutoff)) q.pollFirst()
            return q.size >= MAX_FAILURES
        }
    }

    private fun recordFailure(ip: String) {
        val q = failures.computeIfAbsent(ip) { ArrayDeque() }
        synchronized(q) { q.addLast(Instant.now()) }
    }

    private fun clientIp(req: HttpServletRequest): String =
        req.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: req.remoteAddr ?: "unknown"

    // ── HTML ─────────────────────────────────────────────────────────────────
    // The JS never uses a dollar sign literally (Kotlin string templates);
    // the currency sign is written as $.

    private val PAGE = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex, nofollow">
<title>TapTapTips Admin</title>
<style>
  :root { --green:#4CAF50; --bg:#0b0b0b; --card:#161616; --line:#2a2a2a; --muted:#9a9a9a; --warn:#FFC107; --red:#ef5350; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:#fff;
         font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Arial,sans-serif; }
  main { max-width:640px; margin:0 auto; padding:24px 16px 64px; }
  h1 { font-size:22px; margin:0 0 4px; }
  .sub { color:var(--muted); font-size:14px; margin:0 0 24px; }
  h2 { font-size:13px; text-transform:uppercase; letter-spacing:.08em; color:var(--muted); margin:28px 0 10px; }
  .card { background:var(--card); border:1px solid var(--line); border-radius:14px; }
  .row { padding:16px; border-top:1px solid var(--line); }
  .row:first-child { border-top:0; }
  .top { display:flex; align-items:center; gap:12px; }
  .label { flex:1; font-weight:600; font-size:15px; }
  .help { color:var(--muted); font-size:13px; margin-top:4px; line-height:1.4; }
  .meta { color:var(--muted); font-size:12px; margin-top:8px; display:flex; gap:10px; align-items:center; flex-wrap:wrap; }
  .tag { color:var(--warn); }
  .edit { display:flex; gap:8px; margin-top:10px; align-items:center; }
  .money { position:relative; flex:1; }
  .money span { position:absolute; left:12px; top:50%; transform:translateY(-50%); color:var(--muted); }
  input[type=text], input[type=password], input[type=number] {
    width:100%; background:#0f0f0f; border:1px solid var(--line); color:#fff; border-radius:10px;
    padding:11px 12px; font-size:15px; font-family:inherit; }
  .money input { padding-left:24px; }
  button { background:var(--green); color:#fff; border:0; border-radius:10px; padding:11px 16px;
           font-size:14px; font-weight:600; cursor:pointer; font-family:inherit; }
  button.link { background:none; color:var(--green); padding:0; font-weight:500; font-size:12px; }
  button:disabled { opacity:.5; cursor:default; }
  .switch { position:relative; width:52px; height:30px; flex:none; }
  .switch input { opacity:0; width:0; height:0; }
  .slider { position:absolute; inset:0; background:#3a3a3a; border-radius:30px; transition:.15s; cursor:pointer; }
  .slider:before { content:""; position:absolute; width:24px; height:24px; left:3px; top:3px; background:#fff; border-radius:50%; transition:.15s; }
  .switch input:checked + .slider { background:var(--green); }
  .switch input:checked + .slider:before { transform:translateX(22px); }
  .switch input:focus-visible + .slider { outline:2px solid #fff; outline-offset:2px; }
  .msg { font-size:13px; min-height:18px; margin-top:6px; }
  .msg.err { color:var(--red); } .msg.ok { color:var(--green); }
  .login { max-width:360px; margin:15vh auto 0; }
  .login .card { padding:20px; }
  .hidden { display:none !important; }
  .bar { display:flex; justify-content:space-between; align-items:flex-start; gap:12px; }
</style>
</head>
<body>
<main>
  <section id="loginView" class="login">
    <h1>TapTapTips Admin</h1>
    <p class="sub">Settings that take effect without a redeploy.</p>
    <form class="card" id="loginForm">
      <input type="password" id="pw" placeholder="Admin password" autocomplete="current-password" aria-label="Admin password">
      <div style="height:12px"></div>
      <button type="submit" style="width:100%">Sign in</button>
      <div class="msg err" id="loginMsg" role="alert"></div>
    </form>
  </section>

  <section id="appView" class="hidden">
    <div class="bar">
      <div>
        <h1>TapTapTips Admin</h1>
        <p class="sub">Changes apply within about 30 seconds. No redeploy needed.</p>
      </div>
      <button class="link" id="signOut" style="margin-top:6px">Sign out</button>
    </div>
    <div id="groups"></div>
  </section>
</main>
<script>
(function () {
  var DOLLAR = "\u0024";
  var key = "";
  try { key = sessionStorage.getItem("tttAdminKey") || ""; } catch (e) {}

  function api(path, body) {
    return fetch(path, {
      method: body === undefined && path.indexOf("login") < 0 ? "GET" : "POST",
      headers: { "X-Admin-Key": key, "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body)
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (j) {
        if (r.status === 401 || r.status === 429 || r.status === 503) { signOut(j.error); throw new Error(j.error || "Signed out"); }
        if (!r.ok) throw new Error(j.error || "Something went wrong.");
        return j;
      });
    });
  }

  function money(cents) { return DOLLAR + (Number(cents) / 100).toFixed(2); }
  function show(s, v) {
    if (s.type === "BOOL") return v === "true" ? "On" : "Off";
    if (s.type === "CENTS") return money(v);
    return v;
  }
  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text !== undefined) e.textContent = text;
    return e;
  }
  function when(iso) {
    if (!iso) return "";
    var d = new Date(iso);
    return d.toLocaleString();
  }

  function render(list) {
    var groups = document.getElementById("groups");
    groups.innerHTML = "";
    var byGroup = {};
    var order = [];
    list.forEach(function (s) {
      if (!byGroup[s.group]) { byGroup[s.group] = []; order.push(s.group); }
      byGroup[s.group].push(s);
    });
    order.forEach(function (g) {
      groups.appendChild(el("h2", "", g));
      var card = el("div", "card");
      byGroup[g].forEach(function (s) { card.appendChild(row(s)); });
      groups.appendChild(card);
    });
  }

  function row(s) {
    var r = el("div", "row");
    var top = el("div", "top");
    var label = el("div", "label", s.label);
    top.appendChild(label);
    var msg = el("div", "msg");
    msg.setAttribute("role", "status");

    function save(value, btn) {
      if (btn) btn.disabled = true;
      msg.className = "msg"; msg.textContent = "Saving…";
      api("/admin/api/settings", { key: s.key, value: String(value) })
        .then(function (list) { render(list); })
        .catch(function (e) {
          msg.className = "msg err"; msg.textContent = e.message;
          if (btn) { btn.disabled = false; if (btn.type === "checkbox") btn.checked = !btn.checked; }
        });
    }

    if (s.type === "BOOL") {
      var sw = el("label", "switch");
      var cb = document.createElement("input");
      cb.type = "checkbox"; cb.checked = s.value === "true";
      cb.setAttribute("aria-label", s.label);
      sw.appendChild(cb); sw.appendChild(el("span", "slider"));
      cb.addEventListener("change", function () {
        var turningOff = !cb.checked;
        if (turningOff && !confirm("Turn off \"" + s.label + "\"?")) { cb.checked = true; return; }
        save(cb.checked ? "true" : "false", cb);
      });
      top.appendChild(sw);
      r.appendChild(top);
    } else {
      r.appendChild(top);
      var edit = el("div", "edit");
      var input = document.createElement("input");
      input.setAttribute("aria-label", s.label);
      var wrap = input;
      if (s.type === "CENTS") {
        input.type = "number"; input.step = "0.01"; input.min = "0";
        input.value = (Number(s.value) / 100).toFixed(2);
        wrap = el("div", "money"); wrap.appendChild(el("span", "", DOLLAR)); wrap.appendChild(input);
      } else if (s.type === "NUMBER") {
        input.type = "number"; input.step = "1"; input.value = s.value; wrap.style.flex = "1";
      } else {
        input.type = "text"; input.value = s.value; wrap.style.flex = "1";
      }
      var btn = el("button", "", "Save");
      btn.addEventListener("click", function () {
        var v = input.value.trim();
        if (s.type === "CENTS") {
          var n = Math.round(parseFloat(v) * 100);
          if (isNaN(n)) { msg.className = "msg err"; msg.textContent = "Enter an amount."; return; }
          v = String(n);
        }
        save(v, btn);
      });
      input.addEventListener("keydown", function (e) { if (e.key === "Enter") btn.click(); });
      edit.appendChild(wrap); edit.appendChild(btn);
      r.appendChild(edit);
    }

    r.appendChild(el("div", "help", s.help));
    var meta = el("div", "meta");
    if (s.overridden) {
      meta.appendChild(el("span", "tag", "Changed " + when(s.updatedAt)));
      meta.appendChild(el("span", "", "Default: " + show(s, s.defaultValue)));
      var reset = el("button", "link", "Reset to default");
      reset.addEventListener("click", function () {
        if (!confirm("Reset \"" + s.label + "\" to " + show(s, s.defaultValue) + "?")) return;
        api("/admin/api/settings/reset", { key: s.key }).then(render)
          .catch(function (e) { msg.className = "msg err"; msg.textContent = e.message; });
      });
      meta.appendChild(reset);
    } else {
      meta.appendChild(el("span", "", "Using default"));
    }
    r.appendChild(meta);
    r.appendChild(msg);
    return r;
  }

  function showApp() {
    document.getElementById("loginView").classList.add("hidden");
    document.getElementById("appView").classList.remove("hidden");
    api("/admin/api/settings").then(render).catch(function () {});
  }

  function signOut(reason) {
    key = "";
    try { sessionStorage.removeItem("tttAdminKey"); } catch (e) {}
    document.getElementById("appView").classList.add("hidden");
    document.getElementById("loginView").classList.remove("hidden");
    document.getElementById("loginMsg").textContent = reason || "";
  }

  document.getElementById("signOut").addEventListener("click", function () { signOut(""); });
  document.getElementById("loginForm").addEventListener("submit", function (e) {
    e.preventDefault();
    key = document.getElementById("pw").value;
    document.getElementById("loginMsg").textContent = "";
    api("/admin/api/login", {}).then(function () {
      try { sessionStorage.setItem("tttAdminKey", key); } catch (e2) {}
      document.getElementById("pw").value = "";
      showApp();
    }).catch(function () {});
  });

  if (key) showApp();
})();
</script>
</body>
</html>
""".trimIndent()
}
