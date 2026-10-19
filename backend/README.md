# Auth backend: WhatsApp login via Meta Cloud API (no Twilio)

How it works
1. App sends the phone number to POST /auth/start and gets a wa.me link.
2. The user taps the link and sends the pre-filled "LOGIN <ref>" message to your WhatsApp number.
3. Meta calls POST /webhook. We check the sender matches the number entered, then reply
   with a 6-digit code. This reply is a free-form "service" message, not a paid template.
4. The user types the code; POST /auth/verify returns a JWT.

Setup (Meta)
1. developers.facebook.com > Create app > type Business > add the WhatsApp product.
2. WhatsApp > API Setup: copy the Phone number ID (WA_PHONE_NUMBER_ID) and the
   test number's digits (WA_BUSINESS_NUMBER). Add your own phone as a test recipient.
3. Access token: the temporary token expires in 24h. For a lasting one, create a System User in
   Business Settings with the whatsapp_business_messaging permission (WA_TOKEN).
4. App settings > Basic: copy the App Secret (WA_APP_SECRET).
5. Deploy this folder (Node 18+, HTTPS), set the env vars from .env.example.
6. WhatsApp > Configuration > Webhook: Callback URL = https://YOUR-HOST/webhook,
   Verify token = your WA_VERIFY_TOKEN, then subscribe to the "messages" field.
7. Put https://YOUR-HOST in the Android app (app/build.gradle.kts, API_BASE_URL).

Going to production: add your own business phone number (it cannot be signed in to the
regular WhatsApp app at the same time) and complete Meta business verification.

Notes
- Pending logins are kept in memory and lost on restart; fine for one instance.
- Sender matching compares the phone digits exactly. A few countries (for example Mexico,
  Argentina, Brazil) can have WhatsApp IDs that differ from the dialed number; if logins fail
  there, loosen the comparison in handleMessage().
