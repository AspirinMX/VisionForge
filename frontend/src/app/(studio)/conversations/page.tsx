"use client";

import {
  useRef,
  useState,
  type ChangeEvent,
  type KeyboardEvent,
} from "react";
import { useRouter } from "next/navigation";
import { Fade } from "@animate-ui/react/primitives/effects/fade";
import { Slide } from "@animate-ui/react/primitives/effects/slide";

import { CONTROL } from "@/lib/motion";
import {
  ArrowUpIcon,
  ClapperboardIcon,
  FileTextIcon,
  FilmIcon,
  Loader2Icon,
  PanelRightOpenIcon,
  PlusIcon,
  SparklesIcon,
  XIcon,
} from "lucide-react";
import { toast } from "sonner";

import { PageHeader } from "@/components/layout/page-header";
import { useAuth } from "@/components/providers/auth-provider";
import { useConversations } from "@/components/providers/conversations-provider";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { putToPresignedUrl } from "@/lib/api/client";
import { assetsApi } from "@/lib/api/endpoints";
import { notifyApiError } from "@/lib/api/feedback";
import type { UUID } from "@/lib/api/types";
import { draftHandoff } from "@/lib/storage/draft-handoff";

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
  const { createConversation } = useConversations();

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

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="新聊天"
        actions={
          <Tooltip>
            <TooltipTrigger
              render={
                <Button
                  type="button"
                  variant="ghost"
                  size="icon-sm"
                  onClick={handleCreateBlankConversation}
                  disabled={submitting}
                  aria-label="新建空白工作区"
                />
              }
            >
              <PanelRightOpenIcon />
            </TooltipTrigger>
            <TooltipContent>新建空白工作区</TooltipContent>
          </Tooltip>
        }
      />

      <div className="flex flex-1 flex-col items-center justify-center overflow-y-auto px-4 py-8">
        <Fade transition={CONTROL.enter} className="w-full max-w-3xl">
          <Slide direction="up" offset={8} transition={CONTROL.enter} className="space-y-6">
            <div className="space-y-1.5 text-center">
              <h2 className="text-2xl font-medium tracking-tight text-foreground sm:text-3xl">
                你好，{user?.displayName ?? "创作者"}
              </h2>
              <p className="text-sm text-muted-foreground">
                描述创作想法或附上参考图，开始生成剧本、分镜与成片。
              </p>
            </div>

            <div className="rounded-surface-lg border border-border/80 bg-card/95 p-3 shadow-md transition-shadow motion-reduce:transition-none focus-within:ring-2 focus-within:ring-ring/20">
            {queuedFiles.length > 0 ? (
              <div className="mb-2 flex flex-wrap gap-2 px-1 pt-1">
                {queuedFiles.map((item) => (
                  <div
                    key={item.id}
                    className="group relative size-16 overflow-hidden rounded-surface-sm border bg-muted"
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
              placeholder="输入你的创作构想或分镜需求..."
              rows={2}
              maxLength={32000}
              disabled={submitting}
              className="min-h-16 resize-none border-0 bg-transparent px-2 py-1 text-sm shadow-none focus-visible:ring-0"
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
                <Tooltip>
                  <TooltipTrigger
                    render={
                      <Button
                        type="button"
                        variant="ghost"
                        size="icon"
                        onClick={() => fileInputRef.current?.click()}
                        disabled={submitting || queuedFiles.length >= MAX_ATTACHMENTS}
                        aria-label="添加参考图"
                        className="rounded-full text-muted-foreground hover:text-foreground"
                      />
                    }
                  >
                    <PlusIcon className="size-4" />
                  </TooltipTrigger>
                  <TooltipContent>添加参考图</TooltipContent>
                </Tooltip>
              </div>

              <Button
                type="button"
                size="icon"
                disabled={!prompt.trim() || submitting}
                onClick={handleStartConversation}
                aria-label="创建并发送"
                className="rounded-full"
              >
                {submitting ? (
                  <Loader2Icon className="animate-spin motion-reduce:animate-none" />
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
          </Slide>
        </Fade>
      </div>
    </div>
  );
}
