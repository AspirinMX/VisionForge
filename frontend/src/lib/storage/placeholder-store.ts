import type { AssetResponse, UUID } from "@/lib/api/types";

/**
 * Client-side isolated placeholder cache for read capabilities that the backend API does not yet
 * expose (conversation asset list, composition task list, message attachments and active run id).
 * Every key is strictly scoped by userId + conversationId to mirror server-side isolation.
 */

export interface CachedMessageAttachment {
  messageId: UUID;
  assetIds: UUID[];
}

export interface CachedActiveRun {
  runId: UUID;
  createdAt: string;
}

function key(
  scope: "assets" | "compositions" | "messageParts" | "activeRun",
  userId: UUID,
  conversationId: UUID
): string {
  return `vf.placeholder.${scope}.v1:${userId}:${conversationId}`;
}

function readJson<T>(storageKey: string, fallback: T): T {
  if (typeof window === "undefined") {
    return fallback;
  }
  try {
    const raw = window.localStorage.getItem(storageKey);
    if (!raw) {
      return fallback;
    }
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

function writeJson(storageKey: string, value: unknown): void {
  if (typeof window === "undefined") {
    return;
  }
  try {
    if (value === null) {
      window.localStorage.removeItem(storageKey);
    } else {
      window.localStorage.setItem(storageKey, JSON.stringify(value));
    }
  } catch {
    // Ignore storage failures in restricted browser environments.
  }
}

export const placeholderStore = {
  getAssets(userId: UUID, conversationId: UUID): AssetResponse[] {
    return readJson<AssetResponse[]>(key("assets", userId, conversationId), []);
  },
  upsertAsset(userId: UUID, conversationId: UUID, asset: AssetResponse): AssetResponse[] {
    const current = this.getAssets(userId, conversationId);
    const next = [asset, ...current.filter((item) => item.id !== asset.id)];
    writeJson(key("assets", userId, conversationId), next);
    return next;
  },

  getCompositionTaskIds(userId: UUID, conversationId: UUID): UUID[] {
    return readJson<UUID[]>(key("compositions", userId, conversationId), []);
  },
  addCompositionTaskId(userId: UUID, conversationId: UUID, taskId: UUID): UUID[] {
    const current = this.getCompositionTaskIds(userId, conversationId);
    const next = [taskId, ...current.filter((id) => id !== taskId)];
    writeJson(key("compositions", userId, conversationId), next);
    return next;
  },

  getMessageAttachments(
    userId: UUID,
    conversationId: UUID
  ): Record<UUID, UUID[]> {
    return readJson<Record<UUID, UUID[]>>(
      key("messageParts", userId, conversationId),
      {}
    );
  },
  setMessageAttachments(
    userId: UUID,
    conversationId: UUID,
    messageId: UUID,
    assetIds: UUID[]
  ): void {
    if (assetIds.length === 0) {
      return;
    }
    const current = this.getMessageAttachments(userId, conversationId);
    writeJson(key("messageParts", userId, conversationId), {
      ...current,
      [messageId]: assetIds,
    });
  },

  getActiveRun(userId: UUID, conversationId: UUID): CachedActiveRun | null {
    return readJson<CachedActiveRun | null>(
      key("activeRun", userId, conversationId),
      null
    );
  },
  setActiveRun(
    userId: UUID,
    conversationId: UUID,
    run: CachedActiveRun | null
  ): void {
    writeJson(key("activeRun", userId, conversationId), run);
  },
};
