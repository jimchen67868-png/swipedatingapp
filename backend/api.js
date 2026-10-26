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
    `select id, name, age, city, gender, looking_for as "lookingFor", (verified_at is not null) as verified, (id_verified_at is not null) as "idVerified", (photo is not null) as "hasPhoto", to_char(birth_date, 'YYYY-MM-DD') as "birthDate", max_distance_km as "maxDistanceKm" from users
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

// Turns coordinates into a place. Tries OpenStreetMap's Nominatim first, then Photon (also OpenStreetMap data), because one free
// service can refuse requests from shared hosting. Failures are written to the log with the reason.
// Returns { name: "City, Country", parts: [every place name found for the spot] }.
const strings = (o) => Object.values(o ?? {}).filter((v) => typeof v === "string");
const GEOCODERS = [
  {
    name: "nominatim",
    url: (lat, lon, lang) => `${process.env.GEOCODE_URL ?? "https://nominatim.openstreetmap.org/reverse"}?format=jsonv2&zoom=10&addressdetails=1&accept-language=${lang}&lat=${lat}&lon=${lon}`,
    parse: (j) => {
      const a = j?.address;
      return a ? { place: a.city ?? a.town ?? a.village ?? a.municipality ?? a.county ?? a.state, country: a.country, parts: [...strings(a), ...String(j.display_name ?? "").split(", ")] } : null;
    }
  },
  {
    name: "photon",
    url: (lat, lon, lang) => `https://photon.komoot.io/reverse?lang=${["en", "de", "fr", "it"].includes(lang.slice(0, 2)) ? lang.slice(0, 2) : "en"}&lat=${lat}&lon=${lon}`,
    parse: (j) => {
      const p = j?.features?.[0]?.properties;
      return p ? { place: p.city ?? p.town ?? p.village ?? p.county ?? p.state ?? p.name, country: p.country, parts: strings(p) } : null;
    }
  }
];

async function lookupPlace(lat, lon, lang = "") {
  const language = /^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$/.test(lang) ? lang : "en";
  for (const g of GEOCODERS) {
    try {
      const r = await fetch(g.url(lat, lon, language), {
        headers: { "User-Agent": "SwipeDatingApp/1.0 (+https://swipedatingapp.onrender.com)", Accept: "application/json" },
        signal: AbortSignal.timeout(8000)
      });
      if (!r.ok) {
        console.error(`geocode ${g.name}: HTTP ${r.status}`);
        continue;
      }
      const f = g.parse(await r.json());
      if (!f || (!f.place && !f.country)) {
        console.error(`geocode ${g.name}: no place found for ${lat},${lon}`);
        continue;
      }
      return { name: [f.place, f.country].filter(Boolean).join(", ").slice(0, 50), parts: f.parts };
    } catch (e) {
      console.error(`geocode ${g.name} failed: ${e.message}`);
    }
  }
  return null;
}

// The phone names the place itself (for example in the person's own language, or as a city where the map data says district).
// That name is kept when it is plain text and its country matches the country found for the coordinates. If it doesn't, the
// lookup's own name is used. The place part can differ between map services, so only the country has to agree.
const plain = (s) => String(s).normalize("NFD").replace(/[\u0300-\u036f]/g, "").toLowerCase().trim();
function acceptPhoneName(phoneName, found) {
  if (typeof phoneName !== "string") return null;
  const parts = phoneName.split(",").map((x) => x.trim()).filter(Boolean);
  if (parts.length === 0 || parts.length > 2) return null;
  if (!parts.every((x) => x.length <= 40 && /^[\p{L}\p{M}\s.'()\-]+$/u.test(x))) return null; // letters only: no digits, links or symbols
  const known = found.parts.map(plain).filter(Boolean);
  const same = (a, b) => a === b || (a.length >= 3 && b.length >= 3 && (a.includes(b) || b.includes(a)));
  // Two parts: the last is the country and must agree. One part: it must be found in the lookup.
  const mustMatch = plain(parts[parts.length - 1]);
  return known.some((k) => same(k, mustMatch)) ? parts.join(", ").slice(0, 50) : null;
}

api.put("/me", auth, wrap(async (req, res) => {
  const name = String(req.body?.name ?? "").trim();
  let age = Number(req.body?.age);
  const gender = req.body?.gender;
  const lookingFor = req.body?.lookingFor;
  const hasMax = req.body !== null && typeof req.body === "object" && "maxDistanceKm" in req.body;
  const maxKm = hasMax && req.body.maxDistanceKm !== null ? Number(req.body.maxDistanceKm) : null;
  if (hasMax && maxKm !== null && (!Number.isInteger(maxKm) || maxKm < 1 || maxKm > 20000)) return bad(res, "Invalid maximum distance.");
  // The app sends a birth date; the age is worked out from it here, so it can't be typed wrongly.
  const birthDate = req.body?.birthDate == null ? null : String(req.body.birthDate);
  if (birthDate !== null) {
    const d = new Date(birthDate + "T00:00:00Z");
    if (!/^\d{4}-\d{2}-\d{2}$/.test(birthDate) || Number.isNaN(d.getTime()) || d.toISOString().slice(0, 10) !== birthDate) {
      return bad(res, "Please enter a valid birth date.");
    }
    age = ageOn(birthDate);
  }
  if (!name || name.length > 30) return bad(res, "Name must be 1 to 30 characters.");
  if (!Number.isInteger(age) || age < 18 || age > 99) return bad(res, "You must be 18 or older.");
  if (!GENDERS.includes(gender)) return bad(res, "Please choose your gender.");
  if (!LOOKING.includes(lookingFor)) return bad(res, "Please choose who you want to meet.");
  if ((await pool.query("select 1 from banned_phones where hash = $1", [phoneHash(req.phone)])).rowCount) {
    return bad(res, "Your account has been suspended.", 403);
  }
  // Once the ID is verified, the birth date (and so the age) can no longer be changed.
  const current = await pool.query(
    "select age, to_char(birth_date, 'YYYY-MM-DD') as bd, id_verified_at is not null as locked from users where phone = $1",
    [req.phone]
  );
  const cur = current.rows[0];
  const lockedMsg = "Your birth date can't be changed because it was confirmed with your ID.";
  let birthToStore = birthDate;
  if (cur?.locked) {
    if (cur.bd) {
      if (birthDate !== null ? birthDate !== cur.bd : age !== cur.age) return bad(res, lockedMsg, 403);
      birthToStore = cur.bd;
    } else {
      if (age !== cur.age) return bad(res, lockedMsg, 403);
      birthToStore = null;
    }
  }
  // The location is never taken from typed text: the app sends coordinates and the server looks the place up itself.
  // Without coordinates an existing profile keeps its current place, and a new profile can't be created.
  let place = null;
  let lat = null;
  let lon = null;
  if (req.body?.lat != null || req.body?.lon != null) {
    lat = Number(req.body.lat);
    lon = Number(req.body.lon);
    if (!Number.isFinite(lat) || !Number.isFinite(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) return bad(res, "Invalid location.");
    const found = await lookupPlace(lat, lon, String(req.body.lang ?? ""));
    if (!found) return bad(res, "We couldn't look up your location. Please try again.", 503);
    const kept = acceptPhoneName(req.body.city, found);
    console.log(`place name: phone="${String(req.body.city).slice(0, 60)}" lookup="${found.name}" -> ${kept ? "kept phone name" : "used lookup name"}`);
    place = kept ?? found.name;
    lat = Math.round(lat * 100) / 100; // about 1 km: enough for distance later, not exact enough to find a home
    lon = Math.round(lon * 100) / 100;
  } else if (!cur) {
    return bad(res, "Turn on location to create your profile.");
  }
  const { rows } = await pool.query(
    `insert into users (phone, name, age, city, gender, looking_for, birth_date, lat, lon, max_distance_km) values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $11)
     on conflict (phone) do update set name = excluded.name, age = excluded.age, city = coalesce(nullif(excluded.city, ''), users.city),
       gender = excluded.gender, looking_for = excluded.looking_for, birth_date = coalesce(excluded.birth_date, users.birth_date),
       lat = coalesce(excluded.lat, users.lat), lon = coalesce(excluded.lon, users.lon),
       max_distance_km = case when $10::boolean then excluded.max_distance_km else users.max_distance_km end
     returning id, name, age, city, gender, looking_for as "lookingFor", (verified_at is not null) as verified, (id_verified_at is not null) as "idVerified", (photo is not null) as "hasPhoto", to_char(birth_date, 'YYYY-MM-DD') as "birthDate", max_distance_km as "maxDistanceKm"`,
    [req.phone, name, age, place ?? "", gender, lookingFor, birthToStore, lat, lon, hasMax, maxKm]
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
  if (verdict === "ok") return false;
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
    // The same-person check runs on the phone. Set SERVER_SAME_PERSON=on to make the server check it too.
    const reference = process.env.SERVER_SAME_PERSON === "on" ? me.rows[0].photo : undefined;
    if (await refusePhoto(res, b, false, reference)) return;
    const { rows } = await pool.query(
      `insert into photos (user_id, data)
       select $1::int, $2::bytea where (select count(*) from photos where user_id = $1::int) < $3::int
       returning id`,
      [req.userId, b, MAX_EXTRA]
    );
    if (!rows[0]) return bad(res, `You can add up to ${MAX_EXTRA} more photos.`);
    res.json({ id: rows[0].id });
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

  const { rows } = await pool.query("select photo, age, to_char(birth_date, 'YYYY-MM-DD') as bd from users where id = $1", [req.userId]);
  if (!rows[0]?.photo) return bad(res, "Add a profile photo first, then verify.");
  let idAge;
  let idDob;
  if (rows[0].bd) {
    // The profile has a birth date, so the ID must show exactly the same date.
    if (!dob.includes(rows[0].bd)) {
      return bad(res, `The birth date on your ID doesn't match the birth date on your profile (${rows[0].bd}).`, 422);
    }
    idDob = rows[0].bd;
    idAge = ageOn(idDob);
    if (idAge < 18) return bad(res, "You must be 18 or older to use this app.", 403);
  } else {
    // Older profiles have only an age. ID_AGE_TOLERANCE=0 makes it exact; the default allows one year.
    const tolerance = Number.isFinite(Number(process.env.ID_AGE_TOLERANCE)) && process.env.ID_AGE_TOLERANCE !== undefined ? Number(process.env.ID_AGE_TOLERANCE) : 1;
    const idAges = [...new Set(ages.filter((a) => a >= 18))].sort((x, y) => Math.abs(x - rows[0].age) - Math.abs(y - rows[0].age));
    idAge = idAges[0]; // the reading closest to the profile age (day and month can be read two ways)
    if (idAge === undefined || Math.abs(idAge - rows[0].age) > tolerance) {
      return bad(res, `The age on your ID (${idAges.join(" or ")}) doesn't match the age on your profile (${rows[0].age}).`, 422);
    }
    idDob = dob.find((d) => ageOn(d) === idAge);
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
  // The face on the ID must also match the main profile photo directly.
  if (r.cross !== null && r.cross > limits.MATCH_ID_MAX) {
    return bad(res, `The photo on your ID doesn't match your main profile photo. (score ${r.cross.toFixed(2)}, limit ${limits.MATCH_ID_MAX})`, 422);
  }
  console.log(`id check: selfie-profile ${prof.distance.toFixed(2)}, selfie-id ${id.distance.toFixed(2)}, id-profile ${r.cross === null ? "n/a" : r.cross.toFixed(2)}`);

  // The ID is the truth: keep the profile age in step with it.
  await pool.query(
    "update users set verified_at = now(), id_verified_at = now(), age = $2, birth_date = coalesce(birth_date, $3::date) where id = $1",
    [req.userId, idAge, idDob ?? null]
  );
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
  const want = Buffer.from((process.env.ADMIN_KEY ?? "").trim());
  const given = Buffer.from((req.get("x-admin-key") ?? "").trim()); // phone keyboards often add a space
  if (!want.length || given.length !== want.length || !crypto.timingSafeEqual(given, want)) return bad(res, "Not allowed.", 403);
  next();
}
const adminLimit = rateLimit({ windowMs: 60 * 1000, limit: 60 });

api.get("/admin", (_req, res) => {
  res.type("html").send(`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Reports</title><body style="font-family:sans-serif;margin:12px;max-width:640px">
<h2>Reports</h2><input id="k" type="password" placeholder="Admin key" style="padding:8px">
<button id="go" style="padding:8px">Load</button>
<h3>Test users</h3><button id="tadd" style="padding:8px">Add 12 test users</button> <button id="taddp" style="padding:8px">Add 12 test users with photo</button> <button id="tnear" style="padding:8px">Add 12 test users near me (with photo)</button> <button id="tdel" style="padding:8px">Delete test users</button>
<button id="tpho" style="padding:8px;margin-top:6px">Give test users photos</button>
<h3>Delete an account by phone</h3><input id="ph" placeholder="Phone number" style="padding:8px"><button id="del" style="padding:8px">Delete</button><div id="tbox"></div><div id="out"></div><script src="/admin.js"></script></body>`);
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
function toJpeg(file) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const s = Math.min(1, 800 / Math.max(img.width, img.height));
      const c = document.createElement("canvas");
      c.width = Math.round(img.width * s); c.height = Math.round(img.height * s);
      c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
      c.toBlob((b) => (b ? resolve(b) : reject(new Error("Could not read the image"))), "image/jpeg", 0.85);
    };
    img.onerror = () => reject(new Error("Could not read the image"));
    img.src = URL.createObjectURL(file);
  });
}
function choose(onFile) {
  const i = document.createElement("input");
  i.type = "file"; i.accept = "image/*";
  i.onchange = () => { if (i.files[0]) onFile(i.files[0]); };
  i.click();
}
function uploadFor(id, kind) {
  return el("button", { textContent: kind === "main" ? "Set main photo" : "Add photo", style: "margin:4px;padding:6px", onclick: () => choose(async (file) => {
    try {
      const blob = await toJpeg(file);
      const r = await fetch("/admin/test-users/" + id + "/photo?kind=" + kind, { method: "POST", headers: { "x-admin-key": key, "Content-Type": "image/jpeg" }, body: blob });
      const b = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(b.error || r.status);
      load();
    } catch (e) { alert(e.message); }
  }) });
}
async function loadTestUsers() {
  const box = document.getElementById("tbox");
  box.replaceChildren();
  let rows;
  try { rows = await call("GET", "/admin/test-users"); } catch (e) { return; }
  for (const u of rows) {
    const card = el("div", { style: "border:1px solid #ccc;border-radius:8px;padding:8px;margin:8px 0" });
    card.append(el("div", { textContent: "#" + u.id + " " + u.name + ", " + u.age + ", " + u.city + " (" + u.gender + ", wants " + u.looking_for + ")" }));
    const pics = el("div", { style: "display:flex;flex-wrap:wrap;gap:6px;margin:6px 0" });
    if (u.has_photo) pics.append(el("div", {}, el("img", { src: "/photo/" + u.id + "?v=" + u.photo_v, style: "width:90px;border:2px solid #333;display:block" }), act("Remove main", "POST", "/admin/users/" + u.id + "/remove-photo")));
    for (const id of u.extra_photos) pics.append(el("div", {}, el("img", { src: "/photo/extra/" + id, style: "width:90px;display:block" }), act("Delete", "DELETE", "/admin/photos/" + id)));
    card.append(pics);
    card.append(uploadFor(u.id, "main"));
    card.append(uploadFor(u.id, "extra"));
    box.append(card);
  }
}
async function load() {
  key = document.getElementById("k").value; sessionStorage.setItem("k", key);
  loadTestUsers();
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
async function testUsers(method, withPhotos, where) {
  key = document.getElementById("k").value; sessionStorage.setItem("k", key);
  try {
    const r = await fetch("/admin/test-users", { method, headers: { "x-admin-key": key, "Content-Type": "application/json" }, body: method === "POST" ? JSON.stringify({ count: 12, withPhotos: withPhotos === true, lat: where ? where.latitude : undefined, lon: where ? where.longitude : undefined }) : undefined });
    const b = await r.json();
    alert(r.ok ? (b.added !== undefined ? "Added " + b.added : "Deleted " + b.deleted) : b.error);
    load();
  } catch (e) { alert(e.message); }
}
document.getElementById("tadd").onclick = () => testUsers("POST", false);
document.getElementById("taddp").onclick = () => testUsers("POST", true);
document.getElementById("tnear").onclick = () => {
  if (!navigator.geolocation) { alert("This browser can't share your location."); return; }
  navigator.geolocation.getCurrentPosition((pos) => testUsers("POST", true, pos.coords), (err) => alert("Location is off: " + err.message));
};
document.getElementById("tdel").onclick = () => testUsers("DELETE");
document.getElementById("tpho").onclick = async () => {
  key = document.getElementById("k").value; sessionStorage.setItem("k", key);
  try {
    const r = await fetch("/admin/test-photos", { method: "POST", headers: { "x-admin-key": key } });
    const b = await r.json();
    alert(r.ok ? "Photos added for " + b.updated + " test users" : b.error);
    load();
  } catch (e) { alert(e.message); }
};
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

const TEST_NAMES = {
  woman: ["Mia", "Emma", "Sofia", "Lily", "Nora", "Zoe", "Ava", "Chloe", "Grace", "Ella"],
  man: ["Liam", "Noah", "Ethan", "Lucas", "Leo", "Max", "Adam", "Ryan", "Jack", "Ben"]
};
const TEST_CITIES = [
  { name: "London, United Kingdom", lat: 51.51, lon: -0.13 }, { name: "Berlin, Germany", lat: 52.52, lon: 13.4 },
  { name: "Paris, France", lat: 48.86, lon: 2.35 }, { name: "Madrid, Spain", lat: 40.42, lon: -3.7 },
  { name: "Rome, Italy", lat: 41.9, lon: 12.5 }, { name: "Toronto, Canada", lat: 43.65, lon: -79.38 },
  { name: "Sydney, Australia", lat: -33.87, lon: 151.21 }, { name: "Singapore, Singapore", lat: 1.35, lon: 103.82 }
];
const pick = (list) => list[Math.floor(Math.random() * list.length)];

// Fake people for testing. They like you back, answer chat messages, and only appear when SHOW_TEST_USERS=on.
api.post("/admin/test-users", adminLimit, admin, wrap(async (req, res) => {
  const count = Math.min(Math.max(Number(req.body?.count) || 12, 1), 40);
  const withPhotos = req.body?.withPhotos === true;
  // With lat and lon (the admin page can send where you are), the test users are placed within about 20 km of that spot.
  const nearLat = Number(req.body?.lat);
  const nearLon = Number(req.body?.lon);
  const near = req.body?.lat != null && Number.isFinite(nearLat) && Number.isFinite(nearLon);
  const nearName = near ? ((await lookupPlace(nearLat, nearLon))?.name ?? "Nearby") : null;
  for (let i = 0; i < count; i++) {
    const gender = i % 2 === 0 ? "woman" : "man";
    const lookingFor = Math.floor(i / 2) % 2 === 0 ? "men" : "women"; // all four combinations, so everyone sees some
    const ins = await pool.query(
      "insert into users (phone, name, age, city, gender, looking_for, is_test, lat, lon) values ($1, $2, $3, $4, $5, $6, true, $7, $8) returning id",
      (() => {
        const c = near ? null : pick(TEST_CITIES);
        const lat = near ? nearLat + (Math.random() - 0.5) * 0.35 : c.lat;
        const lon = near ? nearLon + (Math.random() - 0.5) * 0.35 : c.lon;
        return ["test-" + crypto.randomUUID(), pick(TEST_NAMES[gender]), 20 + Math.floor(Math.random() * 21), near ? nearName : c.name, gender, lookingFor, lat, lon];
      })()
    );
    if (withPhotos) await giveTestPhotos(ins.rows[0].id);
  }
  res.json({ added: count });
}));

// A main photo plus 1 to 3 more, so the photo gallery can be tested. Skipped quietly if the image library isn't available.
async function giveTestPhotos(userId) {
  try {
    const { testPhoto } = await import("./face.js");
    const hue = Math.floor(Math.random() * 360);
    await pool.query("update users set photo = $1, photo_v = photo_v + 1 where id = $2", [await testPhoto(hue, 0), userId]);
    await pool.query("delete from photos where user_id = $1", [userId]);
    const extras = 1 + Math.floor(Math.random() * 3);
    for (let k = 1; k <= extras; k++) {
      await pool.query("insert into photos (user_id, data) values ($1, $2)", [userId, await testPhoto((hue + k * 35) % 360, k)]);
    }
  } catch (e) {
    console.error("test photos failed:", e.message);
  }
}

api.get("/admin/test-users", adminLimit, admin, wrap(async (_req, res) => {
  const { rows } = await pool.query(
    `select u.id, u.name, u.age, u.city, u.gender, u.looking_for, (u.photo is not null) as has_photo, u.photo_v,
            (select coalesce(json_agg(p.id order by p.id), '[]'::json) from photos p where p.user_id = u.id) as extra_photos
     from users u where u.is_test order by u.id`
  );
  res.json(rows);
}));

// Upload your own picture for a test user (kind=main or kind=extra). No face or content checks: only you can do this.
api.post("/admin/test-users/:id/photo", adminLimit, admin, express.raw({ type: "image/jpeg", limit: "800kb" }), wrap(async (req, res) => {
  const id = Number(req.params.id);
  const b = req.body;
  if (!Number.isInteger(id)) return bad(res, "Bad id.");
  if (!Buffer.isBuffer(b) || b.length < 4 || b[0] !== 0xff || b[1] !== 0xd8) return bad(res, "Please send a JPEG photo.");
  if (!(await pool.query("select 1 from users where id = $1 and is_test", [id])).rowCount) return bad(res, "Not a test user.", 404);
  if (req.query.kind === "main") await pool.query("update users set photo = $1, photo_v = photo_v + 1 where id = $2", [b, id]);
  else await pool.query("insert into photos (user_id, data) values ($1, $2)", [id, b]);
  res.json({ ok: true });
}));

api.post("/admin/test-photos", adminLimit, admin, wrap(async (_req, res) => {
  const { rows } = await pool.query("select id from users where is_test");
  for (const u of rows) await giveTestPhotos(u.id);
  res.json({ updated: rows.length });
}));

api.delete("/admin/test-users", adminLimit, admin, wrap(async (_req, res) => {
  const r = await pool.query("delete from users where is_test");
  res.json({ deleted: r.rowCount });
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

// Distance in whole kilometres between two people (rounded up), or null if either has no location.
const distanceKm = (u, me) => `case when ${u}.lat is null or ${me}.lat is null then null else
  ceil(6371 * 2 * asin(sqrt(least(1, power(sin(radians(${u}.lat - ${me}.lat) / 2), 2)
    + cos(radians(${me}.lat)) * cos(radians(${u}.lat)) * power(sin(radians(${u}.lon - ${me}.lon) / 2), 2)))))::int end`;

// ----- discover -----
api.get("/deck", auth, needUser, wrap(async (req, res) => {
  // Both sides must fit: I want their gender, and they want mine.
  const { rows } = await pool.query(
    `select u.id, u.name, u.age, u.city, case when u.photo is null then null else u.photo_v end as photo_v,
            (u.verified_at is not null) as verified,
            ${distanceKm("u", "me")} as distance_km,
            (select coalesce(json_agg(p.id order by p.id), '[]'::json) from photos p where p.user_id = u.id) as extra_photos
     from users u join users me on me.id = $1
     where u.id <> me.id and (u.photo is not null or u.is_test) and (not u.is_test or $2::boolean) and u.gender is not null and u.looking_for in ('men', 'women')
       and u.gender = case me.looking_for when 'men' then 'man' else 'woman' end
       and me.gender = case u.looking_for when 'men' then 'man' else 'woman' end
       and not u.banned
       and (me.max_distance_km is null or me.lat is null or (u.lat is not null and ${distanceKm("u", "me")} <= me.max_distance_km))
       and not exists (select 1 from swipes s where s.from_id = me.id and s.to_id = u.id)
       and not exists (select 1 from blocks bl where (bl.blocker = me.id and bl.blocked = u.id) or (bl.blocker = u.id and bl.blocked = me.id))
     order by distance_km nulls last, random() limit 20`, // nearest first
    [req.userId, process.env.SHOW_TEST_USERS === "on"]
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
    const found = await client.query("select is_test from users where id = $1 and not banned", [target]);
    if (!found.rowCount) {
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
    if (liked && found.rows[0].is_test) {
      await client.query(
        `insert into swipes (from_id, to_id, liked) values ($1, $2, true)
         on conflict (from_id, to_id) do update set liked = true`,
        [target, req.userId]
      );
    }
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
            ${distanceKm("u", "me")} as distance_km,
            (select body from messages where match_id = m.id order by id desc limit 1) as last_message
     from matches m join users u on u.id = case when m.a = $1 then m.b else m.a end
     join users me on me.id = $1
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
  const m = await pool.query("select case when a = $2 then b else a end as other from matches where id = $1", [matchId, req.userId]);
  const other = m.rows[0]?.other;
  if (other && (await pool.query("select 1 from users where id = $1 and is_test", [other])).rowCount) {
    const replies = ["Hey! Nice to meet you", "Haha, tell me more", "That sounds fun!", "What do you do for fun?", "Same here!", "Interesting, why?", "Want to meet up sometime?"];
    setTimeout(() => {
      pool.query("insert into messages (match_id, sender, body) values ($1, $2, $3)", [matchId, other, replies[Math.floor(Math.random() * replies.length)]]).catch(() => {});
    }, 1500 + Math.random() * 2500);
  }
  res.json({ ok: true });
}));

api.use((err, _req, res, _next) => {
  if (err.type === "entity.too.large") return bad(res, "Photo is too large.", 413);
  console.error(err);
  bad(res, "Server error. Please try again.", 500);
});

export default api;
