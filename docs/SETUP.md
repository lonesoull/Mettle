# Mettle — one-time setup

Three accounts, all free. Do them in this order. Nothing here needs coding.

| Part | Time | What it unlocks |
|---|---|---|
| A. GitHub secrets (signing key) | 3 min | An installable Mettle app under **Releases** |
| B. Google Maps key | 10 min | Google's map inside the app |
| C. Supabase project | 10 min | Logins, sync, the group, the admin view |

**Never** paste secrets into chat. Each one goes straight into the website named below.

---

## A. GitHub — add the signing key

The signing key proves every update really comes from you, so phones accept updates over the old version.

1. Open the file `mettle-signing-key.txt` that Claude sent you. **Save a backup copy** (e.g. Google Drive). If it is lost, everyone must uninstall Mettle to get future updates.
2. Go to **https://github.com/lonesoull/Mettle**.
3. Click the **Settings** tab (top of the repo page, right side).
4. In the left menu: **Secrets and variables → Actions**.
5. Click the green **New repository secret** button.
   - **Name:** `METTLE_KEYSTORE_PASSWORD`
   - **Secret:** the short value under "GitHub secret 1" in the file.
   - Click **Add secret**.
6. Click **New repository secret** again.
   - **Name:** `METTLE_KEYSTORE_B64`
   - **Secret:** the long one-line value under "GitHub secret 2". Copy **all** of it.
   - Click **Add secret**.
7. Tell Claude "secrets added". The next build publishes a signed APK under **Releases**.

You should now see two secrets listed: `METTLE_KEYSTORE_B64` and `METTLE_KEYSTORE_PASSWORD`.

---

## B. Google Maps key (free map, never charged)

Google requires a billing account even for its free map. These steps lock the key to the free map and to Mettle only, so it cannot run up a bill.

1. Go to **https://console.cloud.google.com** and sign in.
2. Top bar → project picker → **New project** → name it `Mettle` → **Create**. Make sure `Mettle` is selected afterwards.
3. Left menu → **Billing** → link or create a billing account (card needed; the free map costs ₹0).
4. Left menu → **APIs & Services → Library** → search **Maps SDK for Android** → **Enable**.
   Do **not** enable anything else.
5. **APIs & Services → Credentials → + Create credentials → API key.** Copy nothing yet — click **Edit API key** (or the key's name):
   - **Name:** `Mettle Android`
   - **Application restrictions:** choose **Android apps** → **Add** →
     - Package name: `app.mettle`
     - SHA-1 certificate fingerprint: `4D:C9:C7:72:46:F7:B1:A3:F0:98:BB:BB:01:9F:53:55:7D:C8:6E:E5`
   - **API restrictions:** choose **Restrict key** → tick only **Maps SDK for Android**.
   - **Save.**
6. Safety alarm: **Billing → Budgets & alerts → Create budget** → amount **₹100** → alerts at 50% and 100% → **Finish**. You'll get an email if anything ever starts costing money.
7. Copy the key (starts with `AIza…`) and add it on GitHub exactly like part A:
   - **Name:** `METTLE_MAPS_API_KEY`
   - **Secret:** the key.

---

## C. Supabase — the server

1. Go to **https://supabase.com** → **Start your project** → **Continue with GitHub**.
2. **New project:**
   - **Organization:** your personal one (create it if asked, Free plan).
   - **Project name:** `mettle`
   - **Database password:** click **Generate a password**, then save it in your password manager. You won't need to give it to anyone.
   - **Region:** **South Asia (Mumbai)**.
   - Click **Create new project** and wait about 2 minutes.
3. **Create the tables** — run the eleven scripts **one at a time, in this order**:
   1. `20261119000000_init.sql`
   2. `20261120000000_all_day_and_admin.sql`
   3. `20261121000000_space_time_and_devices.sql`
   4. `20261122000000_safety_health_extras.sql`
   5. `20261123000000_phone_capabilities.sql`
   6. `20261124000000_api_usage_limits.sql`
   7. `20261125000000_grants.sql`
   8. `20261126000000_advisor_fixes.sql`
   9. `20261127000000_admin_views.sql`
   10. `20261128000000_alert_views.sql`
   11. `20261129000000_workout_heart_rate.sql`

   For each one:
   - Open it at **https://github.com/lonesoull/Mettle/tree/main/supabase/migrations** → click the file → click the **Copy raw file** icon (two overlapping squares, top-right of the code).
   - In Supabase, left menu → **SQL Editor** → **+ New query** → paste → click **Run** (bottom right).
   - It should say **Success. No rows returned.** If you see a red error, stop and send Claude a screenshot of it.
4. **Make it invite-only:** left menu → **Authentication → Sign In / Providers** → under **User Signups**, turn **off** "Allow new users to sign up" → **Save**.
5. **Send Claude two values** (these are meant to be inside the app, so they're safe to share):
   - **Project URL:** left menu → **Project Settings → Data API** → copy the URL (looks like `https://abcdxyz.supabase.co`).
   - **Public key:** **Project Settings → API Keys** → copy the **anon public** key (starts with `eyJ…`). If you only see a **publishable** key (starts with `sb_publishable_…`), send that instead.
   - **Never** send the `service_role` / secret key or the database password.

### After the app has a login screen (later)
- Invite yourself: **Authentication → Users → Invite user** → your email. Accept the email, log in to Mettle, create your profile (name, height, weight).
- Make yourself admin: **SQL Editor → + New query** → paste, with your email, and **Run**:
  ```sql
  update profiles set role = 'admin'
  where id = (select id from auth.users where email = 'YOUR-EMAIL');
  ```
- Invite each member the same way; they create their own profile (including height) on first login.
- The Gemini key goes into Supabase later (Edge Functions → Secrets) — Claude will give exact steps when that feature is built.
