"use client";

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  type ReactNode,
} from "react";

import { useAuth } from "@/components/providers/auth-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import { conversationsApi } from "@/lib/api/endpoints";
import type { ApiError } from "@/lib/api/errors";
import type { ConversationResponse, UUID } from "@/lib/api/types";

interface ConversationsContextValue {
  conversations: ConversationResponse[];
  loading: boolean;
  error: ApiError | null;
  refresh: () => void;
  createOrReuseBlankConversation: () => Promise<ConversationResponse>;
  renameConversation: (
    id: UUID,
    title: string
  ) => Promise<ConversationResponse>;
  deleteConversation: (id: UUID) => Promise<void>;
  upsertConversation: (conversation: ConversationResponse) => void;
  getById: (id: UUID) => ConversationResponse | undefined;
}

const ConversationsContext = createContext<ConversationsContextValue | null>(
  null
);

function conversationTime(conversation: ConversationResponse): number {
  const parsed = Date.parse(
    conversation.lastMessageAt ?? conversation.createdAt
  );
  return Number.isNaN(parsed) ? 0 : parsed;
}

/**
 * Single ordering rule for the sidebar. Matches the backend query:
 * COALESCE(last_message_at, created_at) DESC, id DESC.
 */
function compareConversations(
  left: ConversationResponse,
  right: ConversationResponse
): number {
  const timeDelta = conversationTime(right) - conversationTime(left);
  if (timeDelta !== 0) {
    return timeDelta;
  }
  if (left.id === right.id) {
    return 0;
  }
  return left.id < right.id ? 1 : -1;
}

export function ConversationsProvider({ children }: { children: ReactNode }) {
  const { status, user } = useAuth();
  const enabled = status === "authenticated" && Boolean(user);

  const { data, loading, error, reload, mutate } = useAsyncData(
    () => conversationsApi.list(),
    user ? `conversations:${user.id}` : "conversations:anon",
    {
      enabled,
      errorMessage: "加载会话列表失败",
    }
  );

  const conversations = useMemo(
    () => [...(data ?? [])].sort(compareConversations),
    [data]
  );

  const upsertConversation = useCallback(
    (conversation: ConversationResponse) => {
      mutate((prev) => [
        conversation,
        ...(prev ?? []).filter((item) => item.id !== conversation.id),
      ]);
    },
    [mutate]
  );

  const createOrReuseBlankConversation = useCallback(async () => {
    const conversation = await conversationsApi.createBlank();
    upsertConversation(conversation);
    return conversation;
  }, [upsertConversation]);

  const renameConversation = useCallback(
    async (id: UUID, title: string) => {
      const updated = await conversationsApi.rename(id, { title });
      upsertConversation(updated);
      return updated;
    },
    [upsertConversation]
  );

  const deleteConversation = useCallback(
    async (id: UUID) => {
      await conversationsApi.delete(id);
      mutate((prev) => (prev ?? []).filter((item) => item.id !== id));
    },
    [mutate]
  );

  const getById = useCallback(
    (id: UUID) => conversations.find((item) => item.id === id),
    [conversations]
  );

  const value = useMemo<ConversationsContextValue>(
    () => ({
      conversations,
      loading,
      error,
      refresh: reload,
      createOrReuseBlankConversation,
      renameConversation,
      deleteConversation,
      upsertConversation,
      getById,
    }),
    [
      conversations,
      loading,
      error,
      reload,
      createOrReuseBlankConversation,
      renameConversation,
      deleteConversation,
      upsertConversation,
      getById,
    ]
  );

  return (
    <ConversationsContext value={value}>{children}</ConversationsContext>
  );
}

export function useConversations(): ConversationsContextValue {
  const ctx = useContext(ConversationsContext);
  if (!ctx) {
    throw new Error("useConversations must be used inside ConversationsProvider");
  }
  return ctx;
}
