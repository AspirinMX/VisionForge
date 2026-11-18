"use client";

import { useEffect } from "react";
import Link from "next/link";
import { notFound, useParams } from "next/navigation";
import {
  MessageSquareIcon,
  PanelRightIcon,
  RefreshCwIcon,
} from "lucide-react";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { PageHeader } from "@/components/layout/page-header";
import { useConversations } from "@/components/providers/conversations-provider";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ScrollArea } from "@/components/ui/scroll-area";
import { Skeleton } from "@/components/ui/skeleton";
import { useAsyncData } from "@/hooks/use-async-data";
import { conversationsApi } from "@/lib/api/endpoints";
import { formatDateTime } from "@/lib/format";

export default function ConversationDetailPage() {
  const params = useParams<{ conversationId: string }>();
  const conversationId = params.conversationId;
  const { upsertConversation } = useConversations();

  const { data, loading, error, reload } = useAsyncData(
    async () => {
      const [conversation, messages] = await Promise.all([
        conversationsApi.get(conversationId),
        conversationsApi.messages(conversationId),
      ]);
      return { conversation, messages };
    },
    `conversation:${conversationId}`,
    { errorMessage: "加载会话详情失败" }
  );

  useEffect(() => {
    if (data?.conversation) {
      upsertConversation(data.conversation);
    }
  }, [data?.conversation, upsertConversation]);

  if (error?.isNotFound) {
    notFound();
  }

  const conversation = data?.conversation ?? null;
  const messages = data?.messages ?? [];

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title={conversation?.title ?? "加载会话中..."}
        badge={
          conversation ? (
            <Badge variant="outline" className="font-mono text-[11px]">
              {conversation.id.slice(0, 8)}
            </Badge>
          ) : null
        }
        actions={
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={reload}
              disabled={loading}
              aria-label="刷新会话"
            >
              <RefreshCwIcon className={loading ? "animate-spin" : ""} />
            </Button>
            <Button type="button" variant="outline" size="sm" disabled>
              <PanelRightIcon />
              <span>Studio Canvas</span>
            </Button>
          </div>
        }
      />

      {error ? (
        <div className="mx-auto w-full max-w-3xl p-4">
          <ApiErrorAlert error={error} title="无法读取会话内容" />
        </div>
      ) : null}

      <ScrollArea className="flex-1">
        <div className="mx-auto flex w-full max-w-3xl flex-col gap-4 px-4 py-6">
          {loading && !conversation ? (
            <div className="space-y-4">
              <Skeleton className="ml-auto h-14 w-2/3 rounded-2xl" />
              <Skeleton className="h-28 w-4/5 rounded-2xl" />
            </div>
          ) : messages.length === 0 ? (
            <div className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-dashed p-10 text-center">
              <MessageSquareIcon className="size-8 text-muted-foreground" />
              <div className="space-y-1">
                <p className="text-sm font-medium">工作区会话已就绪</p>
                <p className="text-xs text-muted-foreground">
                  已验证会话归属与消息读取链路，流式对话控制台与 Studio Canvas 工作区面板将于下一步完整接入。
                </p>
              </div>
              <Button
                variant="outline"
                size="sm"
                nativeButton={false}
                render={<Link href="/conversations" />}
              >
                返回新对话页
              </Button>
            </div>
          ) : (
            messages.map((message) => (
              <div
                key={message.id}
                className={
                  message.role === "USER"
                    ? "ml-auto max-w-[85%] rounded-2xl bg-primary px-4 py-2.5 text-sm text-primary-foreground"
                    : "max-w-[92%] space-y-1 rounded-2xl border bg-card px-4 py-3 text-sm text-card-foreground"
                }
              >
                <div className="whitespace-pre-wrap leading-relaxed">
                  {message.content}
                </div>
                <div
                  className={
                    message.role === "USER"
                      ? "mt-1 text-right text-[11px] opacity-75 tabular-nums"
                      : "mt-1 text-[11px] text-muted-foreground tabular-nums"
                  }
                >
                  #{message.sequenceNo} · {formatDateTime(message.createdAt)}
                </div>
              </div>
            ))
          )}
        </div>
      </ScrollArea>
    </div>
  );
}
