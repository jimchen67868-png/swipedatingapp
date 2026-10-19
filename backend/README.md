# Auth backend: phone login through a Telegram bot (free)

How it works
1. App calls POST /auth/start and opens the returned t.me link.
2. The bot asks the user to tap "Share my phone number". Telegram itself confirms the
   number belongs to that account.
3. The app polls POST /auth/status until the number arrives, then receives a JWT.
   The user never types a number or a code.

Setup
1. In Telegram, open @BotFather > /newbot > pick a name and a username ending in "bot".
   Copy the token (BOT_TOKEN) and the username (BOT_USERNAME, without @).
2. Deploy this folder (Node 18+, HTTPS) on Railway, Fly.io, Koyeb or a small VPS.
   Set BOT_TOKEN, BOT_USERNAME and JWT_SECRET. Start command: npm start
3. Put https://YOUR-HOST in the Android app (app/build.gradle.kts, API_BASE_URL).

Notes
- The bot uses long polling, so the server must stay running. Hosts that put free apps
  to sleep will pause the bot until the next request wakes them.
- Run only ONE copy of the server per bot token.
- Pending logins live in memory and are lost on restart (users just tap Continue again).
- Keep BOT_TOKEN secret. If it leaks, revoke it with @BotFather (/revoke).

## Database (Neon Postgres, free)
1. neon.com > sign up > Create project > copy the connection string (starts with postgresql://).
2. Set it as DATABASE_URL on your host. Tables are created automatically on startup.
3. Tables: users, swipes, matches, messages. Phone numbers stay on the server and are never
   sent to other users.
