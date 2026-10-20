import crypto from "node:crypto";
import express from "express";
import rateLimit from "express-rate-limit";
import jwt from "jsonwebtoken";
import api from "./api.js";
import { initDb } from "./db.js";

const {
  BOT_TOKEN,     // from @BotFather
  BOT_USERNAME,  // the bot's username, without @
  JWT_SECRET,
  PORT = 3000,
} = process.env;

for (const [k, v] of Object.entries({ BOT_TOKEN, BOT_USERNAME, JWT_SECRET })) {
  if (!v) throw new Error(`Missing environment variable: ${k}`);
}

const TTL_MS = 10 * 60 * 1000;
const REF_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const makeRef = () => Array.from(crypto.randomBytes(16), (b) => REF_CHARS[b % REF_CHARS.length]).join("");

// One entry per login attempt. In memory: fine for one server instance.
const bySecret = new Map(); // app polls with this (never shown in Telegram)
const byRef = new Map();    // goes in the t.me link
const byChat = new Map();   // Telegram chat id -> entry, set after /start

function drop(entry) {
  bySecret.delete(entry.secret);
  byRef.delete(entry.ref);
  if (entry.chatId) byChat.delete(entry.chatId);
}
setInterval(() => {
  for (const e of bySecret.values()) if (Date.now() > e.expires) drop(e);
}, 60_000).unref();

// ---------- HTTP API ----------
const app = express();
app.set("trust proxy", 1);
// The ID check route sends two photos, so it parses its own larger JSON body (after login is checked).
const json = express.json();
app.use((req, res, next) => (req.path === "/me/verify-id" ? next() : json(req, res, next)));

app.get("/health", (_req, res) => res.json({ ok: true }));

app.post("/auth/start", rateLimit({ windowMs: 15 * 60 * 1000, limit: 20 }), (_req, res) => {
  const entry = {
    ref: makeRef(),
    secret: crypto.randomBytes(24).toString("hex"),
    expires: Date.now() + TTL_MS,
    chatId: null,
    userId: null,
    phone: null,
  };
  bySecret.set(entry.secret, entry);
  byRef.set(entry.ref, entry);
  res.json({ secret: entry.secret, link: `https://t.me/${BOT_USERNAME}?start=${entry.ref}` });
});

// The app polls this every couple of seconds until the user shares their number in Telegram.
app.post("/auth/status", rateLimit({ windowMs: 15 * 60 * 1000, limit: 1000 }), (req, res) => {
  const entry = bySecret.get(String(req.body?.secret ?? ""));
  if (!entry || Date.now() > entry.expires) return res.status(401).json({ error: "Login expired. Please try again." });
  if (!entry.phone) return res.json({ status: "waiting" });
  drop(entry);
  const token = jwt.sign({ sub: entry.phone, tg: entry.userId }, JWT_SECRET, { expiresIn: "30d" });
  res.json({ status: "ok", token });
});

// ---------- Telegram bot (long polling, so no webhook setup is needed) ----------
async function tg(method, body = {}) {
  const r = await fetch(`https://api.telegram.org/bot${BOT_TOKEN}/${method}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  const data = await r.json();
  if (!data.ok) throw new Error(`Telegram ${method}: ${data.description}`);
  return data.result;
}

const say = (chat_id, text, extra = {}) => tg("sendMessage", { chat_id, text, ...extra });

async function handleUpdate(u) {
  const msg = u.message;
  if (!msg || msg.chat.type !== "private") return;
  const chatId = msg.chat.id;

  // The user's own number, shared with the button. user_id must match the sender,
  // otherwise someone could forward a contact card of another person.
  if (msg.contact) {
    const entry = byChat.get(chatId);
    if (!entry || Date.now() > entry.expires) {
      return say(chatId, "Please open the login link from the Swipe app first.");
    }
    if (msg.contact.user_id !== msg.from.id) {
      return say(chatId, "Please share your own number using the button.");
    }
    entry.phone = "+" + String(msg.contact.phone_number).replace(/\D/g, "");
    entry.userId = msg.from.id;
    byChat.delete(chatId);
    return say(chatId, "Verified. Go back to the Swipe app; you'll be logged in automatically.", {
      reply_markup: { remove_keyboard: true },
    });
  }

  const m = /^\/start\s+([A-Za-z0-9]{16})$/.exec(msg.text ?? "");
  if (m) {
    const entry = byRef.get(m[1]);
    if (!entry || Date.now() > entry.expires) {
      return say(chatId, "This login link has expired. Go back to the Swipe app and try again.");
    }
    entry.chatId = chatId;
    byChat.set(chatId, entry);
    return say(chatId, "Tap the button below to confirm your phone number.", {
      reply_markup: {
        keyboard: [[{ text: "Share my phone number", request_contact: true }]],
        resize_keyboard: true,
        one_time_keyboard: true,
      },
    });
  }

  if ((msg.text ?? "").startsWith("/start")) {
    return say(chatId, "Open the Swipe app and tap Continue with Telegram.");
  }
}

async function runBot() {
  await tg("deleteWebhook").catch(() => {}); // long polling and webhooks can't be used together
  let offset = 0;
  for (;;) {
    try {
      const updates = await tg("getUpdates", { offset, timeout: 30, allowed_updates: ["message"] });
      for (const u of updates) {
        offset = u.update_id + 1;
        await handleUpdate(u).catch((e) => console.error("update failed:", e.message));
      }
    } catch (e) {
      console.error("polling error:", e.message);
      await sleep(3000);
    }
  }
}

app.use(api);
await initDb();
app.listen(PORT, () => console.log(`Auth backend listening on ${PORT}`));
runBot();
