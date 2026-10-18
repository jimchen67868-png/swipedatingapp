# Auth backend (WhatsApp OTP via Twilio Verify)

1. Twilio Console > Verify > Services > create a service. Copy its SID (VA...).
2. In that service, open the WhatsApp channel settings and enable it.
3. Run locally:  cd backend && npm install && cp .env.example .env
   then export the variables and `npm start`.
4. Deploy anywhere that runs Node 18+ over HTTPS (Render, Railway, Fly.io, Cloud Run)
   and set the four environment variables there.
5. Put the deployed URL in app/build.gradle.kts (API_BASE_URL).

Endpoints
- POST /auth/start   {"phone":"+8613800138000"}            -> {"ok":true}
- POST /auth/verify  {"phone":"+8613800138000","code":"123456"} -> {"token":"<jwt>"}
