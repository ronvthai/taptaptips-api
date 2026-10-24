package com.taptaptips.server.web

import com.taptaptips.server.service.ReferralService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * GET /r/{code} — PUBLIC landing page that referral QR codes and share links open.
 *
 *  - Android: "Get it on Google Play" carries the code through the install
 *    (Play Install Referrer), so the app pre-fills it on the sign-up screen.
 *  - iPhone: "Copy code & get the app" copies the code, then opens the App Store;
 *    the sign-up screen has a Paste button. "Open TapTapTips" (for people who
 *    already have the app) opens taptaptips://r/CODE, which pre-fills it.
 *
 * While the program is off, or for an unknown code, it's a plain download page.
 *
 * Config:
 *   referral.appStoreUrl     https://apps.apple.com/app/id<your Apple ID>   (required for the iPhone button)
 *   referral.playPackage     com.app.taptaptips
 */
@RestController
class ReferralLandingController(
    private val referrals: ReferralService,
    @Value("\${referral.appStoreUrl:}") private val appStoreUrl: String,
    @Value("\${referral.playPackage:com.app.taptaptips}") private val playPackage: String
) {
    private val codeRegex = Regex("^[A-Z0-9]{4,12}$")
    private val validStoreUrl = Regex("^https://(apps|itunes)\\.apple\\.com/[A-Za-z0-9/_.-]*id[0-9]+[A-Za-z0-9?=&_.-]*$")

    @GetMapping("/r/{code}", produces = [MediaType.TEXT_HTML_VALUE])
    fun landing(@PathVariable code: String): ResponseEntity<String> {
        val clean = code.trim().uppercase()
        val config = referrals.publicConfig()
        // Only show a code that is well-formed, real, and while the program is on.
        val shown = clean.takeIf { config.enabled && codeRegex.matches(it) && referrals.isValidCode(it) }

        val playUrl = buildString {
            append("https://play.google.com/store/apps/details?id=").append(playPackage)
            if (shown != null) {
                append("&referrer=").append(URLEncoder.encode("ref=$shown", StandardCharsets.UTF_8))
            }
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)))
            .contentType(MediaType.TEXT_HTML)
            // A bad REFERRAL_APPSTOREURL (e.g. the "<your Apple ID>" placeholder)
            // makes Safari say "address is invalid" — fall back to "search the
            // App Store" instead of a broken button.
            .body(page(shown, playUrl, appStoreUrl.trim().takeIf { validStoreUrl.matches(it) } ?: ""))
    }

    // `code` is either null or matches [A-Z0-9]{4,12}; URLs come from config,
    // so nothing user-controlled reaches the page unescaped.
    private fun page(code: String?, playUrl: String, appStore: String): String {
        val hasCode = code != null
        val store = appStore.replace("\"", "%22")
        val hasStore = store.isNotEmpty()

        val heading = if (hasCode) "A friend invited you to TapTapTips" else "Get TapTapTips"
        val codeBlock = if (hasCode)
            "<div class=\"code\"><small>Your friend's code</small><b id=\"code\">$code</b></div>\n" +
            "<div class=\"toast\" id=\"toast\" role=\"status\"></div>"
        else ""

        val iosButton = when {
            !hasStore -> "<p>Search <b>TapTapTips</b> in the App Store.</p>"
            hasCode -> "<button class=\"btn primary\" id=\"iosGet\">Copy code &amp; get the app</button>"
            else -> "<a class=\"btn primary\" href=\"$store\">Download on the App Store</a>"
        }
        val iosExtra = if (hasCode)
            "<button class=\"btn secondary\" id=\"openApp\" data-href=\"taptaptips://r/$code\">Already have it? Open TapTapTips</button>\n" +
            "<div class=\"hint hidden\" id=\"openHint\">Looks like TapTapTips isn't on this phone yet. Tap <b>Copy code &amp; get the app</b> above.</div>\n" +
            "<div class=\"note\">When you sign up, tap <b>Paste</b> next to the referral code field.</div>"
        else ""
        val androidNote = if (hasCode)
            "<div class=\"note\">Your friend's code will be filled in for you when you sign up.</div>"
        else ""
        val otherButtons = buildString {
            if (hasStore) append("<a class=\"btn primary\" href=\"$store\">Download on the App Store</a>\n")
            append("<a class=\"btn ${if (hasStore) "secondary" else "primary"}\" href=\"$playUrl\">Get it on Google Play</a>")
            if (hasCode) append("\n<div class=\"note\">Open this page on your phone, or enter code <b>$code</b> when you sign up.</div>")
        }

        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Join TapTapTips</title>
<meta name="robots" content="noindex">
<style>
  :root { --green:#4CAF50; --bg:#000; --card:#141414; --muted:#9a9a9a; }
  * { box-sizing:border-box; }
  body { margin:0; background:var(--bg); color:#fff; min-height:100vh;
         font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Arial,sans-serif;
         display:flex; align-items:center; justify-content:center; padding:24px 16px; }
  main { width:100%; max-width:400px; text-align:center; }
  h1 { font-size:28px; line-height:1.2; margin:0 0 10px; }
  p { color:var(--muted); font-size:16px; line-height:1.5; margin:0 0 24px; }
  .code { background:var(--card); border:1px solid #2a2a2a; border-radius:16px; padding:18px 16px; margin:0 0 8px; }
  .code small { display:block; color:var(--muted); font-size:13px; margin-bottom:4px; }
  .code b { font-family:ui-monospace,Menlo,Consolas,monospace; font-size:34px; letter-spacing:4px; }
  a.btn, button.btn { display:block; width:100%; padding:16px; border-radius:14px; border:0;
         font-size:17px; font-weight:600; text-decoration:none; margin:0 0 12px; cursor:pointer; font-family:inherit; }
  .primary { background:var(--green); color:#fff; }
  .secondary { background:transparent; color:var(--green); border:1px solid var(--green) !important; }
  .hidden { display:none !important; }
  .note { font-size:13px; color:var(--muted); margin-top:16px; }
  .hint { font-size:14px; color:#FFC107; margin:-4px 0 12px; line-height:1.4; }
  .toast { color:var(--green); font-size:14px; min-height:20px; margin:0 0 12px; }
  a:focus-visible, button:focus-visible { outline:3px solid #fff; outline-offset:2px; }
</style>
</head>
<body>
<main>
  <h1>$heading</h1>
  <p>Tip the people who make your day better, right where you are. No cash or usernames &mdash; just tap.</p>
  $codeBlock
  <div id="ios" class="hidden">
    $iosButton
    $iosExtra
  </div>
  <div id="android" class="hidden">
    <a class="btn primary" href="$playUrl">Get it on Google Play</a>
    $androidNote
  </div>
  <div id="other" class="hidden">
    $otherButtons
  </div>
</main>
<script>
(function () {
  var ua = navigator.userAgent || "";
  var isIOS = /iPhone|iPad|iPod/.test(ua) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  var isAndroid = /Android/.test(ua);
  document.getElementById(isIOS ? "ios" : isAndroid ? "android" : "other").classList.remove("hidden");

  var getBtn = document.getElementById("iosGet");
  var codeEl = document.getElementById("code");
  var toast = document.getElementById("toast");
  var appStore = "$store";
  // "Already have it?" — try the app. If TapTapTips isn't installed, Safari
  // shows "address is invalid" and the page stays in front, so after a moment
  // we show what to do instead.
  var openBtn = document.getElementById("openApp");
  var openHint = document.getElementById("openHint");
  if (openBtn) {
    openBtn.addEventListener("click", function () {
      var left = false;
      var onHide = function () { if (document.hidden) left = true; };
      document.addEventListener("visibilitychange", onHide);
      window.location.href = openBtn.getAttribute("data-href");
      setTimeout(function () {
        document.removeEventListener("visibilitychange", onHide);
        if (!left && !document.hidden && openHint) openHint.classList.remove("hidden");
      }, 1500);
    });
  }

  if (getBtn) {
    getBtn.addEventListener("click", function () {
      var go = function () { window.location.href = appStore; };
      if (!codeEl || !navigator.clipboard) { go(); return; }
      navigator.clipboard.writeText(codeEl.textContent).then(function () {
        if (toast) toast.textContent = "Code copied \u2713";
        setTimeout(go, 600);
      }, go);
    });
  }
})();
</script>
</body>
</html>
""".trimIndent()
    }
}