import crypto from "node:crypto";
import express from "express";
import jwt from "jsonwebtoken";
import rateLimit from "express-rate-limit";
import { pool } from "./db.js";

const api = express.Router();
const wrap = (fn) => (req, res, next) => fn(req, res, next).catch(next);
const bad = (res, error, status = 400) => res.status(status).json({ error });

// Every route needs the login token from /auth. Other users never see phone numbers.
function auth(req, res, next) {
  try {
    const token = (req.get("authorization") ?? "").replace(/^Bearer /, "");
    req.phone = jwt.verify(token, process.env.JWT_SECRET).sub;
    next();
  } catch {
    bad(res, "Please log in again.", 401);
  }
}

const needUser = wrap(async (req, res, next) => {
  const { rows } = await pool.query(
    "select id, banned from users where phone = $1 and gender is not null and looking_for in ('men', 'women')",
    [req.phone]
  );
  if (!rows[0]) return bad(res, "Create your profile first.", 403);
  if (rows[0].banned) return bad(res, "Your account has been suspended.", 403);
  req.userId = rows[0].id;
  next();
});

async function isMember(matchId, userId) {
  if (!Number.isInteger(matchId)) return false;
  const { rowCount } = await pool.query("select 1 from matches where id = $1 and (a = $2 or b = $2)", [matchId, userId]);
  return rowCount > 0;
}

// ----- my profile -----
api.get("/me", auth, wrap(async (req, res) => {
  const { rows } = await pool.query(
    `select id, name, age, city, gender, looking_for as "lookingFor", (verified_at is not null) as verified, (id_verified_at is not null) as "idVerified", (photo is not null) as "hasPhoto" from users
     where phone = $1 and gender is not null and looking_for in ('men', 'women')`,
    [req.phone]
  );
  rows[0] ? res.json(rows[0]) : bad(res, "No profile yet", 404);
}));

const phoneHash = (p) => crypto.createHash("sha256").update(String(p).replace(/\D/g, "")).digest("hex");

// Removes the person and, through the database cascades, their photos, swipes, matches, messages, blocks and reports.
async function eraseUser(u) {
  if (u.banned) await pool.query("insert into banned_phones (hash) values ($1) on conflict do nothing", [phoneHash(u.phone)]);
  await pool.query("delete from users where id = $1", [u.id]);
}

api.delete("/me", auth, wrap(async (req, res) => {
  const { rows } = await pool.query("select id, phone, banned from users where phone = $1", [req.phone]);
  if (rows[0]) await eraseUser(rows[0]);
  res.json({ ok: true });
}));

api.get("/delete-account", (_req, res) => {
  const mail = process.env.SUPPORT_EMAIL;
  res.type("html").send(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Delete your account</title><body style="font-family:sans-serif;margin:16px;max-width:640px;line-height:1.5">
<h2>Delete your account</h2>
<p><b>In the app:</b> open the Matches tab, tap the menu (three lines, top right), choose <b>Delete account</b> and confirm. This takes effect immediately.</p>
<p><b>Can't open the app?</b> ${mail ? "Email <a href=\"mailto:" + mail + "\">" + mail + "</a> from the Telegram account or phone number you signed up with, and we will delete your account." : "Contact the app support address listed in the store page."}</p>
<h3>What is deleted</h3>
<p>Your profile (name, age, city, gender, preferences), your phone number, all your photos, your likes and passes, your matches, your messages, and the blocks and reports you made.</p>
<h3>What we may keep</h3>
<p>If an account was banned for breaking the rules, we keep only a one-way scrambled (hashed) version of the phone number so the person cannot sign up again.</p>
</body>`);
});

const GENDERS = ["man", "woman"];
const LOOKING = ["men", "women"];

api.put("/me", auth, wrap(async (req, res) => {
  const name = String(req.body?.name ?? "").trim();
  const city = String(req.body?.city ?? "").trim();
  const age = Number(req.body?.age);
  const gender = req.body?.gender;
  const lookingFor = req.body?.lookingFor;
  if (!name || name.length > 30) return bad(res, "Name must be 1 to 30 characters.");
  if (!Number.isInteger(age) || age < 18 || age > 99) return bad(res, "You must be 18 or older.");
  if (!city || city.length > 50) return bad(res, "City must be 1 to 50 characters.");
  if (!GENDERS.includes(gender)) return bad(res, "Please choose your gender.");
  if (!LOOKING.includes(lookingFor)) return bad(res, "Please choose who you want to meet.");
  if ((await pool.query("select 1 from banned_phones where hash = $1", [phoneHash(req.phone)])).rowCount) {
    return bad(res, "Your account has been suspended.", 403);
  }
  const { rows } = await pool.query(
    `insert into users (phone, name, age, city, gender, looking_for) values ($1, $2, $3, $4, $5, $6)
     on conflict (phone) do update set name = excluded.name, age = excluded.age, city = excluded.city,
       gender = excluded.gender, looking_for = excluded.looking_for
     returning id, name, age, city, gender, looking_for as "lookingFor", (verified_at is not null) as verified, (id_verified_at is not null) as "idVerified", (photo is not null) as "hasPhoto"`,
    [req.phone, name, age, city, gender, lookingFor]
  );
  res.json(rows[0]);
}));

// ----- photos -----
const PHOTO_MSG = {
  explicit: "This photo isn't allowed. No nudity, lingerie or sexual content.",
  no_face: "We couldn't find your face. Use a clear photo of you.",
  many_faces: "Only you should be in the photo. Every photo must show one person.",
  too_small: "Your face is too small. Use a closer photo.",
  different_person: "This photo doesn't look like the person in your main photo."
};

// Sends an error and returns true when the photo must be refused. FACE_CHECK=off turns every photo check off.
async function refusePhoto(res, buf, main, reference) {
  if (process.env.FACE_CHECK === "off") return false;
  let verdict;
  try {
    const { moderatePhoto } = await import("./face.js");
    verdict = await moderatePhoto(buf, main, reference);
  } catch (e) {
    console.error("photo check unavailable:", e);
    bad(res, "Photo check is not available right now. Try again later.", 503);
    return true;
  }
  if (verdict === "ok" || verdict.startsWith("ok:")) {
    res.locals.check = verdict; // for extra photos: the same-person score, shown in the app so the limit can be tuned
    return false;
  }
  const [kind, score, limit] = verdict.split(":");
  // The score is shown so the limit can be tuned: lower score = more alike.
  const pct = (v) => Math.round(Number(v) * 100);
  const detail = !score ? "" : kind === "too_small"
    ? ` Your face is ${pct(score)}% of the photo width, it needs at least ${pct(limit)}%.`
    : ` (score ${score}, limit ${limit})`;
  bad(res, PHOTO_MSG[kind] + detail, 422);
  return true;
}

// The app sends one small JPEG (about 100 KB). It is stored in the database.
api.post("/me/photo", auth, needUser,
  express.raw({ type: "image/jpeg", limit: "600kb" }),
  wrap(async (req, res) => {
    const b = req.body;
    if (!Buffer.isBuffer(b) || b.length < 4 || b[0] !== 0xff || b[1] !== 0xd8) return bad(res, "Please send a JPEG photo.");
    if (await refusePhoto(res, b, true)) return;
    await pool.query("update users set photo = $1, photo_v = photo_v + 1, verified_at = null, id_verified_at = null where id = $2", [b, req.userId]);
    res.json({ ok: true });
  }));

// ----- extra photos: up to 5 more. They can be anything (no face rule), the main photo keeps the one-person rule -----
const MAX_EXTRA = 5;

api.get("/me/photos", auth, needUser, wrap(async (req, res) => {
  const me = await pool.query("select photo is not null as has, photo_v from users where id = $1", [req.userId]);
  const extra = await pool.query("select id from photos where user_id = $1 order by id", [req.userId]);
  res.json({
    main: me.rows[0]?.has ? `/photo/${req.userId}?v=${me.rows[0].photo_v}` : null,
    extras: extra.rows.map((r) => r.id)
  });
}));

api.post("/me/photos", auth, needUser,
  express.raw({ type: "image/jpeg", limit: "600kb" }),
  wrap(async (req, res) => {
    const b = req.body;
    if (!Buffer.isBuffer(b) || b.length < 4 || b[0] !== 0xff || b[1] !== 0xd8) return bad(res, "Please send a JPEG photo.");
    const me = await pool.query("select photo from users where id = $1", [req.userId]);
    if (!me.rows[0]?.photo) return bad(res, "Add your main photo first.");
    if (await refusePhoto(res, b, false, me.rows[0].photo)) return;
    const { rows } = await pool.query(
      `insert into photos (user_id, data)
       select $1::int, $2::bytea where (select count(*) from photos where user_id = $1::int) < $3::int
       returning id`,
      [req.userId, b, MAX_EXTRA]
    );
    if (!rows[0]) return bad(res, `You can add up to ${MAX_EXTRA} more photos.`);
    res.json({ id: rows[0].id, check: res.locals.check ?? null });
  }));

api.delete("/me/photos/:id", auth, needUser, wrap(async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id)) return bad(res, "Bad photo id.");
  await pool.query("delete from photos where id = $1 and user_id = $2", [id, req.userId]);
  res.json({ ok: true });
}));

api.get("/photo/extra/:id", wrap(async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id)) return res.sendStatus(404);
  const { rows } = await pool.query("select data from photos where id = $1", [id]);
  if (!rows[0]) return res.sendStatus(404);
  res.set("Content-Type", "image/jpeg");
  res.set("Cache-Control", "public, max-age=31536000, immutable"); // an extra photo never changes, it is only added or deleted
  res.send(rows[0].data);
}));

// ----- face verification -----
// The selfie is compared with the profile photo and is never stored.
const verifyLimit = rateLimit({
  windowMs: 60 * 60 * 1000, limit: 6,
  keyGenerator: (req) => String(req.userId),
  message: { error: "Too many tries. Please try again in an hour." }
});

api.post("/me/verify", auth, needUser, verifyLimit,
  express.raw({ type: "image/jpeg", limit: "800kb" }),
  wrap(async (req, res) => {
    const selfie = req.body;
    if (!Buffer.isBuffer(selfie) || selfie.length < 4 || selfie[0] !== 0xff || selfie[1] !== 0xd8) return bad(res, "Please send a JPEG selfie.");
    const { rows } = await pool.query("select photo from users where id = $1", [req.userId]);
    if (!rows[0]?.photo) return bad(res, "Add a profile photo first, then verify.");
    let r;
    try {
      const { compareFaces } = await import("./face.js");
      r = await compareFaces(selfie, rows[0].photo);
    } catch (e) {
      console.error("face check unavailable:", e);
      return bad(res, "Verification is not available right now. Try again later.", 503);
    }
    if (!r.ok) {
      const msg = {
        no_face_selfie: "We couldn't see your face. Use good light and look at the camera.",
        many_faces: "Only you should be in the selfie.",
        no_face_photo: "We couldn't find a face in your profile photo. Choose a clearer photo."
      }[r.reason];
      return bad(res, msg, 422);
    }
    if (!r.match) return bad(res, "Your selfie doesn't match your profile photo.", 422);
    await pool.query("update users set verified_at = now() where id = $1", [req.userId]);
    res.json({ verified: true });
  }));

// ----- ID check: face on the ID must match the selfie, and the birth date must show 18+ -----
// Nothing from the ID is stored: not the photo, not the number, not the birth date. We only keep that the user passed.
const idLimit = rateLimit({
  windowMs: 60 * 60 * 1000, limit: 4,
  keyGenerator: (req) => String(req.userId),
  message: { error: "Too many tries. Please try again in an hour." }
});

function ageOn(iso, now = new Date()) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (!m) return null;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const cm = now.getUTCMonth() + 1;
  return now.getUTCFullYear() - y - (cm < mo || (cm === mo && now.getUTCDate() < d) ? 1 : 0);
}

const jpegFromB64 = (v) => {
  if (typeof v !== "string" || v.length > 1_000_000) return null;
  const b = Buffer.from(v, "base64");
  return b.length > 4 && b[0] === 0xff && b[1] === 0xd8 ? b : null;
};

api.post("/me/verify-id", auth, needUser, idLimit, express.json({ limit: "3mb" }), wrap(async (req, res) => {
  const selfie = jpegFromB64(req.body?.selfie);
  const idPhoto = jpegFromB64(req.body?.idPhoto);
  if (!selfie || !idPhoto) return bad(res, "Please send both photos.");
  const dob = Array.isArray(req.body?.dob) ? req.body.dob.slice(0, 4).map(String) : [];
  const ages = dob.map((d) => ageOn(d)).filter((a) => a !== null);
  if (!ages.length) return bad(res, "We couldn't read the birth date on your ID.");
  if (ages.every((a) => a < 18)) return bad(res, "You must be 18 or older to use this app.", 403);

  const { rows } = await pool.query("select photo, age from users where id = $1", [req.userId]);
  if (!rows[0]?.photo) return bad(res, "Add a profile photo first, then verify.");
  if (!ages.some((a) => a >= 18 && Math.abs(a - rows[0].age) <= 1)) {
    return bad(res, "The birth date on your ID doesn't match the age on your profile.", 422);
  }

  let r, limits;
  try {
    const face = await import("./face.js");
    limits = face;
    r = await face.compareSelfie(selfie, [rows[0].photo, idPhoto]);
  } catch (e) {
    console.error("face check unavailable:", e);
    return bad(res, "Verification is not available right now. Try again later.", 503);
  }
  if (!r.ok) {
    return bad(res, r.reason === "many_faces" ? "Only you should be in the selfie." : "We couldn't see your face. Use good light and look at the camera.", 422);
  }
  const [prof, id] = r.results;
  if (!prof.ok) return bad(res, "We couldn't find a face in your profile photo. Choose a clearer photo.", 422);
  if (!id.ok) return bad(res, "We couldn't find the face on your ID. Retake the photo flat, in focus and without glare.", 422);
  if (prof.distance > limits.MATCH_MAX) return bad(res, "Your selfie doesn't match your profile photo.", 422);
  if (id.distance > limits.MATCH_ID_MAX) return bad(res, "Your selfie doesn't match the photo on your ID.", 422);

  await pool.query("update users set verified_at = now(), id_verified_at = now() where id = $1", [req.userId]);
  res.json({ verified: true, idVerified: true });
}));

api.get("/photo/:id", wrap(async (req, res) => {
  const id = Number(req.params.id);
  if (!Number.isInteger(id)) return res.sendStatus(404);
  const { rows } = await pool.query("select photo from users where id = $1", [id]);
  if (!rows[0]?.photo) return res.sendStatus(404);
  res.set("Content-Type", "image/jpeg");
  res.set("Cache-Control", "public, max-age=604800");
  res.send(rows[0].photo);
}));

// ----- report and block (needed for Google Play) -----
// Blocking removes the match and its messages, and hides both people from each other's deck.
const REASONS = ["fake", "photo", "harassment", "underage", "spam", "other"];
const reportLimit = rateLimit({
  windowMs: 60 * 60 * 1000, limit: 20,
  keyGenerator: (req) => String(req.userId),
  message: { error: "Too many reports. Please try again later." }
});

async function blockUser(me, target) {
  await pool.query("insert into blocks (blocker, blocked) values ($1, $2) on conflict do nothing", [me, target]);
  await pool.query("delete from matches where (a = $1 and b = $2) or (a = $2 and b = $1)", [me, target]);
}

async function validTarget(req) {
  const target = Number(req.body?.targetId);
  if (!Number.isInteger(target) || target === req.userId) return null;
  const { rowCount } = await pool.query("select 1 from users where id = $1", [target]);
  return rowCount ? target : null;
}

api.post("/report", auth, needUser, reportLimit, wrap(async (req, res) => {
  const target = await validTarget(req);
  const reason = String(req.body?.reason ?? "");
  if (!target) return bad(res, "Invalid user.");
  if (!REASONS.includes(reason)) return bad(res, "Choose a reason.");
  await pool.query("insert into reports (reporter, reported, reason) values ($1, $2, $3)", [req.userId, target, reason]);
  await blockUser(req.userId, target);
  res.json({ ok: true });
}));

api.post("/block", auth, needUser, wrap(async (req, res) => {
  const target = await validTarget(req);
  if (!target) return bad(res, "Invalid user.");
  await blockUser(req.userId, target);
  res.json({ ok: true });
}));

// ----- admin: a simple page at /admin to review reports. Needs the ADMIN_KEY env var. -----
function admin(req, res, next) {
  const want = Buffer.from(process.env.ADMIN_KEY ?? "");
  const given = Buffer.from(req.get("x-admin-key") ?? "");
  if (!want.length || given.length !== want.length || !crypto.timingSafeEqual(given, want)) return bad(res, "Not allowed.", 403);
  next();
}
const adminLimit = rateLimit({ windowMs: 60 * 1000, limit: 60 });

api.get("/admin", (_req, res) => {
  res.type("html").send(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Reports</title><body style="font-family:sans-serif;margin:12px;max-width:640px">
<h2>Reports</h2><input id="k" type="password" placeholder="Admin key" style="padding:8px">
<button id="go" style="padding:8px">Load</button>
<h3>Delete an account by phone</h3><input id="ph" placeholder="Phone number" style="padding:8px"><button id="del" style="padding:8px">Delete</button><div id="out"></div><script src="/admin.js"></script></body>`);
});

api.get("/admin.js", (_req, res) => {
  res.type("js").send(`
const out = document.getElementById("out");
let key = sessionStorage.getItem("k") || "";
document.getElementById("k").value = key;
const el = (tag, props = {}, ...kids) => { const e = Object.assign(document.createElement(tag), props); kids.forEach((k) => e.append(k)); return e; };
async function call(method, path) {
  const r = await fetch(path, { method, headers: { "x-admin-key": key } });
  const body = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(body.error || r.status);
  return body;
}
function act(label, method, path) {
  return el("button", { textContent: label, style: "margin:4px;padding:6px", onclick: async () => {
    if (!confirm(label + "?")) return;
    try { await call(method, path); load(); } catch (e) { alert(e.message); }
  } });
}
async function load() {
  key = document.getElementById("k").value; sessionStorage.setItem("k", key);
  out.replaceChildren();
  let rows;
  try { rows = await call("GET", "/admin/reports"); } catch (e) { out.append(e.message); return; }
  if (!rows.length) out.append("No open reports.");
  for (const r of rows) {
    const card = el("div", { style: "border:1px solid #ccc;border-radius:8px;padding:8px;margin:12px 0" });
    card.append(el("div", { textContent: "Report #" + r.id + ": " + r.reason + " (by " + r.reporter_name + ")" }));
    card.append(el("div", { textContent: r.name + ", " + r.age + ", " + r.city + " | reports on this user: " + r.report_count + (r.banned ? " | BANNED" : "") }));
    const pics = el("div", { style: "display:flex;flex-wrap:wrap;gap:6px;margin:8px 0" });
    if (r.has_photo) pics.append(el("img", { src: "/photo/" + r.user_id + "?v=" + r.photo_v, style: "width:110px;border:2px solid #333" }));
    for (const id of r.extra_photos) {
      const box = el("div", {}, el("img", { src: "/photo/extra/" + id, style: "width:110px;display:block" }), act("Delete photo", "DELETE", "/admin/photos/" + id));
      pics.append(box);
    }
    card.append(pics);
    card.append(act("Ban user", "POST", "/admin/users/" + r.user_id + "/ban"));
    card.append(act("Remove main photo", "POST", "/admin/users/" + r.user_id + "/remove-photo"));
    card.append(act("Dismiss report", "POST", "/admin/reports/" + r.id + "/dismiss"));
    out.append(card);
  }
}
document.getElementById("go").onclick = load;
document.getElementById("del").onclick = async () => {
  const phone = document.getElementById("ph").value.trim();
  if (!phone || !confirm("Permanently delete the account for " + phone + "?")) return;
  key = document.getElementById("k").value; sessionStorage.setItem("k", key);
  try {
    const r = await fetch("/admin/delete-phone", { method: "POST", headers: { "x-admin-key": key, "Content-Type": "application/json" }, body: JSON.stringify({ phone }) });
    const b = await r.json();
    alert(r.ok ? "Deleted accounts: " + b.deleted : b.error);
  } catch (e) { alert(e.message); }
};
if (key) load();
`);
});

api.get("/admin/reports", adminLimit, admin, wrap(async (_req, res) => {
  const { rows } = await pool.query(
    `select r.id, r.reason, rp.name as reporter_name,
            u.id as user_id, u.name, u.age, u.city, u.banned, (u.photo is not null) as has_photo, u.photo_v,
            (select coalesce(json_agg(p.id order by p.id), '[]'::json) from photos p where p.user_id = u.id) as extra_photos,
            (select count(*) from reports x where x.reported = u.id)::int as report_count
     from reports r join users rp on rp.id = r.reporter join users u on u.id = r.reported
     where not r.handled order by r.id limit 50`
  );
  res.json(rows);
}));

api.post("/admin/reports/:id/dismiss", adminLimit, admin, wrap(async (req, res) => {
  await pool.query("update reports set handled = true where id = $1", [Number(req.params.id)]);
  res.json({ ok: true });
}));

api.post("/admin/users/:id/ban", adminLimit, admin, wrap(async (req, res) => {
  const id = Number(req.params.id);
  await pool.query("update users set banned = true where id = $1", [id]);
  await pool.query("delete from matches where a = $1 or b = $1", [id]);
  await pool.query("update reports set handled = true where reported = $1", [id]);
  res.json({ ok: true });
}));

api.post("/admin/users/:id/unban", adminLimit, admin, wrap(async (req, res) => {
  await pool.query("update users set banned = false where id = $1", [Number(req.params.id)]);
  res.json({ ok: true });
}));

api.post("/admin/users/:id/remove-photo", adminLimit, admin, wrap(async (req, res) => {
  await pool.query(
    "update users set photo = null, photo_v = photo_v + 1, verified_at = null, id_verified_at = null where id = $1",
    [Number(req.params.id)]
  );
  res.json({ ok: true });
}));

api.post("/admin/delete-phone", adminLimit, admin, wrap(async (req, res) => {
  const digits = String(req.body?.phone ?? "").replace(/\D/g, "");
  if (digits.length < 6) return bad(res, "Enter the full phone number.");
  const { rows } = await pool.query("select id, phone, banned from users where regexp_replace(phone, '\\D', '', 'g') = $1", [digits]);
  for (const u of rows) await eraseUser(u);
  res.json({ deleted: rows.length });
}));

api.delete("/admin/photos/:id", adminLimit, admin, wrap(async (req, res) => {
  await pool.query("delete from photos where id = $1", [Number(req.params.id)]);
  res.json({ ok: true });
}));

// ----- discover -----
api.get("/deck", auth, needUser, wrap(async (req, res) => {
  // Both sides must fit: I want their gender, and they want mine.
  const { rows } = await pool.query(
    `select u.id, u.name, u.age, u.city, case when u.photo is null then null else u.photo_v end as photo_v,
            (u.verified_at is not null) as verified,
            (select coalesce(json_agg(p.id order by p.id), '[]'::json) from photos p where p.user_id = u.id) as extra_photos
     from users u join users me on me.id = $1
     where u.id <> me.id and u.photo is not null and u.gender is not null and u.looking_for in ('men', 'women')
       and u.gender = case me.looking_for when 'men' then 'man' else 'woman' end
       and me.gender = case u.looking_for when 'men' then 'man' else 'woman' end
       and not u.banned
       and not exists (select 1 from swipes s where s.from_id = me.id and s.to_id = u.id)
       and not exists (select 1 from blocks bl where (bl.blocker = me.id and bl.blocked = u.id) or (bl.blocker = u.id and bl.blocked = me.id))
     order by random() limit 20`,
    [req.userId]
  );
  res.json(rows);
}));

api.post("/swipe", auth, needUser, wrap(async (req, res) => {
  const target = Number(req.body?.targetId);
  const liked = req.body?.liked === true;
  if (!Number.isInteger(target) || target === req.userId) return bad(res, "Invalid user.");
  const [a, b] = [req.userId, target].sort((x, y) => x - y);
  const client = await pool.connect();
  try {
    await client.query("begin");
    // Lock per pair so two people liking each other at the same moment still match.
    await client.query("select pg_advisory_xact_lock($1, $2)", [a, b]);
    if (!(await client.query("select 1 from users where id = $1 and not banned", [target])).rowCount) {
      await client.query("rollback");
      return bad(res, "User not found.", 404);
    }
    if ((await client.query("select 1 from blocks where (blocker = $1 and blocked = $2) or (blocker = $2 and blocked = $1)", [req.userId, target])).rowCount) {
      await client.query("rollback");
      return bad(res, "User not found.", 404);
    }
    await client.query(
      `insert into swipes (from_id, to_id, liked) values ($1, $2, $3)
       on conflict (from_id, to_id) do update set liked = excluded.liked`,
      [req.userId, target, liked]
    );
    let matchId = null;
    if (liked && (await client.query("select 1 from swipes where from_id = $1 and to_id = $2 and liked", [target, req.userId])).rowCount) {
      const ins = await client.query("insert into matches (a, b) values ($1, $2) on conflict (a, b) do nothing returning id", [a, b]);
      matchId = ins.rows[0]?.id ?? null;
    }
    await client.query("commit");
    res.json({ matched: matchId !== null, matchId });
  } catch (e) {
    await client.query("rollback").catch(() => {});
    throw e;
  } finally {
    client.release();
  }
}));

// ----- matches and chat -----
api.get("/matches", auth, needUser, wrap(async (req, res) => {
  const { rows } = await pool.query(
    `select m.id as match_id, u.id, u.name, u.age, u.city, case when u.photo is null then null else u.photo_v end as photo_v,
            (u.verified_at is not null) as verified,
            (select coalesce(json_agg(p.id order by p.id), '[]'::json) from photos p where p.user_id = u.id) as extra_photos,
            (select body from messages where match_id = m.id order by id desc limit 1) as last_message
     from matches m join users u on u.id = case when m.a = $1 then m.b else m.a end
     where (m.a = $1 or m.b = $1) and not u.banned
     order by m.created_at desc`,
    [req.userId]
  );
  res.json(rows);
}));

api.get("/matches/:id/messages", auth, needUser, wrap(async (req, res) => {
  const matchId = Number(req.params.id);
  if (!(await isMember(matchId, req.userId))) return bad(res, "Not your match.", 403);
  const { rows } = await pool.query(
    "select id, (sender = $2) as mine, body from messages where match_id = $1 and id > $3 order by id limit 200",
    [matchId, req.userId, Number(req.query.after) || 0]
  );
  res.json(rows);
}));

api.post("/matches/:id/messages", auth, needUser, wrap(async (req, res) => {
  const matchId = Number(req.params.id);
  const body = String(req.body?.body ?? "").trim();
  if (!body || body.length > 1000) return bad(res, "Message must be 1 to 1000 characters.");
  if (!(await isMember(matchId, req.userId))) return bad(res, "Not your match.", 403);
  await pool.query("insert into messages (match_id, sender, body) values ($1, $2, $3)", [matchId, req.userId, body]);
  res.json({ ok: true });
}));

api.use((err, _req, res, _next) => {
  if (err.type === "entity.too.large") return bad(res, "Photo is too large.", 413);
  console.error(err);
  bad(res, "Server error. Please try again.", 500);
});

export default api;
