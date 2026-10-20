import pg from "pg";

const { DATABASE_URL } = process.env;
if (!DATABASE_URL) throw new Error("Missing environment variable: DATABASE_URL");

export const pool = new pg.Pool({ connectionString: DATABASE_URL, max: 5, idleTimeoutMillis: 30_000 });
pool.on("error", (e) => console.error("db pool error:", e.message));

const SCHEMA = `
create table if not exists users (
  id serial primary key,
  phone text unique not null,
  name text not null,
  age int not null check (age >= 18),
  city text not null,
  created_at timestamptz not null default now()
);
create table if not exists swipes (
  from_id int not null references users(id) on delete cascade,
  to_id int not null references users(id) on delete cascade,
  liked boolean not null,
  created_at timestamptz not null default now(),
  primary key (from_id, to_id)
);
create table if not exists matches (
  id serial primary key,
  a int not null references users(id) on delete cascade,
  b int not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  unique (a, b)
);
create table if not exists messages (
  id bigserial primary key,
  match_id int not null references matches(id) on delete cascade,
  sender int not null references users(id) on delete cascade,
  body text not null,
  created_at timestamptz not null default now()
);
create index if not exists messages_match_idx on messages (match_id, id);
create table if not exists photos (
  id serial primary key,
  user_id int not null references users(id) on delete cascade,
  data bytea not null,
  created_at timestamptz not null default now()
);
create index if not exists photos_user_idx on photos (user_id);
create table if not exists blocks (
  blocker int not null references users(id) on delete cascade,
  blocked int not null references users(id) on delete cascade,
  created_at timestamptz not null default now(),
  primary key (blocker, blocked)
);
create table if not exists reports (
  id serial primary key,
  reporter int not null references users(id) on delete cascade,
  reported int not null references users(id) on delete cascade,
  reason text not null,
  handled boolean not null default false,
  created_at timestamptz not null default now()
);
create index if not exists reports_open_idx on reports (handled, id);
-- When a banned person deletes their account we keep only a one-way hash of the phone number, so they can't simply return.
create table if not exists banned_phones (hash text primary key);

alter table users add column if not exists gender text check (gender in ('man', 'woman', 'other'));
alter table users add column if not exists banned boolean not null default false;
alter table users add column if not exists verified_at timestamptz;
alter table users add column if not exists id_verified_at timestamptz;
alter table users add column if not exists photo bytea;
alter table users add column if not exists photo_v int not null default 0;
alter table users add column if not exists looking_for text check (looking_for in ('men', 'women'));
`;

export const initDb = () => pool.query(SCHEMA);
