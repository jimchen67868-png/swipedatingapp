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
`;

export const initDb = () => pool.query(SCHEMA);
