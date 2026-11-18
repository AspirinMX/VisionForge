import type { UUID } from "@/lib/api/types";

export interface PendingConversationTurn {
  prompt: string;
  assetIds: UUID[];
  createdAt: string;
}

function handoffKey(userId: UUID, conversationId: UUID): string {
  return `vf.pendingTurn.v1:${userId}:${conversationId}`;
}

export const draftHandoff = {
  set(userId: UUID, conversationId: UUID, turn: PendingConversationTurn): void {
    if (typeof window === "undefined") {
      return;
    }
    try {
      window.sessionStorage.setItem(
        handoffKey(userId, conversationId),
        JSON.stringify(turn)
      );
    } catch {
      // Ignore storage failures.
    }
  },
  consume(userId: UUID, conversationId: UUID): PendingConversationTurn | null {
    if (typeof window === "undefined") {
      return null;
    }
    try {
      const storageKey = handoffKey(userId, conversationId);
      const raw = window.sessionStorage.getItem(storageKey);
      if (!raw) {
        return null;
      }
      window.sessionStorage.removeItem(storageKey);
      return JSON.parse(raw) as PendingConversationTurn;
    } catch {
      return null;
    }
  },
};
