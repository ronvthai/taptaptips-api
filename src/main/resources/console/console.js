/* TapTapTips admin dashboard: reports, users, settings and the ban list.
 * All data is rendered with textContent
 * (never innerHTML), since display names and emails are user-typed. */
(function () {
  "use strict";

  const TOKEN_KEY = "ttt.admin.token";
  const $ = (id) => document.getElementById(id);

  // ── session token (sessionStorage: gone when the tab closes) ──────────────
  let memToken = null;
  function getToken() {
    try { return sessionStorage.getItem(TOKEN_KEY) || memToken; } catch (_) { return memToken; }
  }
  function setToken(t) {
    memToken = t;
    try { t ? sessionStorage.setItem(TOKEN_KEY, t) : sessionStorage.removeItem(TOKEN_KEY); } catch (_) {}
  }

  // ── tiny DOM helper ───────────────────────────────────────────────────────
  function el(tag, attrs, ...kids) {
    const n = document.createElement(tag);
    if (attrs) {
      for (const [k, v] of Object.entries(attrs)) {
        if (v == null || v === false) continue;
        if (k === "class") n.className = v;
        else if (k === "text") n.textContent = v;
        else if (k.startsWith("on")) n.addEventListener(k.slice(2), v);
        else n.setAttribute(k, v === true ? "" : v);
      }
    }
    for (const kid of kids.flat()) {
      if (kid == null || kid === false) continue;
      n.appendChild(typeof kid === "string" || typeof kid === "number" ? document.createTextNode(String(kid)) : kid);
    }
    return n;
  }
  function clear(n) { while (n.firstChild) n.removeChild(n.firstChild); return n; }

  // ── formatting ────────────────────────────────────────────────────────────
  const nf = new Intl.NumberFormat();
  const usdF = new Intl.NumberFormat(undefined, { style: "currency", currency: "USD" });
  const num = (n) => nf.format(n || 0);
  const usd = (cents) => usdF.format((cents || 0) / 100);
  function date(iso) {
    if (!iso) return "—";
    const d = new Date(iso);
    return d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
  }
  function dateTime(iso) {
    if (!iso) return "—";
    const d = new Date(iso);
    return d.toLocaleString(undefined, { year: "numeric", month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  }
  function ago(iso) {
    if (!iso) return "—";
    const s = (Date.now() - new Date(iso).getTime()) / 1000;
    if (s < 60) return "just now";
    if (s < 3600) return Math.floor(s / 60) + " min ago";
    if (s < 86400) return Math.floor(s / 3600) + " h ago";
    if (s < 86400 * 30) return Math.floor(s / 86400) + " d ago";
    return date(iso);
  }
  const pct = (part, total) => (total ? Math.round((part / total) * 100) + "%" : "—");

  function toast(msg) {
    const t = $("toast");
    t.textContent = msg;
    t.hidden = false;
    clearTimeout(toast._t);
    toast._t = setTimeout(() => { t.hidden = true; }, 2200);
  }

  // ── API ───────────────────────────────────────────────────────────────────
  async function api(path, opts) {
    const headers = Object.assign({ "X-Admin-Token": getToken() || "" }, (opts && opts.headers) || {});
    const res = await fetch(path, Object.assign({}, opts, { headers, cache: "no-store" }));
    if (res.status === 401 && path !== "/console/api/login") {
      setToken(null);
      showLogin("Your session ended. Sign in again.");
      throw new Error("unauthorized");
    }
    return res;
  }
  async function getJson(path) {
    const res = await api(path);
    if (!res.ok) throw new Error((await safeJson(res)).error || ("Request failed (" + res.status + ")"));
    return res.json();
  }
  async function safeJson(res) { try { return await res.json(); } catch (_) { return {}; } }

  // ── login / logout ────────────────────────────────────────────────────────
  function showLogin(msg) {
    $("app-view").hidden = true;
    $("login-view").hidden = false;
    $("login-error").textContent = msg || "";
    $("password").value = "";
    $("password").focus();
  }
  function showApp() {
    $("login-view").hidden = true;
    $("app-view").hidden = false;
    route();
  }

  $("login-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const btn = $("login-btn");
    btn.disabled = true;
    $("login-error").textContent = "";
    try {
      const res = await fetch("/console/api/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ password: $("password").value }),
        cache: "no-store"
      });
      const body = await safeJson(res);
      if (res.ok && body.token) {
        setToken(body.token);
        $("password").value = "";
        showApp();
      } else {
        $("login-error").textContent = body.error || "Sign-in failed.";
      }
    } catch (_) {
      $("login-error").textContent = "Couldn't reach the server.";
    } finally {
      btn.disabled = false;
    }
  });

  $("logout-btn").addEventListener("click", async () => {
    try { await api("/console/api/logout", { method: "POST" }); } catch (_) {}
    setToken(null);
    showLogin("Signed out.");
  });

  $("refresh-btn").addEventListener("click", () => { cache.overview = null; route(true); });

  // ── routing (#/overview, #/users, #/user/<id>, #/settings, #/fraud) ──────
  const cache = { overview: null };
  function route(force) {
    if (!getToken()) return showLogin();
    const parts = (location.hash || "#/overview").split("/");
    let page = parts[1] || "overview";
    const arg = parts[2];
    if (!["overview", "users", "user", "settings", "fraud"].includes(page) || (page === "user" && !arg)) page = "overview";
    document.querySelectorAll(".tabs a").forEach((a) => a.classList.toggle("on",
      a.dataset.tab === page || (page === "user" && a.dataset.tab === "users")));
    $("overview-view").hidden = page !== "overview";
    $("users-view").hidden = page !== "users";
    $("user-view").hidden = page !== "user";
    $("settings-view").hidden = page !== "settings";
    $("fraud-view").hidden = page !== "fraud";
    window.scrollTo(0, 0);
    if (page === "users") loadUsers();
    else if (page === "settings") loadSettings();
    else if (page === "fraud") loadBans();
    else if (page === "user") loadUser(decodeURIComponent(arg));
    else loadOverview(force);
  }
  window.addEventListener("hashchange", () => route());

  // ── Overview ──────────────────────────────────────────────────────────────
  let chartMode = "daily";
  document.querySelectorAll("[data-chart]").forEach((b) => b.addEventListener("click", () => {
    chartMode = b.dataset.chart;
    document.querySelectorAll("[data-chart]").forEach((x) => x.classList.toggle("on", x === b));
    if (cache.overview) drawChart(cache.overview.signupsByDay);
  }));

  function card(label, value, sub, onClick) {
    return el("div", { class: "card" + (onClick ? " link" : ""), onclick: onClick, role: onClick ? "button" : null, tabindex: onClick ? "0" : null },
      el("div", { class: "label", text: label }),
      el("div", { class: "value", text: value }),
      sub ? el("div", { class: "sub", text: sub }) : null);
  }
  function goUsers(params) {
    pendingFilters = params;
    location.hash = "#/users";
  }

  async function loadOverview(force) {
    if (cache.overview && !force) return renderOverview(cache.overview);
    try {
      cache.overview = await getJson("/console/api/overview");
      renderOverview(cache.overview);
    } catch (e) { if (e.message !== "unauthorized") toast(e.message); }
  }

  function renderOverview(o) {
    $("env-badge").hidden = !o.stripeTestMode;
    $("updated").textContent = "Updated " + dateTime(o.generatedAt);
    const u = o.users;
    clear($("user-cards")).append(
      card("Total users", num(u.total), "+" + num(u.new_today) + " today", () => goUsers({})),
      card("New, last 7 days", num(u.new_7d), "+" + num(u.new_30d) + " in 30 days"),
      card("Active, last 7 days", num(u.active_7d), pct(u.active_7d, u.total) + " of users", () => goUsers({ active: "7" })),
      card("Active, last 30 days", num(u.active_30d), pct(u.active_30d, u.total) + " of users", () => goUsers({ active: "30" })),
      card("Bank linked", num(u.bank_linked), num(u.bank_started) + " started, not finished", () => goUsers({ bank: "linked" })),
      card("Card on file", num(u.card_on_file), pct(u.card_on_file, u.total) + " of users", () => goUsers({ card: "yes" })),
      card("Receive tips", num(u.receivers), "turned on in settings", () => goUsers({ receiver: "yes" })),
      card("Have sent a tip", num(u.ever_sent), num(u.ever_received) + " have received one"),
      card("iPhone / Android", num(u.ios) + " / " + num(u.android), num(u.platform_unknown) + " unknown"),
      card("Suspended", num(u.suspended), null, () => goUsers({ suspended: "yes" }))
    );

    const t = o.tips;
    const cards = [
      card("Tips today", usd(t.cents_today), num(t.count_today) + " tips"),
      card("Tips, last 30 days", usd(t.cents_30d), num(t.count_30d) + " tips"),
      card("Tips, all time", usd(t.cents_all), num(t.count_all) + " tips · avg " + usd(t.count_all ? t.cents_all / t.count_all : 0))
    ];
    if (o.held) cards.push(card("Held, waiting on bank", usd(o.held.net_cents), num(o.held.count) + " tips · " + num(o.held.receivers) + " people"));
    if (o.guestTips) cards.push(card("Web-link tips", usd(o.guestTips.cents), num(o.guestTips.count) + " tips"));
    if (o.referrals) {
      const r = o.referrals;
      const total = Object.values(r).reduce((a, b) => a + b, 0);
      cards.push(card("Referrals", num(total), num(r.PAID || 0) + " paid · " + num(r.PENDING || 0) + " pending"));
    }
    clear($("tip-cards")).append(...cards);

    renderTop($("top-receivers"), o.topReceivers30d);
    renderTop($("top-senders"), o.topSenders30d);
    drawChart(o.signupsByDay);

    const note = $("tracking-note");
    note.hidden = false;
    note.textContent = o.lastActiveTracking
      ? "“Active” counts app use, device check-ins, push-token refreshes and tips. Times shown in your browser's time zone; day boundaries use " + o.timezone + "."
      : "Last-active tracking isn't running yet (the server couldn't add its column). Activity is estimated from devices and tips.";
  }

  function renderTop(host, rows) {
    clear(host);
    if (!rows || !rows.length) { host.append(el("p", { class: "muted", text: "No tips in the last 30 days." })); return; }
    host.append(el("table", null,
      el("thead", null, el("tr", null, el("th", { text: "#" }), el("th", { text: "User" }), el("th", { class: "num", text: "Tips" }), el("th", { class: "num", text: "Amount" }))),
      el("tbody", null, rows.map((r, i) => el("tr", { class: "clickable", onclick: () => { location.hash = "#/user/" + r.id; } },
        el("td", { class: "muted", text: String(i + 1) }),
        el("td", { class: "name-cell", text: r.displayName || "—" }),
        el("td", { class: "num", text: num(r.count) }),
        el("td", { class: "num", text: usd(r.cents) }))))));
  }

  const SVGNS = "http://www.w3.org/2000/svg";
  function svg(tag, attrs) {
    const n = document.createElementNS(SVGNS, tag);
    for (const [k, v] of Object.entries(attrs || {})) n.setAttribute(k, v);
    return n;
  }

  function drawChart(series) {
    const host = clear($("signup-chart"));
    if (!series || !series.length) return;
    const W = Math.max(host.clientWidth || 800, 320), H = 220;
    const pad = { l: 40, r: 10, t: 10, b: 24 };
    const iw = W - pad.l - pad.r, ih = H - pad.t - pad.b;
    const vals = series.map((d) => (chartMode === "daily" ? d.count : d.total));
    const minV = chartMode === "daily" ? 0 : Math.min(...vals);
    const maxV = Math.max(1, ...vals);
    const lo = chartMode === "daily" ? 0 : Math.floor(minV * 0.95);
    const span = Math.max(1, maxV - lo);
    const y = (v) => pad.t + ih - ((v - lo) / span) * ih;
    const s = svg("svg", { viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: "none", role: "img",
      "aria-label": chartMode === "daily" ? "Sign-ups per day" : "Total users over time" });

    // gridlines + y labels
    for (let i = 0; i <= 4; i++) {
      const v = lo + (span * i) / 4;
      const yy = y(v);
      s.append(svg("line", { x1: pad.l, x2: W - pad.r, y1: yy, y2: yy, class: "axis" }));
      const t = svg("text", { x: pad.l - 6, y: yy + 4, "text-anchor": "end", class: "tick" });
      t.textContent = num(Math.round(v));
      s.append(t);
    }
    // x labels: first of each month
    const step = iw / series.length;
    series.forEach((d, i) => {
      if (d.day.endsWith("-01") || i === 0) {
        const t = svg("text", { x: pad.l + i * step + step / 2, y: H - 6, "text-anchor": "middle", class: "tick" });
        t.textContent = new Date(d.day + "T12:00:00").toLocaleDateString(undefined, { month: "short", day: "numeric" });
        s.append(t);
      }
    });

    const tip = el("div", { class: "chart-tip", hidden: true });
    const showTip = (i, xPos, yPos) => {
      const d = series[i];
      tip.textContent = new Date(d.day + "T12:00:00").toLocaleDateString(undefined, { month: "short", day: "numeric" }) +
        " · " + (chartMode === "daily" ? num(d.count) + " new" : num(d.total) + " users");
      tip.style.left = xPos + "px";
      tip.style.top = yPos + "px";
      tip.hidden = false;
    };

    if (chartMode === "daily") {
      series.forEach((d, i) => {
        const h = Math.max(d.count > 0 ? 2 : 0, pad.t + ih - y(d.count));
        const r = svg("rect", { x: pad.l + i * step + 1, y: pad.t + ih - h, width: Math.max(1, step - 2), height: h, class: "bar" });
        r.addEventListener("mouseenter", () => showTip(i, ((pad.l + i * step + step / 2) / W) * host.clientWidth, ((pad.t + ih - h) / H) * H));
        r.addEventListener("mouseleave", () => { tip.hidden = true; });
        s.append(r);
      });
    } else {
      const pts = series.map((d, i) => [pad.l + i * step + step / 2, y(d.total)]);
      const path = pts.map((p, i) => (i ? "L" : "M") + p[0].toFixed(1) + " " + p[1].toFixed(1)).join(" ");
      s.append(svg("path", { d: path + ` L ${pts[pts.length - 1][0]} ${pad.t + ih} L ${pts[0][0]} ${pad.t + ih} Z`, class: "area" }));
      s.append(svg("path", { d: path, class: "line" }));
      const hit = svg("rect", { x: pad.l, y: pad.t, width: iw, height: ih, fill: "transparent" });
      hit.addEventListener("mousemove", (ev) => {
        const box = s.getBoundingClientRect();
        const i = Math.min(series.length - 1, Math.max(0, Math.floor(((ev.clientX - box.left) / box.width * W - pad.l) / step)));
        showTip(i, (pts[i][0] / W) * box.width, (pts[i][1] / H) * H);
      });
      hit.addEventListener("mouseleave", () => { tip.hidden = true; });
      s.append(hit);
    }
    host.append(s, tip);
  }
  let resizeT;
  window.addEventListener("resize", () => {
    clearTimeout(resizeT);
    resizeT = setTimeout(() => { if (cache.overview && !$("overview-view").hidden) drawChart(cache.overview.signupsByDay); }, 150);
  });

  // ── Users table ───────────────────────────────────────────────────────────
  const FILTER_IDS = { q: "f-q", bank: "f-bank", card: "f-card", receiver: "f-receiver", platform: "f-platform",
    active: "f-active", suspended: "f-suspended", joinedFrom: "f-from", joinedTo: "f-to" };
  const state = { sort: "joined", dir: "desc", page: 0, size: 50 };
  let pendingFilters = null;
  let usersReq = 0;

  function currentParams() {
    const p = new URLSearchParams();
    for (const [k, id] of Object.entries(FILTER_IDS)) {
      const v = $(id).value.trim();
      if (v) p.set(k, v);
    }
    p.set("sort", state.sort);
    p.set("dir", state.dir);
    return p;
  }

  async function loadUsers() {
    if (pendingFilters) {
      for (const [k, id] of Object.entries(FILTER_IDS)) $(id).value = pendingFilters[k] || "";
      state.page = 0;
      pendingFilters = null;
    }
    document.querySelectorAll("th[data-sort]").forEach((th) => {
      th.classList.toggle("sorted", th.dataset.sort === state.sort);
      th.classList.toggle("asc", th.dataset.sort === state.sort && state.dir === "asc");
    });
    const p = currentParams();
    p.set("page", state.page);
    p.set("size", state.size);
    const my = ++usersReq;
    const body = $("users-body");
    $("users-count").textContent = "Loading…";
    try {
      const data = await getJson("/console/api/users?" + p.toString());
      if (my !== usersReq) return;  // a newer request superseded this one
      renderUsers(data);
    } catch (e) {
      if (e.message !== "unauthorized") { clear(body); $("users-count").textContent = e.message; }
    }
  }

  function bankBadge(b, long) {
    if (b === "linked") return el("span", { class: "badge good", text: long ? "Bank linked" : "Linked" });
    if (b === "started") return el("span", { class: "badge warn", text: long ? "Bank setup started" : "Started" });
    return el("span", { class: "badge", text: long ? "No bank" : "None" });
  }
  function platformText(list) {
    if (!list || !list.length) return "—";
    return list.map((p) => (p === "ios" ? "iPhone" : "Android")).join(", ");
  }

  function renderUsers(data) {
    const body = clear($("users-body"));
    const from = data.total ? data.page * data.size + 1 : 0;
    const to = Math.min(data.total, (data.page + 1) * data.size);
    $("users-count").textContent = data.total ? `${num(from)}–${num(to)} of ${num(data.total)} users` : "No users match.";
    $("page-label").textContent = data.total ? `Page ${data.page + 1} of ${Math.max(1, Math.ceil(data.total / data.size))}` : "";
    $("prev-btn").disabled = data.page <= 0;
    $("next-btn").disabled = to >= data.total;
    if (!data.rows.length) {
      body.append(el("tr", null, el("td", { colspan: "11", class: "empty", text: "No users match these filters." })));
      return;
    }
    for (const r of data.rows) {
      body.append(el("tr", { onclick: () => { location.hash = "#/user/" + r.id; } },
        el("td", { class: "name-cell" }, r.displayName || "—",
          r.suspended ? el("span", { class: "badge bad ml", text: "Suspended" }) : null),
        el("td", { text: r.email || "—" }),
        el("td", { text: date(r.joined), title: dateTime(r.joined) }),
        el("td", { text: ago(r.lastActive), title: dateTime(r.lastActive) }),
        el("td", null, bankBadge(r.bank)),
        el("td", { text: r.card ? "Yes" : "—" }),
        el("td", { text: platformText(r.platforms) }),
        el("td", { class: "num", text: num(r.sentCount) }),
        el("td", { class: "num", text: usd(r.sentCents) }),
        el("td", { class: "num", text: num(r.recvCount) }),
        el("td", { class: "num", text: usd(r.recvCents) })));
    }
  }

  document.querySelectorAll("th[data-sort]").forEach((th) => th.addEventListener("click", () => {
    const key = th.dataset.sort;
    if (state.sort === key) state.dir = state.dir === "asc" ? "desc" : "asc";
    else { state.sort = key; state.dir = key === "name" || key === "email" ? "asc" : "desc"; }
    state.page = 0;
    loadUsers();
  }));
  let qTimer;
  $("f-q").addEventListener("input", () => {
    clearTimeout(qTimer);
    qTimer = setTimeout(() => { state.page = 0; loadUsers(); }, 300);
  });
  Object.values(FILTER_IDS).filter((id) => id !== "f-q").forEach((id) =>
    $(id).addEventListener("change", () => { state.page = 0; loadUsers(); }));
  $("f-clear").addEventListener("click", () => {
    Object.values(FILTER_IDS).forEach((id) => { $(id).value = ""; });
    state.page = 0;
    loadUsers();
  });
  $("page-size").addEventListener("change", () => { state.size = parseInt($("page-size").value, 10) || 50; state.page = 0; loadUsers(); });
  $("prev-btn").addEventListener("click", () => { if (state.page > 0) { state.page--; loadUsers(); } });
  $("next-btn").addEventListener("click", () => { state.page++; loadUsers(); });

  $("export-btn").addEventListener("click", async () => {
    const btn = $("export-btn");
    btn.disabled = true;
    try {
      const res = await api("/console/api/users.csv?" + currentParams().toString());
      if (!res.ok) throw new Error("Export failed (" + res.status + ")");
      const blob = await res.blob();
      const cd = res.headers.get("Content-Disposition") || "";
      const m = /filename="([^"]+)"/.exec(cd);
      const a = el("a", { href: URL.createObjectURL(blob), download: m ? m[1] : "taptaptips-users.csv" });
      document.body.append(a);
      a.click();
      setTimeout(() => { URL.revokeObjectURL(a.href); a.remove(); }, 1000);
    } catch (e) {
      if (e.message !== "unauthorized") toast(e.message);
    } finally {
      btn.disabled = false;
    }
  });

  // ── User detail ───────────────────────────────────────────────────────────
  let avatarUrl = null;

  function statusBadge(s) {
    const cls = { SUCCEEDED: "good", RELEASED: "good", PAID: "good", QUALIFIED: "info", HELD: "warn", PENDING: "warn",
      PAYING: "warn", AWAITING_PAYMENT: "warn", RELEASING: "warn", FAILED: "bad", DISPUTED: "bad", REFUNDED: "", REJECTED: "bad",
      BLOCKED: "bad", CAPPED: "" }[s];
    return el("span", { class: "badge " + (cls || ""), text: s || "—" });
  }
  function kv(pairs) {
    return el("dl", { class: "kv" }, pairs.filter(Boolean).flatMap(([k, v]) => [el("dt", { text: k }), el("dd", null, v == null ? "—" : v)]));
  }
  function copyBtn(text) {
    return el("button", { type: "button", class: "ghost copy", onclick: (e) => {
      e.stopPropagation();
      navigator.clipboard && navigator.clipboard.writeText(text).then(() => toast("Copied"), () => toast("Couldn't copy"));
    } }, "Copy");
  }
  function userLink(id, name, fallback) {
    if (!id) return el("span", { class: "muted", text: fallback || "—" });
    return el("a", { href: "#/user/" + id, text: name || "(no name)" });
  }
  function simpleTable(cols, rows, empty) {
    if (!rows || !rows.length) return el("p", { class: "muted", text: empty });
    return el("div", { class: "table-wrap" }, el("table", null,
      el("thead", null, el("tr", null, cols.map((c) => el("th", { class: c.num ? "num" : null, text: c.label })))),
      el("tbody", null, rows.map((r) => el("tr", null, cols.map((c) => {
        const v = c.render(r);
        return el("td", { class: c.num ? "num" : null }, v instanceof Node ? v : (v == null ? "—" : String(v)));
      }))))));
  }
  function stripeUrl(kind, id, test) {
    const base = "https://dashboard.stripe.com/" + (test ? "test/" : "");
    return base + (kind === "account" ? "connect/accounts/" : "customers/") + encodeURIComponent(id);
  }

  async function loadUser(id) {
    const host = clear($("user-detail"));
    host.append(el("p", { class: "muted", text: "Loading…" }));
    let d;
    try { d = await getJson("/console/api/users/" + encodeURIComponent(id)); }
    catch (e) { if (e.message !== "unauthorized") { clear(host).append(el("p", { class: "error", text: e.message })); } return; }
    clear(host);
    const s = d.summary, p = d.profile;

    const avatar = el("div", { class: "avatar", text: (s.displayName || "?").slice(0, 1).toUpperCase() });
    if (p.hasAvatar) {
      api("/console/api/users/" + encodeURIComponent(id) + "/avatar").then((r) => r.ok ? r.blob() : null).then((b) => {
        if (!b) return;
        if (avatarUrl) URL.revokeObjectURL(avatarUrl);
        avatarUrl = URL.createObjectURL(b);
        avatar.replaceWith(el("img", { class: "avatar", src: avatarUrl, alt: "" }));
      }).catch(() => {});
    }

    host.append(el("div", { class: "profile" }, avatar,
      el("div", null,
        el("h2", { text: s.displayName || "(no name)" }),
        el("div", { class: "muted", text: s.email || "" }),
        el("div", { class: "badges" },
          bankBadge(s.bank, true),
          s.card ? el("span", { class: "badge info", text: "Card on file" }) : null,
          s.receiver ? el("span", { class: "badge info", text: "Receives tips" }) : null,
          s.suspended ? el("span", { class: "badge bad", text: "Suspended" }) : null,
          s.platforms.map((x) => el("span", { class: "badge", text: x === "ios" ? "iPhone" : "Android" }))))));

    host.append(adminActions(s));

    host.append(el("div", { class: "cards" },
      card("Tips sent", usd(s.sentCents), num(s.sentCount) + " completed"),
      card("Tips received", usd(s.recvCents), num(s.recvCount) + " completed"),
      card("Joined", date(s.joined), ago(s.joined)),
      card("Last active", ago(s.lastActive), dateTime(s.lastActive))));

    const test = d.stripeTestMode;
    host.append(el("div", { class: "grid2" },
      el("div", { class: "panel" }, el("h3", { text: "Account" }), kv([
        ["User ID", el("span", null, el("span", { class: "mono", text: s.id }), copyBtn(s.id))],
        ["Email", s.email],
        ["Joined", dateTime(s.joined)],
        ["Last app use", p.lastAppUse ? dateTime(p.lastAppUse) : "Not recorded yet"],
        ["Profile updated", dateTime(p.updatedAt)],
        ["Referral code", d.referralCode ? el("span", { class: "mono", text: d.referralCode }) : null],
        ["Referred by", d.referredBy ? el("span", null, userLink(d.referredBy.id, d.referredBy.displayName), " ", statusBadge(d.referredBy.status)) : null],
        s.suspended ? ["Suspended", (p.suspendedAt ? dateTime(p.suspendedAt) + " — " : "") + (p.suspensionReason || "no reason given")] : null
      ])),
      el("div", { class: "panel" }, el("h3", { text: "Payments" }), kv([
        ["Payout account", p.stripeAccountId
          ? el("a", { href: stripeUrl("account", p.stripeAccountId, test), target: "_blank", rel: "noopener noreferrer", class: "mono", text: p.stripeAccountId })
          : null],
        ["Payouts", s.bank === "linked" ? "Enabled" : s.bank === "started" ? "Setup started, not finished" : "Not set up"],
        ["Bank", p.bankName ? p.bankName + (p.bankLast4 ? " ••" + p.bankLast4 : "") : null],
        ["Stripe last checked", p.stripeLastChecked ? dateTime(p.stripeLastChecked) : null],
        ["Customer (cards)", p.stripeCustomerId
          ? el("a", { href: stripeUrl("customer", p.stripeCustomerId, test), target: "_blank", rel: "noopener noreferrer", class: "mono", text: p.stripeCustomerId })
          : null],
        ["Card on file", s.card ? "Yes" : "No"]
      ]))));

    const breakdownCols = [
      { label: "Status", render: (r) => statusBadge(r.status) },
      { label: "Tips", num: true, render: (r) => num(r.count) },
      { label: "Amount", num: true, render: (r) => usd(r.cents) }
    ];
    host.append(el("div", { class: "grid2" },
      el("div", { class: "panel" }, el("h3", { text: "Sent, by status" }), simpleTable(breakdownCols, d.sentByStatus, "No tips sent.")),
      el("div", { class: "panel" }, el("h3", { text: "Received, by status" }), simpleTable(breakdownCols, d.receivedByStatus, "No tips received."))));

    const tipCols = (otherLabel, guestLabel) => [
      { label: "When", render: (r) => dateTime(r.at) },
      { label: otherLabel, render: (r) => userLink(r.otherId, r.otherName, guestLabel) },
      { label: "Status", render: (r) => statusBadge(r.status) },
      { label: "Amount", num: true, render: (r) => usd(r.cents) },
      { label: "Receiver got", num: true, render: (r) => (r.netCents == null ? "—" : usd(r.netCents)) }
    ];
    host.append(el("div", { class: "panel" }, el("h3", { text: "Tips received (latest 100)" }),
      simpleTable(tipCols("From", "Guest (web link)"), d.tipsReceived, "No tips received.")));
    host.append(el("div", { class: "panel" }, el("h3", { text: "Tips sent (latest 100)" }),
      simpleTable(tipCols("To", "—"), d.tipsSent, "No tips sent.")));

    host.append(el("div", { class: "panel" }, el("h3", { text: "Held tips (waiting on this user's bank)" }), simpleTable([
      { label: "Created", render: (r) => dateTime(r.at) },
      { label: "Status", render: (r) => statusBadge(r.status) },
      { label: "Sender paid", num: true, render: (r) => usd(r.grossCents) },
      { label: "User gets", num: true, render: (r) => usd(r.netCents) },
      { label: "Refund date", render: (r) => (r.status === "HELD" ? date(r.expiresAt) : "—") },
      { label: "Paid out", render: (r) => (r.releasedAt ? dateTime(r.releasedAt) : r.refundedAt ? "Refunded " + date(r.refundedAt) : "—") }
    ], d.heldTips, "No held tips.")));

    host.append(el("div", { class: "panel" }, el("h3", { text: "People this user referred" }), simpleTable([
      { label: "When", render: (r) => dateTime(r.at) },
      { label: "User", render: (r) => userLink(r.id, r.displayName) },
      { label: "Status", render: (r) => statusBadge(r.status) },
      { label: "Reward", num: true, render: (r) => usd(r.rewardCents) }
    ], d.referralsMade, "No referrals.")));

    host.append(el("div", { class: "grid2" },
      el("div", { class: "panel" }, el("h3", { text: "Signing devices" }), simpleTable([
        { label: "Platform", render: (r) => (r.platform === "ios" ? "iPhone" : r.platform === "android" ? "Android" : r.platform) },
        { label: "Name", render: (r) => r.name },
        { label: "Last seen", render: (r) => dateTime(r.lastSeen) },
        { label: "Active", render: (r) => (r.active === false ? "No" : "Yes") }
      ], d.devices, "No devices registered.")),
      el("div", { class: "panel" }, el("h3", { text: "Push notifications" }), simpleTable([
        { label: "Platform", render: (r) => (r.platform === "ios" ? "iPhone" : r.platform === "android" ? "Android" : r.platform) },
        { label: "Device", render: (r) => r.device },
        { label: "Updated", render: (r) => dateTime(r.updated) },
        { label: "Working", render: (r) => (r.active ? el("span", { class: "badge good", text: "Yes" }) : el("span", { class: "badge bad", text: "No" })) }
      ], d.pushTokens, "No push tokens — this user won't get notifications."))));
  }

  // ── Writes (settings + ban list, served by /admin/api) ────────────────────
  // Same session as the reports. The server also needs ADMIN_PASSWORD to be
  // 16+ characters for anything that changes data; otherwise it answers 503.
  async function postJson(path, body) {
    const res = await api(path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body || {})
    });
    const data = await safeJson(res);
    if (!res.ok) throw new Error(data.error || ("Request failed (" + res.status + ")"));
    return data;
  }
  function showMsg(node, text, kind) {
    node.className = "msg" + (kind ? " " + kind : "");
    node.textContent = text || "";
  }

  // ── Settings ──────────────────────────────────────────────────────────────
  function settingShow(s, v) {
    if (s.type === "BOOL") return v === "true" ? "On" : "Off";
    if (s.type === "CENTS") return usd(Number(v));
    return v;
  }

  async function loadSettings() {
    const host = $("settings-groups");
    $("settings-error").textContent = "";
    if (!host.firstChild) host.append(el("p", { class: "muted", text: "Loading…" }));
    try {
      renderSettings(await getJson("/admin/api/settings"));
    } catch (e) {
      if (e.message !== "unauthorized") { clear(host); $("settings-error").textContent = e.message; }
    }
  }

  function renderSettings(list) {
    const host = clear($("settings-groups"));
    const order = [];
    const byGroup = {};
    list.forEach((s) => {
      if (!byGroup[s.group]) { byGroup[s.group] = []; order.push(s.group); }
      byGroup[s.group].push(s);
    });
    order.forEach((g) => {
      host.append(el("h2", { class: "section-title", text: g }),
        el("div", { class: "panel list" }, byGroup[g].map(settingRow)));
    });
  }

  function settingRow(s) {
    const msg = el("p", { class: "msg", role: "status" });
    async function save(value, control) {
      if (control) control.disabled = true;
      showMsg(msg, "Saving…");
      try {
        renderSettings(await postJson("/admin/api/settings", { key: s.key, value: String(value) }));
        toast("Saved");
      } catch (e) {
        if (e.message === "unauthorized") return;
        showMsg(msg, e.message, "err");
        if (control) {
          control.disabled = false;
          if (control.type === "checkbox") control.checked = !control.checked;
        }
      }
    }

    const head = el("div", { class: "setting-head" }, el("div", { class: "setting-label", text: s.label }));
    const row = el("div", { class: "setting" }, head);

    if (s.type === "BOOL") {
      const cb = el("input", { type: "checkbox", "aria-label": s.label });
      cb.checked = s.value === "true";
      cb.addEventListener("change", () => {
        if (!cb.checked && !confirm("Turn off “" + s.label + "”?")) { cb.checked = true; return; }
        save(cb.checked ? "true" : "false", cb);
      });
      head.append(el("label", { class: "switch" }, cb, el("span", { class: "slider" })));
    } else {
      const input = el("input", { "aria-label": s.label });
      let field = input;
      if (s.type === "CENTS") {
        input.type = "number"; input.step = "0.01"; input.min = "0";
        input.value = (Number(s.value) / 100).toFixed(2);
        field = el("div", { class: "money" }, el("span", { text: "$" }), input);
      } else if (s.type === "NUMBER") {
        input.type = "number"; input.step = "1"; input.value = s.value;
      } else {
        input.type = "text"; input.value = s.value;
      }
      const btn = el("button", { type: "button", text: "Save" });
      btn.addEventListener("click", () => {
        let v = input.value.trim();
        if (s.type === "CENTS") {
          const n = Math.round(parseFloat(v) * 100);
          if (isNaN(n)) { showMsg(msg, "Enter an amount.", "err"); return; }
          v = String(n);
        }
        save(v, btn);
      });
      input.addEventListener("keydown", (e) => { if (e.key === "Enter") btn.click(); });
      row.append(el("div", { class: "setting-edit" }, field, btn));
    }

    row.append(el("p", { class: "setting-help", text: s.help }));
    const meta = el("div", { class: "setting-meta" });
    if (s.overridden) {
      meta.append(el("span", { class: "badge warn", text: "Changed " + dateTime(s.updatedAt) }),
        el("span", { text: "Default: " + settingShow(s, s.defaultValue) }),
        el("button", { type: "button", class: "ghost small-btn", text: "Reset to default", onclick: async () => {
          if (!confirm("Reset “" + s.label + "” to " + settingShow(s, s.defaultValue) + "?")) return;
          try { renderSettings(await postJson("/admin/api/settings/reset", { key: s.key })); toast("Reset"); }
          catch (e) { if (e.message !== "unauthorized") showMsg(msg, e.message, "err"); }
        } }));
    } else {
      meta.append(el("span", { text: "Using default" }));
    }
    row.append(meta, msg);
    return row;
  }

  // ── Fraud: ban list ───────────────────────────────────────────────────────
  async function loadBans() {
    $("bans-error").textContent = "";
    try { renderBans(await getJson("/admin/api/bans")); }
    catch (e) { if (e.message !== "unauthorized") $("bans-error").textContent = e.message; }
  }

  function renderBans(list) {
    const host = clear($("ban-list"));
    if (!list.length) { host.append(el("p", { class: "muted", text: "Nothing banned yet." })); return; }
    list.forEach((b) => {
      host.append(el("div", { class: "ban-row" },
        el("div", { class: "ban-main" },
          el("div", null, el("span", { class: "badge bad", text: b.type }), " ", el("span", { class: "mono", text: b.value })),
          el("div", { class: "muted small" },
            b.reason || "",
            b.sourceUserId ? el("span", null, " · ", el("a", { href: "#/user/" + b.sourceUserId, text: "view user" })) : null,
            " · added " + dateTime(b.createdAt))),
        el("button", { type: "button", class: "ghost small-btn", text: "Remove", onclick: async () => {
          if (!confirm("Take " + b.type + " " + b.value + " off the ban list?")) return;
          try { renderBans(await postJson("/admin/api/bans/remove", { type: b.type, value: b.value })); toast("Removed"); }
          catch (e) { if (e.message !== "unauthorized") toast(e.message); }
        } })));
    });
  }

  function splitIps(text) {
    return (text || "").split(",").map((x) => x.trim()).filter((x) => x.length > 0);
  }
  function banSummary(r) {
    const lines = ["Banned " + r.email + ". Added " + r.added.length + " to the ban list" +
      (r.alreadyBanned.length ? " (" + r.alreadyBanned.length + " were already on it)." : ".")];
    if (r.payoutsPaused === true) lines.push("Stripe payouts paused.");
    (r.notes || []).forEach((n) => lines.push(n));
    return lines.join(" ");
  }
  function unbanSummary(r) {
    const lines = ["Unbanned " + r.email + ". Removed " + r.removed.length + " from the ban list."];
    if (r.payoutsResumed === true) lines.push("Stripe payouts turned back on.");
    (r.notes || []).forEach((n) => lines.push(n));
    return lines.join(" ");
  }

  $("ban-form").addEventListener("submit", async (e) => {
    e.preventDefault();
    const msg = $("ban-msg");
    const userId = $("ban-user").value.trim();
    const reason = $("ban-reason").value.trim();
    if (!userId || !reason) { showMsg(msg, "Enter a user ID and a reason.", "err"); return; }
    if (!confirm("Ban user " + userId + "? This suspends the account and pauses its payouts.")) return;
    const btn = $("ban-btn");
    btn.disabled = true;
    showMsg(msg, "Banning…");
    try {
      const j = await postJson("/admin/api/ban", { userId, reason, extraIps: splitIps($("ban-ips").value) });
      showMsg(msg, banSummary(j.result), "ok");
      renderBans(j.bans);
      $("ban-user").value = ""; $("ban-reason").value = ""; $("ban-ips").value = "";
      cache.overview = null;
    } catch (err) {
      if (err.message !== "unauthorized") showMsg(msg, err.message, "err");
    } finally {
      btn.disabled = false;
    }
  });

  // Ban / unban box on a user's page.
  function adminActions(s) {
    const msg = el("p", { class: "msg", role: "status" });
    const box = el("div", { class: "panel" }, el("h3", { text: "Admin actions" }));

    if (s.suspended) {
      const btn = el("button", { type: "button", text: "Unban" });
      btn.addEventListener("click", async () => {
        if (!confirm("Unban " + (s.email || s.id) + "? This unsuspends the account, removes its ban-list entries " +
          "and turns its Stripe payouts back on.")) return;
        btn.disabled = true;
        showMsg(msg, "Unbanning…");
        try {
          const j = await postJson("/admin/api/unban", { userId: s.id });
          cache.overview = null;
          toast("Unbanned");
          loadUser(s.id).then(() => { const m = document.querySelector("#user-detail .admin-msg"); if (m) showMsg(m, unbanSummary(j.result), "ok"); });
        } catch (e) {
          btn.disabled = false;
          if (e.message !== "unauthorized") showMsg(msg, e.message, "err");
        }
      });
      box.append(el("p", { class: "muted small", text: "This account is suspended. Unbanning removes the ban-list " +
        "entries that came from it — including any it shares with another banned account (like the same bank) — " +
        "and turns its Stripe payouts back on." }), el("div", null, btn));
    } else {
      const reason = el("input", { type: "text", placeholder: "Reason (private)", "aria-label": "Reason" });
      const ips = el("input", { type: "text", placeholder: "Extra IP addresses, comma-separated (optional)", "aria-label": "Extra IP addresses" });
      const btn = el("button", { type: "button", class: "danger", text: "Ban this user" });
      btn.addEventListener("click", async () => {
        if (!reason.value.trim()) { showMsg(msg, "Enter a reason.", "err"); return; }
        if (!confirm("Ban " + (s.email || s.id) + "? This suspends the account and pauses its payouts.")) return;
        btn.disabled = true;
        showMsg(msg, "Banning…");
        try {
          const j = await postJson("/admin/api/ban", { userId: s.id, reason: reason.value.trim(), extraIps: splitIps(ips.value) });
          cache.overview = null;
          toast("Banned");
          loadUser(s.id).then(() => { const m = document.querySelector("#user-detail .admin-msg"); if (m) showMsg(m, banSummary(j.result), "ok"); });
        } catch (e) {
          btn.disabled = false;
          if (e.message !== "unauthorized") showMsg(msg, e.message, "err");
        }
      });
      box.append(el("p", { class: "muted small", text: "Suspends the account, pauses its Stripe payouts and puts its " +
        "email, internet addresses and payout bank on the ban list. The user only sees “Violation of Terms of Service”." }),
        el("div", { class: "stack" }, reason, ips, el("div", null, btn)));
    }
    msg.classList.add("admin-msg");
    box.append(msg);
    return box;
  }

  // ── start ─────────────────────────────────────────────────────────────────
  if (getToken()) showApp(); else showLogin();
})();
