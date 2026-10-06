// Proxies Gemini "describe this photo" requests for both the public PhotoSync
// page (docs/index.html) and the Android app, so no Gemini credential ever
// sits in plaintext HTML or inside the APK — both are trivially extractable
// by anyone, and a plain API key hardcoded in the public page already got
// auto-detected and revoked by Google once.
//
// Describing a photo is the only thing this Worker does. It also carried a
// multi-image "compare" mode for the app's Find Redundant feature, which was
// removed from both apps; the endpoint went with it rather than staying
// reachable, since anyone holding the app secret could still have called it.
//
// Auth is an "authorization key": an API key bound to a service account, the
// only kind Gemini accepts on this project. It once signed a JWT with the
// service account's private key and sent the resulting OAuth token instead,
// until Google started refusing service-account tokens for Gemini with a 403.
//
// Two secrets, set via `wrangler secret put <NAME>`:
//   GEMINI_API_KEY — APIs & Services → Credentials → Create credentials →
//     API key, restrict it to Gemini API, then turn on the service-account
//     binding that appears and pick the service account. Pipe it in rather
//     than pasting at wrangler's prompt, which can capture stray terminal
//     characters that Google then rejects with a bodiless 400:
//       $k = Read-Host "key"; $k.Trim() | npx wrangler secret put GEMINI_API_KEY
//   APP_SHARED_SECRET — any random string you make up. Set the exact same
//     value in local.properties as GEMINI_PROXY_APP_SECRET so the Android
//     app can authenticate. Low-stakes if it leaks (extractable from the
//     APK either way) — worst case is your free-tier Gemini quota getting
//     used up, not a credential compromise.
//
// One optional binding, created with `wrangler kv namespace create
// DESCRIPTIONS` and pasted into wrangler.toml:
//   DESCRIPTIONS — remembers each photo's description so it is generated once
//     for everyone rather than once per visitor. Everything still works
//     without it, minus the remembering.
//
// The public page is instead authorized via CORS: only requests whose
// Origin header matches ALLOWED_ORIGIN are accepted, so a plain <script>
// fetch from any other site is rejected server-side (CORS headers alone
// only stop browsers from *reading* a cross-origin response — the origin
// check below is what actually blocks the request).

const ALLOWED_ORIGIN = "https://johnvv999.github.io";
const GEMINI_MODEL = "gemini-flash-latest";
// Fixed server-side rather than accepted from the caller. The app's secret is
// extractable from the APK, so anyone can reach this Worker — what stops it
// becoming a free general-purpose Gemini endpoint on your quota is that the
// only thing it will ever ask is this one question about one photo.
const GEMINI_PROMPT =
  "Briefly describe what's in this photo and identify any recognizable landmark, location, or point of interest, in 2-3 sentences.";


// Descriptions are kept in a KV namespace so a photo is only ever described
// once for the whole world, not once per visitor per browser.
//
// The Android app has its own store — it writes the text onto the Drive file,
// which the public page then reads for free — but the page can't do the same:
// it reads Drive with an anonymous API key and has no credential to write
// anything back. Without this, every visitor regenerated the text for every
// photo the app hadn't already described, paying the wait and the quota each
// time, and each seeing slightly different wording.
//
// The binding is optional on purpose: with no namespace attached the Worker
// still answers normally, just without remembering. That keeps a missing or
// mistyped binding from taking the page's Info button down with it.
const CACHE_PREFIX = "desc:v1:";

/**
 * Cache key for a photo. [version] should be something that changes when the
 * image content does — the page passes Drive's md5Checksum. Ids alone would
 * serve a stale description forever if a photo were ever replaced in place,
 * and modifiedTime would throw the cache away every time PhotoSync renamed a
 * file, which it does routinely when fixing locations.
 */
function cacheKey(photoId, version) {
  return version ? `${CACHE_PREFIX}${photoId}:${version}` : `${CACHE_PREFIX}${photoId}`;
}

async function readCachedDescription(env, photoId, version) {
  if (!env.DESCRIPTIONS || !photoId) return null;
  try {
    return await env.DESCRIPTIONS.get(cacheKey(photoId, version));
  } catch {
    // A cache that can't be read is a slow path, not a failure.
    return null;
  }
}

async function writeCachedDescription(env, photoId, version, text) {
  if (!env.DESCRIPTIONS || !photoId || !text) return;
  try {
    await env.DESCRIPTIONS.put(cacheKey(photoId, version), text);
  } catch {
    // Same reasoning: the caller already has its answer.
  }
}

function corsHeaders(origin) {
  const headers = {
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type, X-App-Secret",
  };
  if (origin === ALLOWED_ORIGIN) headers["Access-Control-Allow-Origin"] = ALLOWED_ORIGIN;
  return headers;
}

function json(body, status, headers) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...headers, "Content-Type": "application/json" },
  });
}

export default {
  async fetch(request, env) {
    const origin = request.headers.get("Origin") || "";
    const headers = corsHeaders(origin);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers });
    }

    const isWebPage = origin === ALLOWED_ORIGIN;
    const isApp = !isWebPage && request.headers.get("X-App-Secret") === env.APP_SHARED_SECRET;
    if (!isWebPage && !isApp) {
      return json({ error: "Forbidden" }, 403, headers);
    }
    if (request.method !== "POST") {
      return json({ error: "Method not allowed" }, 405, headers);
    }

    let body;
    try {
      body = await request.json();
    } catch {
      return json({ error: "Invalid JSON body" }, 400, headers);
    }

    const { mode, mimeType, data, lat, lon, photoId, version } = body;

    // A request naming a photo but carrying no image is a cache lookup. It
    // exists so the page can find out whether a description is already known
    // *before* downloading the photo to send here — on a hit that saves a
    // multi-megabyte download on someone's phone, which is the slowest part of
    // the whole exchange.
    if (mode === "lookup" || (photoId && !data)) {
      const hit = await readCachedDescription(env, photoId, version);
      return hit
        ? json({ text: hit, cached: true }, 200, headers)
        : json({ miss: true }, 200, headers);
    }

    if (!data || typeof data !== "string") {
      return json({ error: "Missing image data" }, 400, headers);
    }

    // Optional GPS from the caller lets Gemini pin the actual location/landmark.
    let prompt = GEMINI_PROMPT;
    if (typeof lat === "number" && typeof lon === "number") {
      prompt += ` The photo was taken at approximately latitude ${lat.toFixed(6)}, longitude ${lon.toFixed(6)}; use these coordinates to help identify the specific place, landmark, or neighborhood.`;
    }
    const imageParts = [{ inline_data: { mime_type: mimeType || "image/jpeg", data } }];

    // Checked again even though the caller was meant to look first: two people
    // opening the same photo at once both miss the lookup, and a caller that
    // skips it entirely (the app) shouldn't pay for a second description of a
    // photo already in the cache.
    const cached = await readCachedDescription(env, photoId, version);
    if (cached) return json({ text: cached, cached: true }, 200, headers);

    // Trimmed because a key pasted into `wrangler secret put` can pick up a
    // stray line ending, which Google rejects with a bare, bodiless 400.
    const apiKey = (env.GEMINI_API_KEY || "").trim();
    if (!apiKey) {
      return json({ error: "Auth failed: GEMINI_API_KEY is not set" }, 502, headers);
    }

    const geminiBody = {
      contents: [{
        parts: [{ text: prompt }, ...imageParts],
      }],
    };

    const geminiRes = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json", "x-goog-api-key": apiKey },
        body: JSON.stringify(geminiBody),
      }
    );
    // Read as text first: some rejections arrive with an empty or non-JSON
    // body, and parsing those directly threw, crashing the Worker with an
    // opaque Cloudflare 1101 instead of saying what Google objected to.
    const geminiText = await geminiRes.text();
    let geminiData = {};
    try {
      geminiData = JSON.parse(geminiText);
    } catch {}

    if (!geminiRes.ok) {
      const message = (geminiData.error && geminiData.error.message) || geminiText.slice(0, 300) || "empty response";
      return json({ error: `Gemini request failed (${geminiRes.status}): ${message}` }, 502, headers);
    }

    const text = geminiData.candidates?.[0]?.content?.parts?.[0]?.text;
    const description = text ? text.trim() : "No description returned.";

    // Only a real description is worth keeping — caching "No description
    // returned." would make one bad response permanent for that photo.
    if (text) {
      await writeCachedDescription(env, photoId, version, description);
    }

    return json({ text: description }, 200, headers);
  },
};
