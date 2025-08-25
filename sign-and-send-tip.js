// sign-and-send-tip.js
// Usage (Windows CMD):
//   node sign-and-send-tip.js --base http://localhost:8080 --token "<JWT>" --sender <SENDER_UUID> --receiver <RECEIVER_UUID> --amount 5.00
//
// Notes:
// - Generates device_private.pem + device_public_spki.b64 if they don't exist
// - Registers the device pubkey to /devices (once per run; server can handle duplicates)
// - Signs canonical message: id|senderId|receiverId|amount|nonce|timestamp
// - Sends POST /tips with signatureBase64 + signingDevicePublicKeyBase64

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

// --- simple args parser ---
function arg(name, def) {
  const i = process.argv.findIndex(a => a === `--${name}`);
  return i > -1 ? process.argv[i + 1] : def;
}
const BASE = arg("base", "http://localhost:8080");
const TOKEN = arg("token");
const SENDER = arg("sender");
const RECEIVER = arg("receiver");
const AMOUNT = arg("amount");

if (!TOKEN || !SENDER || !RECEIVER || !AMOUNT) {
  console.error("Usage:");
  console.error('  node sign-and-send-tip.js --base http://localhost:8080 --token "<JWT>" --sender <SENDER_UUID> --receiver <RECEIVER_UUID> --amount 5.00');
  process.exit(1);
}

// --- key files in current folder ---
const PRIV_PEM = path.resolve("device_private.pem");
const PUB_B64 = path.resolve("device_public_spki.b64");

// --- ensure keys exist ---
function ensureKeys() {
  if (fs.existsSync(PRIV_PEM) && fs.existsSync(PUB_B64)) return;
  const { privateKey, publicKey } = crypto.generateKeyPairSync("ed25519");
  fs.writeFileSync(PRIV_PEM, privateKey.export({ type: "pkcs8", format: "pem" }));
  const pubDer = publicKey.export({ type: "spki", format: "der" });
  fs.writeFileSync(PUB_B64, Buffer.from(pubDer).toString("base64"));
  console.log("✅ Generated device_private.pem and device_public_spki.b64");
}

// --- helpers ---
function uuid() {
  return crypto.randomUUID();
}
function nowMillis() {
  return Date.now();
}

async function postJson(url, body, authBearer = null) {
  const headers = { "Content-Type": "application/json" };
  if (authBearer) headers["Authorization"] = `Bearer ${authBearer}`;
  const res = await fetch(url, { method: "POST", headers, body: JSON.stringify(body) });
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = { raw: text }; }
  if (!res.ok) {
    const msg = typeof data === "object" ? JSON.stringify(data) : String(data);
    throw new Error(`HTTP ${res.status} ${res.statusText}: ${msg}`);
  }
  return data;
}

function signMessage(message) {
  const privateKey = crypto.createPrivateKey(fs.readFileSync(PRIV_PEM, "utf8"));
  const sig = crypto.sign(null, Buffer.from(message, "utf8"), privateKey);
  return sig.toString("base64");
}

(async () => {
  try {
    ensureKeys();
    const pubB64 = fs.readFileSync(PUB_B64, "utf8").trim();

    // 1) Register device pubkey
    console.log("→ Registering device public key (will ignore auth errors if already set)...");
try {
  const reg = await postJson(`${BASE}/devices`, {
    userId: SENDER,
    deviceName: "Windows Dev",
    publicKeyBase64: pubB64
  }, TOKEN);
  console.log("✓ /devices response:", reg);
} catch (e) {
  // If token is expired or policy denies, continue; device is likely already registered
  console.warn("! /devices registration skipped:", e.message);
}


    // 2) Build canonical message to sign
    const id = uuid();
    const nonce = uuid().replace(/-/g, "");
    const ts = nowMillis();
    // IMPORTANT: match the server’s canonical format used in your API:
    const amountPlain = Number(AMOUNT).toString(); // "5.00" -> "5"
    const message = `${id}|${SENDER}|${RECEIVER}|${amountPlain}|${nonce}|${ts}`;


    // 3) Sign
    const signatureBase64 = signMessage(message);
    console.log("→ Signed message:", message);
    console.log("→ Signature (base64):", signatureBase64);

    // 4) Send tip
    console.log("→ Sending tip…");
    const tipResp = await postJson(`${BASE}/tips`, {
      id,
      senderId: SENDER,
      receiverId: RECEIVER,
      amount: Number(AMOUNT),                  // decimal (no cents column)
      nonce,
      timestamp: ts,
      signatureBase64,
      signingDevicePublicKeyBase64: pubB64
    }, TOKEN);
    console.log("✓ /tips response:", tipResp);
    console.log("\n🎉 Done. Tip ID:", id);
  } catch (e) {
    console.error("✖ Error:", e.message);
    process.exit(1);
  }
})();
