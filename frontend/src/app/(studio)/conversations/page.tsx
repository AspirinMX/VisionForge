"use client";

import {
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
} from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  ArrowUpIcon,
  ClapperboardIcon,
  FileTextIcon,
  FilmIcon,
  ImagePlusIcon,
  LayersIcon,
  Loader2Icon,
  MessageSquareIcon,
  SparklesIcon,
  XIcon,
} from "lucide-react";
import { toast } from "sonner";

import { PageHeader } from "@/components/layout/page-header";
import { useAuth } from "@/components/providers/auth-provider";
import { useConversations } from "@/components/providers/conversations-provider";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import { putToPresignedUrl } from "@/lib/api/client";
import { assetsApi } from "@/lib/api/endpoints";
import { notifyApiError } from "@/lib/api/feedback";
import type { UUID } from "@/lib/api/types";
import { formatShortTime } from "@/lib/format";
import { draftHandoff } from "@/lib/storage/draft-handoff";
import { placeholderStore } from "@/lib/storage/placeholder-store";

const ALLOWED_IMAGE_TYPES = new Set([
  "image/png",
  "image/jpeg",
  "image/webp",
  "image/gif",
  "image/avif",
]);

const MAX_ATTACHMENTS = 8;

const STARTER_PROMPTS = [
  {
    label: "一句话生成短片",
    icon: ClapperboardIcon,
    prompt:
      "帮我制作一条 15 秒的治愈系短片：一只橘猫第一次推开木门走出院子，试探性地踩过晨露。请按剧本、分镜、首帧图、分镜视频和顺序成片的流程推进。",
  },
  {
    label: "参考图风格生图",
    icon: SparklesIcon,
    prompt:
      "请结合我附带的参考图分析主体外观与光线风格，随后优化提示词并生成一张 16:9 电影感海报。",
  },
  {
    label: "首帧图生动态视频",
    icon: FilmIcon,
    prompt:
      "我有一张首帧参考图，请基于它帮我设计镜头运动描述，并生成一段 5 秒的视频片段。",
  },
  {
    label: "撰写分场剧本",
    icon: FileTextIcon,
    prompt:
      "我想拍一支极简风格的精品手冲咖啡产品广告，时长约 20 秒，请先帮我撰写标准 Markdown 分场剧本。",
  },
];

interface QueuedFile {
  id: string;
  file: File;
  previewUrl: string;
}

export default function ConversationsHomePage() {
  const router = useRouter();
  const { user } = useAuth();
  const { conversations, createConversation } = useConversations();

  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const [prompt, setPrompt] = useState("");
  const [queuedFiles, setQueuedFiles] = useState<QueuedFile[]>([]);
  const [submitting, setSubmitting] = useState(false);

  const handleFilesSelected = (event: ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (selected.length === 0) {
      return;
    }

    const availableSlots = MAX_ATTACHMENTS - queuedFiles.length;
    if (availableSlots <= 0) {
      toast.warning(`单条消息最多附带 ${MAX_ATTACHMENTS} 张参考图片`);
      return;
    }

    const accepted: QueuedFile[] = [];
    for (const file of selected.slice(0, availableSlots)) {
      if (!ALLOWED_IMAGE_TYPES.has(file.type)) {
        toast.error(`不支持的图片格式：${file.name}（仅支持 PNG/JPEG/WebP/GIF/AVIF）`);
        continue;
      }
      accepted.push({
        id: `${file.name}-${file.size}-${crypto.randomUUID()}`,
        file,
        previewUrl: URL.createObjectURL(file),
      });
    }

    if (selected.length > availableSlots) {
      toast.warning(`已截断超出上限的文件，最多附带 ${MAX_ATTACHMENTS} 张图片`);
    }
    if (accepted.length > 0) {
      setQueuedFiles((prev) => [...prev, ...accepted]);
    }
  };

  const removeQueuedFile = (id: string) => {
    setQueuedFiles((prev) => {
      const target = prev.find((item) => item.id === id);
      if (target) {
        URL.revokeObjectURL(target.previewUrl);
      }
      return prev.filter((item) => item.id !== id);
    });
  };

  const handleStartConversation = async () => {
    const content = prompt.trim();
    if (!content || submitting || !user) {
      return;
    }

    setSubmitting(true);
    try {
      const conversation = await createConversation();
      const uploadedAssetIds: UUID[] = [];

      for (const item of queuedFiles) {
        const ticket = await assetsApi.createUpload(conversation.id, {
          kind: "IMAGE",
          filename: item.file.name,
          mimeType: item.file.type,
          sizeBytes: item.file.size,
        });
        await putToPresignedUrl({
          url: ticket.uploadUrl,
          file: item.file,
          requiredHeaders: ticket.requiredHeaders,
        });
        const asset = await assetsApi.completeUpload(
          conversation.id,
          ticket.uploadSessionId
        );
        placeholderStore.upsertAsset(user.id, conversation.id, asset);
        uploadedAssetIds.push(asset.id);
        URL.revokeObjectURL(item.previewUrl);
      }

      draftHandoff.set(user.id, conversation.id, {
        prompt: content,
        assetIds: uploadedAssetIds,
        createdAt: new Date().toISOString(),
      });
      setQueuedFiles([]);
      router.push(`/conversations/${conversation.id}`);
    } catch (error) {
      notifyApiError(error, "创建新对话失败");
    } finally {
      setSubmitting(false);
    }
  };

  const handleCreateBlankConversation = async () => {
    if (submitting) {
      return;
    }
    setSubmitting(true);
    try {
      const conversation = await createConversation();
      router.push(`/conversations/${conversation.id}`);
    } catch (error) {
      notifyApiError(error, "创建空白会话失败");
    } finally {
      setSubmitting(false);
    }
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (
      event.key === "Enter" &&
      !event.shiftKey &&
      !event.nativeEvent.isComposing
    ) {
      event.preventDefault();
      void handleStartConversation();
    }
  };

  const recentConversations = conversations.slice(0, 4);

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="新对话"
        actions={
          <Button
            type="button"
            variant="ghost"
            size="sm"
            onClick={handleCreateBlankConversation}
            disabled={submitting}
          >
            <LayersIcon />
            <span>空白工作区</span>
          </Button>
        }
      />

      <div className="flex flex-1 flex-col items-center justify-center overflow-y-auto px-4 py-8">
        <div className="w-full max-w-3xl space-y-6">
          <div className="space-y-2 text-center">
            <h2 className="text-2xl font-semibold tracking-tight text-foreground sm:text-3xl">
              你好，{user?.displayName ?? "创作者"}，准备创作什么作品？
            </h2>
            <p className="text-sm text-muted-foreground">
              用自然语言描述创意或附上参考图，对话驱动剧本、分镜、首帧图、分镜视频与顺序成片。
            </p>
          </div>

          <div className="rounded-3xl border bg-card p-3 shadow-xs transition-shadow focus-within:ring-2 focus-within:ring-ring/30">
            {queuedFiles.length > 0 ? (
              <div className="mb-2 flex flex-wrap gap-2 px-1 pt-1">
                {queuedFiles.map((item) => (
                  <div
                    key={item.id}
                    className="group relative size-16 overflow-hidden rounded-xl border bg-muted"
                  >
                    {/* Local object URL preview before upload */}
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      src={item.previewUrl}
                      alt={item.file.name}
                      className="size-full object-cover"
                    />
                    <Button
                      type="button"
                      size="icon-xs"
                      variant="secondary"
                      onClick={() => removeQueuedFile(item.id)}
                      aria-label={`移除 ${item.file.name}`}
                      className="absolute top-1 right-1 size-5 rounded-full opacity-90"
                    >
                      <XIcon className="size-3" />
                    </Button>
                  </div>
                ))}
              </div>
            ) : null}

            <Textarea
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder="输入你的短片构想、分镜要求或图片生成需求（Enter 发送，Shift+Enter 换行）..."
              rows={3}
              maxLength={32000}
              disabled={submitting}
              className="min-h-20 resize-none border-0 bg-transparent px-2 py-1 text-sm shadow-none focus-visible:ring-0"
            />

            <div className="mt-2 flex items-center justify-between gap-2 px-1">
              <div className="flex items-center gap-2">
                <input
                  ref={fileInputRef}
                  type="file"
                  accept="image/png,image/jpeg,image/webp,image/gif,image/avif"
                  multiple
                  className="hidden"
                  onChange={handleFilesSelected}
                />
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={submitting || queuedFiles.length >= MAX_ATTACHMENTS}
                >
                  <ImagePlusIcon />
                  <span>
                    添加参考图
                    {queuedFiles.length > 0
                      ? ` (${queuedFiles.length}/${MAX_ATTACHMENTS})`
                      : ""}
                  </span>
                </Button>
              </div>

              <Button
                type="button"
                size="icon"
                disabled={!prompt.trim() || submitting}
                onClick={handleStartConversation}
                aria-label="创建并发送"
              >
                {submitting ? (
                  <Loader2Icon className="animate-spin" />
                ) : (
                  <ArrowUpIcon />
                )}
              </Button>
            </div>
          </div>

          <div className="flex flex-wrap items-center justify-center gap-2">
            {STARTER_PROMPTS.map((item) => {
              const Icon = item.icon;
              return (
                <Button
                  key={item.label}
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => setPrompt(item.prompt)}
                  disabled={submitting}
                >
                  <Icon className="text-muted-foreground" />
                  <span>{item.label}</span>
                </Button>
              );
            })}
          </div>

          {recentConversations.length > 0 ? (
            <div className="pt-4">
              <div className="mb-2 flex items-center justify-between text-xs text-muted-foreground">
                <span>最近继续</span>
                <span className="tabular-nums">
                  共 {conversations.length} 个会话
                </span>
              </div>
              <div className="grid gap-2 sm:grid-cols-2">
                {recentConversations.map((conv) => (
                  <Button
                    key={conv.id}
                    variant="outline"
                    nativeButton={false}
                    render={<Link href={`/conversations/${conv.id}`} />}
                    className="h-auto justify-between rounded-2xl px-3.5 py-2.5 text-left"
                  >
                    <span className="flex min-w-0 items-center gap-2">
                      <MessageSquareIcon className="size-4 shrink-0 text-muted-foreground" />
                      <span className="truncate font-normal">{conv.title}</span>
                    </span>
                    <Badge
                      variant="secondary"
                      className="ml-2 shrink-0 text-[11px] font-normal tabular-nums"
                    >
                      {formatShortTime(conv.lastMessageAt ?? conv.createdAt)}
                    </Badge>
                  </Button>
                ))}
              </div>
            </div>
          ) : null}
        </div>
      </div>
    </div>
  );
}
