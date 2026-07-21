// Proxies Gemini "describe this photo" requests for both the public PhotoSync
// page (docs/index.html) and the Android app, so no Gemini credential ever
// sits in plaintext HTML or inside the APK — both are trivially extractable
// by anyone, and a plain API key hardcoded in the public page already got
// auto-detected and revoked by Google once.
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
// The public page is instead authorized via CORS: only requests whose
// Origin header matches ALLOWED_ORIGIN are accepted, so a plain <script>
// fetch from any other site is rejected server-side (CORS headers alone
// only stop browsers from *reading* a cross-origin response — the origin
// check below is what actually blocks the request).

const ALLOWED_ORIGIN = "https://johnvv999.github.io";
const GEMINI_MODEL = "gemini-flash-latest";
const GEMINI_PROMPT =
  "Briefly describe what's in this photo and identify any recognizable landmark, location, or point of interest, in 2-3 sentences.";
const TOKEN_SCOPE = "https://www.googleapis.com/auth/generative-language";

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

    const { mimeType, data, lat, lon } = body;
    if (!data || typeof data !== "string") {
      return json({ error: "Missing image data" }, 400, headers);
    }

    // Optional GPS from the caller lets Gemini pin the actual location/landmark.
    let prompt = GEMINI_PROMPT;
    if (typeof lat === "number" && typeof lon === "number") {
      prompt += ` The photo was taken at approximately latitude ${lat.toFixed(6)}, longitude ${lon.toFixed(6)}; use these coordinates to help identify the specific place, landmark, or neighborhood.`;
    }

    let accessToken;
    try {
      accessToken = await getAccessToken(env);
    } catch (e) {
      return json({ error: `Auth failed: ${e.message}` }, 502, headers);
    }

    const geminiBody = {
      contents: [{
        parts: [
          { text: prompt },
          { inline_data: { mime_type: mimeType || "image/jpeg", data } },
        ],
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
    return json({ text: text ? text.trim() : "No description returned." }, 200, headers);
  },
};
