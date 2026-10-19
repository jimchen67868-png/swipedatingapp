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
  const { rows } = await pool.query("select id from users where phone = $1", [req.phone]);
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
  const { rows } = await pool.query("select id, name, age, city from users where phone = $1", [req.phone]);
  rows[0] ? res.json(rows[0]) : bad(res, "No profile yet", 404);
}));

api.put("/me", auth, wrap(async (req, res) => {
  const name = String(req.body?.name ?? "").trim();
  const city = String(req.body?.city ?? "").trim();
  const age = Number(req.body?.age);
  if (!name || name.length > 30) return bad(res, "Name must be 1 to 30 characters.");
  if (!Number.isInteger(age) || age < 18 || age > 99) return bad(res, "You must be 18 or older.");
  if (!city || city.length > 50) return bad(res, "City must be 1 to 50 characters.");
  const { rows } = await pool.query(
    `insert into users (phone, name, age, city) values ($1, $2, $3, $4)
     on conflict (phone) do update set name = excluded.name, age = excluded.age, city = excluded.city
     returning id, name, age, city`,
    [req.phone, name, age, city]
  );
  res.json(rows[0]);
}));

// ----- discover -----
api.get("/deck", auth, needUser, wrap(async (req, res) => {
  const { rows } = await pool.query(
    `select u.id, u.name, u.age, u.city from users u
     where u.id <> $1 and not exists (select 1 from swipes s where s.from_id = $1 and s.to_id = u.id)
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
    `select m.id as match_id, u.id, u.name, u.age, u.city,
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
  console.error(err);
  bad(res, "Server error. Please try again.", 500);
});

export default api;
