const UPSTREAM_ORIGIN = "https://lrclib.net";
const ALLOWED_PATHS = new Set(["/api/get", "/api/search"]);
const MAX_QUERY_LENGTH = 2048;
const MAX_RESPONSE_BYTES = 1024 * 1024;
const UPSTREAM_TIMEOUT_MS = 4000;

export default {
  async fetch(request) {
    if (request.method !== "GET") {
      return jsonResponse({ error: "Method not allowed" }, 405, {
        Allow: "GET",
      });
    }

    const requestUrl = new URL(request.url);
    if (requestUrl.pathname === "/health") {
      return jsonResponse({
        service: "carlyrics-lrclib-proxy",
        status: "ok",
      });
    }

    if (!ALLOWED_PATHS.has(requestUrl.pathname)) {
      return jsonResponse({ error: "Not found" }, 404);
    }
    if (requestUrl.search.length > MAX_QUERY_LENGTH) {
      return jsonResponse({ error: "Query too long" }, 414);
    }

    const upstreamUrl = new URL(requestUrl.pathname, UPSTREAM_ORIGIN);
    upstreamUrl.search = requestUrl.search;

    try {
      const upstreamResponse = await fetch(upstreamUrl, {
        headers: {
          Accept: "application/json",
          "User-Agent": "CarLyrics-Worker/1.0",
        },
        redirect: "follow",
        signal: AbortSignal.timeout(UPSTREAM_TIMEOUT_MS),
        cf: {
          cacheEverything: true,
          cacheTtlByStatus: {
            "200-299": 21600,
            "404": 300,
            "500-599": 0,
          },
        },
      });

      const headers = new Headers();
      headers.set(
        "Content-Type",
        upstreamResponse.headers.get("Content-Type") || "application/json; charset=utf-8",
      );
      headers.set("Cache-Control", upstreamResponse.ok
        ? "public, max-age=21600"
        : "no-store");
      headers.set("X-CarLyrics-Proxy", "cloudflare-worker");
      headers.set("X-Content-Type-Options", "nosniff");

      const retryAfter = upstreamResponse.headers.get("Retry-After");
      if (retryAfter) {
        headers.set("Retry-After", retryAfter);
      }

      const contentLength = Number(upstreamResponse.headers.get("Content-Length"));
      if (Number.isFinite(contentLength) && contentLength > MAX_RESPONSE_BYTES) {
        return jsonResponse({ error: "Upstream response too large" }, 502);
      }
      const body = await upstreamResponse.arrayBuffer();
      if (body.byteLength > MAX_RESPONSE_BYTES) {
        return jsonResponse({ error: "Upstream response too large" }, 502);
      }

      return new Response(body, {
        status: upstreamResponse.status,
        statusText: upstreamResponse.statusText,
        headers,
      });
    } catch (error) {
      return jsonResponse({
        error: error && error.name === "TimeoutError"
          ? "Upstream timeout"
          : "Upstream unavailable",
      }, 502);
    }
  },
};

function jsonResponse(body, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "application/json; charset=utf-8",
      "X-Content-Type-Options": "nosniff",
      ...extraHeaders,
    },
  });
}
