// Mettle — AI insight for one workout, using Gemini.
// Deploy: Supabase dashboard → Edge Functions → Deploy a new function → "Via editor",
// name it `insight`, paste this file, Deploy. Then Edge Functions → Secrets → add
// GEMINI_API_KEY (from Google AI Studio, in a project WITHOUT billing so it stays free).
// Optional secret GEMINI_MODEL (default below).
//
// Only workout numbers are sent to Gemini — never names or locations.
// Token usage is recorded; at 80% of the admin-set monthly limit the app falls back
// to its built-in templates (see supabase/migrations/20261124000000_api_usage_limits.sql).
import { createClient } from "jsr:@supabase/supabase-js@2";

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

Deno.serve(async (req) => {
  const url = Deno.env.get("SUPABASE_URL")!;
  const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
  const key = Deno.env.get("GEMINI_API_KEY");
  const model = Deno.env.get("GEMINI_MODEL") ?? "gemini-2.5-flash";
  const admin = createClient(url, service);

  const token = (req.headers.get("Authorization") ?? "").replace("Bearer ", "");
  const { data: who } = await admin.auth.getUser(token);
  if (!who?.user) return json({ error: "sign in required" }, 401);

  const { data: cfg } = await admin.from("app_config").select("enabled").eq("provider", "gemini").single();
  if (!key || !cfg?.enabled) return json({ text: null, reason: "ai_off" });

  const { facts } = await req.json().catch(() => ({ facts: "" }));
  if (typeof facts !== "string" || facts.length < 10 || facts.length > 2000) return json({ text: null, reason: "bad_request" });

  const prompt =
    "You are a warm, precise endurance coach. In at most 2 short sentences (max 45 words), " +
    "tell the athlete the single most interesting thing about this workout and, if useful, one practical tip. " +
    "Use only these facts, never invent numbers, no emojis.\n\nFacts:\n" + facts;

  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${key}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ contents: [{ parts: [{ text: prompt }] }], generationConfig: { maxOutputTokens: 160, temperature: 0.6 } }),
  });
  if (!r.ok) return json({ text: null, reason: `gemini_${r.status}` });
  const g = await r.json();
  const text = g?.candidates?.[0]?.content?.parts?.[0]?.text?.trim() ?? null;
  const tokens = g?.usageMetadata?.totalTokenCount ?? 0;
  if (tokens > 0) await admin.rpc("record_api_usage", { p_provider: "gemini", p_units: tokens });
  return json({ text, tokens });
});
