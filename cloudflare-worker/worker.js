// Proxies Gemini "describe this photo" requests for the public PhotoSync page
// (docs/index.html), so the Gemini API key stays server-side instead of
// sitting in plaintext HTML where it gets auto-detected and revoked.
//
// The real key is a Worker secret (`wrangler secret put GEMINI_API_KEY`),
// never committed here. Requests are restricted to the public page's origin
// via CORS, and the prompt/model are fixed server-side rather than accepted
// from the caller, so this can't be used as an open-ended Gemini relay.

const ALLOWED_ORIGIN = "https://johnvv999.github.io";
const GEMINI_MODEL = "gemini-flash-latest";
const GEMINI_PROMPT =
  "Briefly describe what's in this photo and identify any recognizable landmark, location, or point of interest, in 2-3 sentences.";

function corsHeaders(origin) {
  const headers = {
    "Access-Control-Allow-Methods": "POST, OPTIONS",
    "Access-Control-Allow-Headers": "Content-Type",
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
    if (origin !== ALLOWED_ORIGIN) {
      return json({ error: "Forbidden origin" }, 403, headers);
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

    const { mimeType, data } = body;
    if (!data || typeof data !== "string") {
      return json({ error: "Missing image data" }, 400, headers);
    }

    const geminiBody = {
      contents: [{
        parts: [
          { text: GEMINI_PROMPT },
          { inline_data: { mime_type: mimeType || "image/jpeg", data } },
        ],
      }],
    };

    const geminiRes = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${env.GEMINI_API_KEY}`,
      { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(geminiBody) }
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
