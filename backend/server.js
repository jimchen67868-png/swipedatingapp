import express from "express";
import rateLimit from "express-rate-limit";
import jwt from "jsonwebtoken";
import twilio from "twilio";

const {
  TWILIO_ACCOUNT_SID,
  TWILIO_AUTH_TOKEN,
  TWILIO_VERIFY_SID,
  JWT_SECRET,
  PORT = 3000,
} = process.env;

for (const [k, v] of Object.entries({ TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_VERIFY_SID, JWT_SECRET })) {
  if (!v) throw new Error(`Missing environment variable: ${k}`);
}

const verify = twilio(TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN).verify.v2.services(TWILIO_VERIFY_SID);
const PHONE = /^\+[1-9]\d{7,14}$/;

const app = express();
app.set("trust proxy", 1);
app.use(express.json());
// Limits code requests per IP so nobody can run up your Twilio bill.
app.use("/auth", rateLimit({ windowMs: 15 * 60 * 1000, limit: 10 }));

app.get("/health", (_req, res) => res.json({ ok: true }));

app.post("/auth/start", async (req, res) => {
  const { phone } = req.body ?? {};
  if (!PHONE.test(phone ?? "")) return res.status(400).json({ error: "Invalid phone number" });
  try {
    await verify.verifications.create({ to: phone, channel: "whatsapp" });
    res.json({ ok: true });
  } catch (e) {
    console.error("start failed:", e.message);
    res.status(502).json({ error: "Could not send the WhatsApp code. Try again later." });
  }
});

app.post("/auth/verify", async (req, res) => {
  const { phone, code } = req.body ?? {};
  if (!PHONE.test(phone ?? "") || !/^\d{4,10}$/.test(code ?? "")) {
    return res.status(400).json({ error: "Invalid phone number or code" });
  }
  try {
    const check = await verify.verificationChecks.create({ to: phone, code });
    if (check.status !== "approved") return res.status(401).json({ error: "Wrong or expired code" });
    const token = jwt.sign({ sub: phone }, JWT_SECRET, { expiresIn: "30d" });
    res.json({ token });
  } catch (e) {
    console.error("verify failed:", e.message);
    res.status(401).json({ error: "Wrong or expired code" });
  }
});

app.listen(PORT, () => console.log(`Auth backend listening on ${PORT}`));
