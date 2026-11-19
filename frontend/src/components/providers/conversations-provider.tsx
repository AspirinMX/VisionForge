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
  createConversation: (title?: string) => Promise<ConversationResponse>;
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

  const conversations = useMemo(() => data ?? [], [data]);

  const upsertConversation = useCallback(
    (conversation: ConversationResponse) => {
      mutate((prev) => {
        const current = prev ?? [];
        const next = [
          conversation,
          ...current.filter((item) => item.id !== conversation.id),
        ];
        return next.sort((a, b) => {
          const timeA = Date.parse(a.lastMessageAt ?? a.createdAt);
          const timeB = Date.parse(b.lastMessageAt ?? b.createdAt);
          return timeB - timeA;
        });
      });
    },
    [mutate]
  );

  const createConversation = useCallback(
    async (title?: string) => {
      const created = await conversationsApi.create({ title });
      upsertConversation(created);
      return created;
    },
    [upsertConversation]
  );

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
      createConversation,
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
      createConversation,
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
