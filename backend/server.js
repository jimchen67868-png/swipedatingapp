import crypto from "node:crypto";
import express from "express";
import rateLimit from "express-rate-limit";
import jwt from "jsonwebtoken";

const {
  WA_TOKEN,            // Meta access token
  WA_PHONE_NUMBER_ID,  // WhatsApp phone number ID (not the phone number itself)
  WA_VERIFY_TOKEN,     // any secret string you also enter in Meta's webhook settings
  WA_APP_SECRET,       // Meta app secret, used to verify webhook signatures
  WA_BUSINESS_NUMBER,  // your WhatsApp number, digits only, with country code
  JWT_SECRET,
  WA_GRAPH_VERSION = "v23.0",
  PORT = 3000,
} = process.env;

for (const [k, v] of Object.entries({ WA_TOKEN, WA_PHONE_NUMBER_ID, WA_VERIFY_TOKEN, WA_APP_SECRET, WA_BUSINESS_NUMBER, JWT_SECRET })) {
  if (!v) throw new Error(`Missing environment variable: ${k}`);
}

const PHONE = /^\+[1-9]\d{7,14}$/;
const TTL_MS = 10 * 60 * 1000;
const REF_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

// ref -> { phone (digits), code, expires, attempts, sent }
// In memory: fine for one server instance. Use Redis if you run several.
const pending = new Map();
setInterval(() => {
  for (const [ref, e] of pending) if (Date.now() > e.expires) pending.delete(ref);
}, 60_000).unref();

const makeRef = () =>
  Array.from(crypto.randomBytes(10), (b) => REF_CHARS[b % REF_CHARS.length]).join("");

const safeEqual = (a, b) => {
  const x = Buffer.from(a), y = Buffer.from(b);
  return x.length === y.length && crypto.timingSafeEqual(x, y);
};

const app = express();
app.set("trust proxy", 1);
app.use(express.json({ verify: (req, _res, buf) => { req.rawBody = buf; } }));
app.use("/auth", rateLimit({ windowMs: 15 * 60 * 1000, limit: 20 }));

app.get("/health", (_req, res) => res.json({ ok: true }));

// Step 1: the app sends the phone number; we hand back a WhatsApp link with a one-time ref.
app.post("/auth/start", (req, res) => {
  const { phone } = req.body ?? {};
  if (!PHONE.test(phone ?? "")) return res.status(400).json({ error: "Invalid phone number" });
  const ref = makeRef();
  const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");
  pending.set(ref, { phone: phone.slice(1), code, expires: Date.now() + TTL_MS, attempts: 0, sent: false });
  const link = `https://wa.me/${WA_BUSINESS_NUMBER}?text=${encodeURIComponent(`LOGIN ${ref}`)}`;
  res.json({ ref, link });
});

// Step 3: the app submits the code the user received on WhatsApp.
app.post("/auth/verify", (req, res) => {
  const { ref, code } = req.body ?? {};
  const key = String(ref ?? "").toUpperCase();
  const entry = pending.get(key);
  if (!entry || Date.now() > entry.expires) return res.status(401).json({ error: "Code expired. Start again." });
  if (!entry.sent) return res.status(400).json({ error: "Send the WhatsApp message first, then enter the code." });
  if (++entry.attempts > 5) {
    pending.delete(key);
    return res.status(429).json({ error: "Too many attempts. Start again." });
  }
  if (!safeEqual(String(code ?? ""), entry.code)) return res.status(401).json({ error: "Wrong code" });
  pending.delete(key);
  res.json({ token: jwt.sign({ sub: `+${entry.phone}` }, JWT_SECRET, { expiresIn: "30d" }) });
});

// Meta webhook verification (one-time handshake when you save the webhook URL).
app.get("/webhook", (req, res) => {
  if (req.query["hub.mode"] === "subscribe" && req.query["hub.verify_token"] === WA_VERIFY_TOKEN) {
    return res.status(200).send(req.query["hub.challenge"]);
  }
  res.sendStatus(403);
});

// Step 2: the user's WhatsApp message arrives here. Reply with the code.
app.post("/webhook", (req, res) => {
  const expected = "sha256=" + crypto.createHmac("sha256", WA_APP_SECRET).update(req.rawBody ?? "").digest("hex");
  if (!safeEqual(req.get("x-hub-signature-256") ?? "", expected)) return res.sendStatus(401);
  res.sendStatus(200); // answer fast; Meta retries otherwise
  for (const entry of req.body?.entry ?? [])
    for (const change of entry.changes ?? [])
      for (const msg of change.value?.messages ?? [])
        handleMessage(msg).catch((e) => console.error("reply failed:", e.message));
});

async function handleMessage(msg) {
  if (msg.type !== "text") return;
  const m = /LOGIN\s+([A-Z0-9]{10})/i.exec(msg.text?.body ?? "");
  if (!m) return;
  const entry = pending.get(m[1].toUpperCase());
  if (!entry || entry.sent || Date.now() > entry.expires) return;
  if (entry.phone !== msg.from) return; // sender must be the number entered in the app
  entry.sent = true;
  try {
    await sendText(msg.from, `Your Swipe login code is ${entry.code}. It expires in 10 minutes. Never share it.`);
  } catch (e) {
    entry.sent = false;
    throw e;
  }
}

async function sendText(to, body) {
  const r = await fetch(`https://graph.facebook.com/${WA_GRAPH_VERSION}/${WA_PHONE_NUMBER_ID}/messages`, {
    method: "POST",
    headers: { Authorization: `Bearer ${WA_TOKEN}`, "Content-Type": "application/json" },
    body: JSON.stringify({ messaging_product: "whatsapp", to, type: "text", text: { body } }),
  });
  if (!r.ok) throw new Error(`WhatsApp API ${r.status}: ${await r.text()}`);
}

app.listen(PORT, () => console.log(`Auth backend listening on ${PORT}`));
