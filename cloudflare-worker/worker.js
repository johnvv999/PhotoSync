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
// Auth is service-account based (GCP org policy on this project now mandates
// it for any new Gemini-capable key) rather than a plain API key: this Worker
// signs a JWT with the service account's private key and exchanges it for a
// short-lived OAuth2 access token, then calls Gemini with a Bearer token
// instead of `?key=`.
//
// Two secrets, set via `wrangler secret put <NAME>`:
//   SERVICE_ACCOUNT_JSON — the full JSON key downloaded from IAM & Admin →
//     Service Accounts → (the account) → Keys → Add Key → JSON. Paste the
//     whole file contents as the secret value.
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

const TOKEN_SCOPE = "https://www.googleapis.com/auth/generative-language";

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

// Cached across requests within the same Worker isolate — avoids minting a
// fresh access token (an extra round trip to Google) on every photo. Isolates
// get recycled periodically, at which point this just starts empty again.
let cachedToken = null;

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

function base64url(input) {
  const bytes = typeof input === "string" ? new TextEncoder().encode(input) : new Uint8Array(input);
  let binary = "";
  for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function pemToArrayBuffer(pem) {
  const b64 = pem
    .replace(/-----BEGIN PRIVATE KEY-----/, "")
    .replace(/-----END PRIVATE KEY-----/, "")
    .replace(/\s/g, "");
  const binary = atob(b64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

/** Signs a JWT with the service account's private key and exchanges it for a short-lived Google OAuth2 access token. */
async function getAccessToken(env) {
  const now = Math.floor(Date.now() / 1000);
  if (cachedToken && cachedToken.expiresAt > now + 60) {
    return cachedToken.accessToken;
  }

  const serviceAccount = JSON.parse(env.SERVICE_ACCOUNT_JSON);
  const tokenUri = serviceAccount.token_uri || "https://oauth2.googleapis.com/token";

  const encodedHeader = base64url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const encodedClaims = base64url(JSON.stringify({
    iss: serviceAccount.client_email,
    scope: TOKEN_SCOPE,
    aud: tokenUri,
    iat: now,
    exp: now + 3600,
  }));
  const signingInput = `${encodedHeader}.${encodedClaims}`;

  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    pemToArrayBuffer(serviceAccount.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    new TextEncoder().encode(signingInput)
  );
  const jwt = `${signingInput}.${base64url(signature)}`;

  const tokenRes = await fetch(tokenUri, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion: jwt,
    }),
  });
  const tokenData = await tokenRes.json();
  if (!tokenRes.ok) {
    throw new Error(`Token exchange failed: ${tokenData.error_description || tokenData.error || tokenRes.status}`);
  }

  cachedToken = { accessToken: tokenData.access_token, expiresAt: now + tokenData.expires_in };
  return cachedToken.accessToken;
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

    let accessToken;
    try {
      accessToken = await getAccessToken(env);
    } catch (e) {
      return json({ error: `Auth failed: ${e.message}` }, 502, headers);
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
        headers: { "Content-Type": "application/json", "Authorization": `Bearer ${accessToken}` },
        body: JSON.stringify(geminiBody),
      }
    );
    const geminiData = await geminiRes.json();

    if (!geminiRes.ok) {
      const message = (geminiData.error && geminiData.error.message) || "unknown error";
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
