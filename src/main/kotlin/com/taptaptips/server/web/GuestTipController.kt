package com.taptaptips.server.web

import com.taptaptips.server.service.GuestTipService
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.*
import org.springframework.web.util.HtmlUtils
import java.time.Duration
import java.util.UUID

/**
 * Tipping WITHOUT the app.
 *
 *   GET  /t/{code}            public tip page (Apple Pay / Google Pay / card)
 *   GET  /t/{code}/photo      receiver's profile photo for that page
 *   POST /t/{code}/intent     {amountCents} → {clientSecret}
 *   POST /t/{code}/complete   {paymentIntentId} → {status, amountCents}
 *   GET  /tiplink/me          (logged in) the receiver's own link for QR / NFC
 *
 * Every /t/ path must be permitAll in SecurityConfig.
 */
@RestController
class GuestTipController(
    private val guest: GuestTipService,
    @Value("\${stripe.publishable.key}") private val publishableKey: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // ── App (receiver) ───────────────────────────────────────────────────────

    @GetMapping("/tiplink/me")
    fun myLink(): GuestTipService.MyLink {
        val userId = UUID.fromString(SecurityContextHolder.getContext().authentication.name)
        return guest.myLink(userId)
    }

    // ── Public page ──────────────────────────────────────────────────────────

    @GetMapping("/t/{code}", produces = [MediaType.TEXT_HTML_VALUE])
    fun page(@PathVariable code: String): ResponseEntity<String> {
        val r = guest.findReceiver(code)
        val html = when {
            r == null -> messagePage("This tip link isn't valid", "Check the link or tap the tag again.", null)
            else -> guest.unavailableReason(r)?.let { messagePage("Can't tip right now", it, r.code) }
                ?: tipPage(r)
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .contentType(MediaType.TEXT_HTML)
            .body(html)
    }

    @GetMapping("/t/{code}/photo")
    fun photo(@PathVariable code: String): ResponseEntity<ByteArray> {
        val bytes = guest.findReceiver(code)?.user?.profilePicture
            ?: return ResponseEntity.notFound().build()
        val type = if (bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte())
            MediaType.IMAGE_PNG else MediaType.IMAGE_JPEG
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
            .contentType(type)
            .body(bytes)
    }

    @PostMapping("/t/{code}/intent")
    fun intent(
        @PathVariable code: String,
        @RequestBody body: Map<String, Any?>,
        request: HttpServletRequest
    ): ResponseEntity<Map<String, Any?>> {
        val cents = (body["amountCents"] as? Number)?.toLong()
            ?: return ResponseEntity.badRequest().body(mapOf("error" to "Choose an amount."))
        return try {
            val secret = guest.createIntent(code, cents, clientIp(request))
            ResponseEntity.ok(mapOf("clientSecret" to secret))
        } catch (e: GuestTipService.GuestTipException) {
            ResponseEntity.badRequest().body(mapOf("error" to e.message))
        } catch (e: Exception) {
            log.error("Guest tip intent failed for $code", e)
            ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(mapOf("error" to "Payments are temporarily unavailable. Please try again."))
        }
    }

    @PostMapping("/t/{code}/complete")
    fun complete(@PathVariable code: String, @RequestBody body: Map<String, Any?>): Map<String, Any?> {
        val pi = (body["paymentIntentId"] as? String)?.takeIf { it.startsWith("pi_") && it.length < 80 }
            ?: return mapOf("status" to "unknown")
        val (status, cents) = guest.completeForPage(code, pi)
        return mapOf("status" to status, "amountCents" to cents)
    }

    private fun clientIp(req: HttpServletRequest): String =
        req.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: req.remoteAddr ?: "unknown"

    // ── HTML ─────────────────────────────────────────────────────────────────
    // Note: the JS avoids "$" (Kotlin string templates); $ is a dollar sign.

    private fun esc(s: String) = HtmlUtils.htmlEscape(s)

    private fun tipPage(r: GuestTipService.Receiver): String {
        val name = esc(r.publicName)
        val code = r.code
        val photo = if (r.user.profilePicture != null)
            "<img class=\"avatar\" src=\"/t/$code/photo\" alt=\"\">"
        else "<div class=\"avatar initials\">${esc(r.publicName.take(1))}</div>"
        return shell("Tip $name", """
<main id="app" data-code="$code" data-pk="${esc(publishableKey)}" data-name="$name"
      data-min="${guest.minCents}" data-max="${guest.maxCents}">
  <section id="form">
    $photo
    <h1>Tip $name</h1>
    <div class="chips" role="group" aria-label="Tip amount">
      <button type="button" class="chip" data-c="200">&#36;2</button>
      <button type="button" class="chip on" data-c="500">&#36;5</button>
      <button type="button" class="chip" data-c="1000">&#36;10</button>
      <button type="button" class="chip" data-c="2000">&#36;20</button>
    </div>
    <label class="custom">Other amount &#36;
      <input id="custom" type="number" inputmode="decimal" min="1" step="0.01" placeholder="0.00">
    </label>
    <div id="express"></div>
    <button type="button" id="cardBtn" class="link">Pay with card instead</button>
    <div id="cardBox" hidden>
      <div id="card"></div>
      <button type="button" id="payBtn" class="btn">Tip</button>
    </div>
    <p id="err" class="err" role="alert"></p>
    <p class="note">Secure payment by Stripe. No app or account needed.<br>A small processing fee is taken from the tip.</p>
  </section>
  <section id="done" hidden>
    <div class="check">&#10003;</div>
    <h1 id="doneTitle">Thank you!</h1>
    <p id="doneText"></p>
    <a class="btn" href="/r/$code">Get TapTapTips</a>
    <p class="note">Tip faster next time with the app.</p>
  </section>
</main>
<script src="https://js.stripe.com/v3/"></script>
<script>
(function () {
  var app = document.getElementById('app');
  var code = app.dataset.code, name = app.dataset.name;
  var min = +app.dataset.min, max = +app.dataset.max;
  var amount = 500, busy = false;
  var err = document.getElementById('err');
  function fmt(c) { return '\u0024' + (c % 100 === 0 ? (c / 100) : (c / 100).toFixed(2)); }
  function fail(m) { busy = false; err.textContent = m || 'Something went wrong. Please try again.'; }
  function showDone(c) {
    document.getElementById('form').hidden = true;
    document.getElementById('done').hidden = false;
    document.getElementById('doneText').textContent =
      name + ' got your ' + (c ? fmt(c) + ' ' : '') + 'tip.';
  }
  function complete(pi, tries) {
    tries = tries || 0;
    fetch('/t/' + code + '/complete', { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ paymentIntentId: pi }) })
      .then(function (r) { return r.json(); })
      .then(function (d) {
        if (d.status === 'succeeded') return showDone(d.amountCents);
        if (d.status === 'pending' && tries < 10) return setTimeout(function () { complete(pi, tries + 1); }, 1500);
        fail(d.status === 'pending' ? 'Your payment is processing. You can close this page.' : 'The payment did not go through.');
      })
      .catch(function () { fail('Could not confirm the payment. If you were charged, the tip will still arrive.'); });
  }

  // Back from a bank verification redirect.
  var q = new URLSearchParams(location.search);
  if (q.get('payment_intent')) { document.getElementById('form').hidden = true; complete(q.get('payment_intent')); return; }

  var stripe = Stripe(app.dataset.pk);
  var elements = stripe.elements({ mode: 'payment', amount: amount, currency: 'usd',
    paymentMethodTypes: ['card'],
    appearance: { theme: 'night', variables: { colorPrimary: '#4CAF50', borderRadius: '12px' } } });

  var express = elements.create('expressCheckout', {
    buttonType: { applePay: 'tip', googlePay: 'pay' }, buttonHeight: 52 });
  express.mount('#express');
  express.on('ready', function (e) {
    if (!e.availablePaymentMethods) openCard();   // no Apple/Google Pay on this device
  });
  express.on('confirm', function () { pay(); });

  var cardEl = null;
  function openCard() {
    document.getElementById('cardBox').hidden = false;
    document.getElementById('cardBtn').hidden = true;
    if (!cardEl) {
      cardEl = elements.create('payment', { layout: 'tabs', wallets: { applePay: 'never', googlePay: 'never' } });
      cardEl.mount('#card');
    }
  }
  document.getElementById('cardBtn').onclick = openCard;
  document.getElementById('payBtn').onclick = function () { pay(); };

  function setAmount(c) {
    amount = c; err.textContent = '';
    elements.update({ amount: c });
    document.getElementById('payBtn').textContent = 'Tip ' + fmt(c);
  }
  var chips = document.querySelectorAll('.chip');
  chips.forEach(function (b) {
    b.onclick = function () {
      chips.forEach(function (x) { x.classList.remove('on'); });
      b.classList.add('on'); document.getElementById('custom').value = '';
      setAmount(+b.dataset.c);
    };
  });
  document.getElementById('custom').oninput = function (e) {
    var c = Math.round(parseFloat(e.target.value) * 100);
    if (!c) return;
    chips.forEach(function (x) { x.classList.remove('on'); });
    if (c < min) return fail('The minimum tip is ' + fmt(min) + '.');
    if (c > max) return fail('The maximum tip is ' + fmt(max) + '.');
    setAmount(c);
  };
  setAmount(amount);

  function pay() {
    if (busy) return; busy = true; err.textContent = '';
    elements.submit().then(function (s) {
      if (s.error) throw new Error(s.error.message);
      return fetch('/t/' + code + '/intent', { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ amountCents: amount }) });
    }).then(function (r) {
      return r.json().then(function (d) { if (!r.ok) throw new Error(d.error); return d; });
    }).then(function (d) {
      return stripe.confirmPayment({ elements: elements, clientSecret: d.clientSecret,
        confirmParams: { return_url: location.origin + '/t/' + code }, redirect: 'if_required' });
    }).then(function (res) {
      if (res.error) throw new Error(res.error.message);
      complete(res.paymentIntent.id);
    }).catch(function (e) { fail(e.message); });
  }
})();
</script>""")
    }

    private fun messagePage(title: String, text: String, code: String?): String = shell(esc(title), """
<main>
  <h1>${esc(title)}</h1>
  <p>${esc(text)}</p>
  <a class="btn" href="${if (code != null) "/r/$code" else "/r/APP"}">Get TapTapTips</a>
</main>""")

    private fun shell(title: String, body: String): String = """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>$title · TapTapTips</title>
<style>
  :root { --green:#4CAF50; --bg:#000; --card:#141414; --muted:#9a9a9a; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:#fff; min-height:100vh;
         font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Arial,sans-serif;
         display:flex; justify-content:center; padding:32px 16px; }
  main { width:100%; max-width:400px; text-align:center; }
  h1 { font-size:26px; margin:12px 0 20px; }
  p { color:var(--muted); line-height:1.5; }
  .avatar { width:112px; height:112px; border-radius:50%; object-fit:cover; border:3px solid #fff; }
  .initials { display:inline-flex; align-items:center; justify-content:center; background:#222; font-size:44px; font-weight:700; }
  .chips { display:grid; grid-template-columns:repeat(4,1fr); gap:8px; margin-bottom:12px; }
  .chip { padding:14px 0; border-radius:12px; border:1px solid #333; background:var(--card); color:#fff;
          font-size:18px; font-weight:600; cursor:pointer; font-family:inherit; }
  .chip.on { border-color:var(--green); background:#13301a; }
  .custom { display:flex; align-items:center; gap:8px; justify-content:center; color:var(--muted); margin:0 0 20px; }
  .custom input { width:110px; padding:10px; border-radius:10px; border:1px solid #333; background:var(--card);
                  color:#fff; font-size:16px; }
  #express { min-height:52px; margin-bottom:12px; }
  #card { margin:12px 0; text-align:left; }
  .btn { display:block; width:100%; padding:16px; border-radius:14px; border:0; background:var(--green); color:#fff;
         font-size:17px; font-weight:600; text-decoration:none; cursor:pointer; font-family:inherit; }
  .link { background:none; border:0; color:var(--green); font-size:15px; cursor:pointer; padding:8px; font-family:inherit; }
  .err { color:#ff6b6b; min-height:20px; }
  .note { font-size:13px; }
  .check { width:84px; height:84px; margin:24px auto 0; border-radius:50%; background:var(--green);
           font-size:48px; line-height:84px; }
  button:focus-visible, a:focus-visible, input:focus-visible { outline:3px solid #fff; outline-offset:2px; }
</style>
</head>
<body>
$body
</body>
</html>
""".trimIndent()
}