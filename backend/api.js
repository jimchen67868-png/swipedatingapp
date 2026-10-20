import express from "express";
import jwt from "jsonwebtoken";
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
    "select id from users where phone = $1 and gender is not null and looking_for in ('men', 'women')",
    [req.phone]
  );
  if (!rows[0]) return bad(res, "Create your profile first.", 403);
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
    `select id, name, age, city, gender, looking_for as "lookingFor" from users
     where phone = $1 and gender is not null and looking_for in ('men', 'women')`,
    [req.phone]
  );
  rows[0] ? res.json(rows[0]) : bad(res, "No profile yet", 404);
}));

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
  const { rows } = await pool.query(
    `insert into users (phone, name, age, city, gender, looking_for) values ($1, $2, $3, $4, $5, $6)
     on conflict (phone) do update set name = excluded.name, age = excluded.age, city = excluded.city,
       gender = excluded.gender, looking_for = excluded.looking_for
     returning id, name, age, city, gender, looking_for as "lookingFor"`,
    [req.phone, name, age, city, gender, lookingFor]
  );
  res.json(rows[0]);
}));

// ----- photos -----
// The app sends one small JPEG (about 100 KB). It is stored in the database.
api.post("/me/photo", auth, needUser,
  express.raw({ type: "image/jpeg", limit: "600kb" }),
  wrap(async (req, res) => {
    const b = req.body;
    if (!Buffer.isBuffer(b) || b.length < 4 || b[0] !== 0xff || b[1] !== 0xd8) return bad(res, "Please send a JPEG photo.");
    await pool.query("update users set photo = $1, photo_v = photo_v + 1 where id = $2", [b, req.userId]);
    res.json({ ok: true });
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

// ----- discover -----
api.get("/deck", auth, needUser, wrap(async (req, res) => {
  // Both sides must fit: I want their gender, and they want mine.
  const { rows } = await pool.query(
    `select u.id, u.name, u.age, u.city, case when u.photo is null then null else u.photo_v end as photo_v
     from users u join users me on me.id = $1
     where u.id <> me.id and u.gender is not null and u.looking_for in ('men', 'women')
       and u.gender = case me.looking_for when 'men' then 'man' else 'woman' end
       and me.gender = case u.looking_for when 'men' then 'man' else 'woman' end
       and not exists (select 1 from swipes s where s.from_id = me.id and s.to_id = u.id)
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
    if (!(await client.query("select 1 from users where id = $1", [target])).rowCount) {
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
            (select body from messages where match_id = m.id order by id desc limit 1) as last_message
     from matches m join users u on u.id = case when m.a = $1 then m.b else m.a end
     where m.a = $1 or m.b = $1
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
