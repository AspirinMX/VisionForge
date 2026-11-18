"use client";

import { toast } from "sonner";

import { normalizeUnknownError } from "./errors";

export async function copyToClipboard(text: string): Promise<boolean> {
  if (typeof navigator === "undefined" || !navigator.clipboard) {
    return false;
  }
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

export function notifySuccess(title: string, description?: string): void {
  toast.success(title, description ? { description } : undefined);
}

export function notifyInfo(title: string, description?: string): void {
  toast.info(title, description ? { description } : undefined);
}

export function notifyError(title: string, description?: string): void {
  toast.error(title, description ? { description } : undefined);
}

export function notifyApiError(
  error: unknown,
  fallbackTitle = "操作未能完成"
): void {
  const apiError = normalizeUnknownError(error, fallbackTitle);
  if (apiError.code === "CLIENT_ABORTED") {
    return;
  }

  toast.error(apiError.message || fallbackTitle);
}
