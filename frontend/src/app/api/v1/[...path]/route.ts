import { NextResponse } from "next/server";

const HOP_BY_HOP_HEADERS = new Set([
  "connection",
  "content-length",
  "content-encoding",
  "host",
  "keep-alive",
  "proxy-authenticate",
  "proxy-authorization",
  "te",
  "trailer",
  "transfer-encoding",
  "upgrade",
]);

function backendBaseUrl(): string {
  return (process.env.VISIONFORGE_API_BASE_URL ?? "http://127.0.0.1:8080").replace(
    /\/+$/,
    ""
  );
}

async function proxy(
  request: Request,
  context: { params: Promise<{ path: string[] }> }
): Promise<Response> {
  const { path } = await context.params;
  const requestUrl = new URL(request.url);
  const target = new URL(
    `/api/v1/${path.map(encodeURIComponent).join("/")}${requestUrl.search}`,
    backendBaseUrl()
  );

  const headers = new Headers(request.headers);
  for (const name of HOP_BY_HOP_HEADERS) {
    headers.delete(name);
  }
  // Let the upstream choose its own content negotiation; Node fetch decompresses
  // transparently, so forwarding the browser encoding would corrupt the body.
  headers.delete("accept-encoding");

  const init: RequestInit & { duplex?: "half" } = {
    method: request.method,
    headers,
    redirect: "manual",
    cache: "no-store",
    signal: request.signal,
  };
  if (request.method !== "GET" && request.method !== "HEAD") {
    init.body = request.body;
    init.duplex = "half";
  }

  let upstream: Response;
  try {
    upstream = await fetch(target, init);
  } catch {
    return NextResponse.json(
      {
        code: "COMMON_SERVICE_UNAVAILABLE",
        message: "后端 API 暂不可用",
        data: null,
      },
      { status: 502 }
    );
  }

  const responseHeaders = new Headers(upstream.headers);
  for (const name of HOP_BY_HOP_HEADERS) {
    responseHeaders.delete(name);
  }
  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: responseHeaders,
  });
}

export const GET = proxy;
export const POST = proxy;
export const PUT = proxy;
export const PATCH = proxy;
export const DELETE = proxy;
export const HEAD = proxy;
export const OPTIONS = proxy;
