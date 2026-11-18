import { clearStoredSession } from "@/lib/auth/token-store";

import {
  ensureValidAccessToken,
  forceRefreshAccessToken,
} from "./client";
import {
  ApiError,
  apiErrorFromEnvelope,
  normalizeUnknownError,
} from "./errors";
import type { AgentStreamEvent, ApiResponse } from "./types";

export interface StreamHandlers {
  onEvent: (event: AgentStreamEvent) => void;
}

interface ParsedSseBlock {
  eventName: string;
  data: string;
}

function parseSseBlock(rawBlock: string): ParsedSseBlock | null {
  const lines = rawBlock.split(/\r?\n/);
  let eventName = "message";
  const dataLines: string[] = [];

  for (const line of lines) {
    if (!line || line.startsWith(":")) {
      continue;
    }
    const colonIndex = line.indexOf(":");
    const field = colonIndex >= 0 ? line.slice(0, colonIndex) : line;
    let value = colonIndex >= 0 ? line.slice(colonIndex + 1) : "";
    if (value.startsWith(" ")) {
      value = value.slice(1);
    }

    if (field === "event") {
      eventName = value;
    } else if (field === "data") {
      dataLines.push(value);
    }
  }

  if (dataLines.length === 0) {
    return null;
  }

  return {
    eventName,
    data: dataLines.join("\n"),
  };
}

function toAgentStreamEvent(block: ParsedSseBlock): AgentStreamEvent | null {
  try {
    const parsed = JSON.parse(block.data) as Record<string, unknown>;
    const type =
      typeof parsed.type === "string" ? parsed.type : block.eventName;

    switch (type) {
      case "accepted":
        return typeof parsed.runId === "string"
          ? { type: "accepted", runId: parsed.runId }
          : null;
      case "reasoning":
        return {
          type: "reasoning",
          content: typeof parsed.content === "string" ? parsed.content : "",
        };
      case "reasoning_summary":
        return {
          type: "reasoning_summary",
          content: typeof parsed.content === "string" ? parsed.content : "",
        };
      case "delta":
        return {
          type: "delta",
          content: typeof parsed.content === "string" ? parsed.content : "",
        };
      case "done":
        return typeof parsed.assistantMessageId === "string"
          ? { type: "done", assistantMessageId: parsed.assistantMessageId }
          : null;
      case "error":
        return {
          type: "error",
          message:
            typeof parsed.message === "string" && parsed.message.length > 0
              ? parsed.message
              : "流式执行过程中发生错误",
        };
      default:
        return null;
    }
  } catch {
    return null;
  }
}

export async function consumeAgentStream(params: {
  path: string;
  body: unknown;
  handlers: StreamHandlers;
  signal?: AbortSignal;
}): Promise<void> {
  const { path, body, handlers, signal } = params;

  const openStream = async (tokenOverride?: string | null): Promise<Response> => {
    const token =
      tokenOverride !== undefined
        ? tokenOverride
        : await ensureValidAccessToken();
    if (!token) {
      throw new ApiError({
        status: 401,
        code: "COMMON_UNAUTHORIZED",
        message: "未登录或登录状态已失效",
      });
    }

    return fetch(path, {
      method: "POST",
      headers: {
        Accept: "text/event-stream",
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify(body),
      signal,
    });
  };

  try {
    let response = await openStream();
    if (response.status === 401) {
      const refreshed = await forceRefreshAccessToken();
      if (refreshed) {
        response = await openStream(refreshed);
      } else {
        clearStoredSession();
      }
    }

    const contentType = response.headers.get("Content-Type") ?? "";
    if (!response.ok || contentType.includes("application/json")) {
      const headerTraceId = response.headers.get("X-Request-Id");
      const text = await response.text();
      let envelope: Partial<ApiResponse<unknown>> = {};
      try {
        envelope = text ? (JSON.parse(text) as Partial<ApiResponse<unknown>>) : {};
      } catch {
        envelope = {};
      }
      throw apiErrorFromEnvelope(response.status, envelope, headerTraceId);
    }

    if (!response.body) {
      throw new ApiError({
        status: 500,
        code: "CLIENT_STREAM_UNAVAILABLE",
        message: "浏览器不支持或未返回流式响应体",
      });
    }

    const reader = response.body.getReader();
    const decoder = new TextDecoder("utf-8");
    let buffer = "";

    const flushBlocks = (isFinal: boolean) => {
      const separatorRegex = /\r?\n\r?\n/;
      let match = separatorRegex.exec(buffer);
      while (match) {
        const rawBlock = buffer.slice(0, match.index);
        buffer = buffer.slice(match.index + match[0].length);
        const block = parseSseBlock(rawBlock);
        if (block) {
          const event = toAgentStreamEvent(block);
          if (event) {
            handlers.onEvent(event);
          }
        }
        match = separatorRegex.exec(buffer);
      }

      if (isFinal && buffer.trim().length > 0) {
        const block = parseSseBlock(buffer);
        buffer = "";
        if (block) {
          const event = toAgentStreamEvent(block);
          if (event) {
            handlers.onEvent(event);
          }
        }
      }
    };

    while (true) {
      const { value, done } = await reader.read();
      if (done) {
        buffer += decoder.decode();
        flushBlocks(true);
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      flushBlocks(false);
    }
  } catch (error) {
    throw normalizeUnknownError(error);
  }
}
