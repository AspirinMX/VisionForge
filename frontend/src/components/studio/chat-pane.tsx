"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ChangeEvent,
  type DragEvent,
  type KeyboardEvent,
} from "react";
import { AnimatePresence, motion } from "motion/react";
import { ShimmeringText } from "@animate-ui/react/primitives/texts/shimmering";
import {
  ArrowDownIcon,
  ArrowUpIcon,
  CheckIcon,
  CopyIcon,
  PaperclipIcon,
  PlusIcon,
  SparklesIcon,
  SquareIcon,
  UploadIcon,
  XIcon,
} from "lucide-react";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { MarkdownView } from "@/components/common/markdown-view";
import { AssetMediaPreview } from "@/components/common/media-preview";
import { useConversations } from "@/components/providers/conversations-provider";
import { AgentProcessTimeline } from "@/components/studio/agent-process-timeline";
import { useStudio } from "@/components/studio/studio-context";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Progress } from "@/components/ui/progress";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { agentRunsApi, conversationsApi } from "@/lib/api/endpoints";
import { normalizeUnknownError, type ApiError } from "@/lib/api/errors";
import {
  copyToClipboard,
  notifyApiError,
  notifyError,
  notifyInfo,
  notifySuccess,
} from "@/lib/api/feedback";
import type { AgentStreamEvent, UUID } from "@/lib/api/types";
import { draftHandoff } from "@/lib/storage/draft-handoff";
import { uploadConversationAsset } from "@/lib/studio/asset-cache";
import {
  applyStreamEvent,
  buildLivePresentation,
  isLiveUserMessagePersisted,
  maxMessageSeq,
  type LiveStreamState,
} from "@/lib/studio/live-stream";
import { cn } from "@/lib/utils";

const MAX_CHAT_IMAGES = 8;

const STARTER_PROMPTS = [
  "我想做一个 15 秒的产品宣传短片，请先帮我梳理创作 Brief 和分场脚本。",
  "请为一段深夜咖啡馆偶遇的 20 秒故事生成 Markdown 分场剧本，并拆分为 4 个可执行分镜。",
  "根据我刚上传的参考图设定主角视觉风格，给出完整分镜草案。",
];

function MessageAttachments({ assetIds }: { assetIds: UUID[] }) {
  if (assetIds.length === 0) {
    return null;
  }
  return (
    <div className="mb-2 flex flex-wrap gap-2">
      {assetIds.map((assetId) => (
        <AssetMediaPreview
          key={assetId}
          assetId={assetId}
          kind="IMAGE"
          alt="对话参考图"
          className="size-20 rounded-xl"
        />
      ))}
    </div>
  );
}

function UserMessageBubble({
  content,
  assetIds,
}: {
  content: string;
  assetIds: UUID[];
}) {
  return (
    <div className="ml-auto flex max-w-[82%] flex-col items-end gap-2">
      <MessageAttachments assetIds={assetIds} />
      <div className="rounded-3xl bg-primary px-4 py-2.5 text-sm leading-relaxed text-primary-foreground">
        <div className="whitespace-pre-wrap">{content}</div>
      </div>
    </div>
  );
}

export function ChatPane() {
  const { refresh: refreshSidebar } = useConversations();
  const {
    conversationId,
    userId,
    messages,
    activeRun,
    localAssets,
    initialLoading,
    canvasOpen,
    openCanvas,
    subscribeComposerPatch,
    refreshSilent,
    registerLocalAsset,
    setTrackedActiveRun,
  } = useStudio();

  const [prompt, setPrompt] = useState("");
  const [attachedAssetIds, setAttachedAssetIds] = useState<UUID[]>([]);
  const [liveStream, setLiveStream] = useState<LiveStreamState | null>(null);
  const [submitError, setSubmitError] = useState<ApiError | null>(null);
  const [stoppingRun, setStoppingRun] = useState(false);
  const [copiedMessageId, setCopiedMessageId] = useState<UUID | null>(null);
  const [showScrollBottom, setShowScrollBottom] = useState(false);

  const [uploading, setUploading] = useState(false);
  const [uploadPercent, setUploadPercent] = useState(0);
  const [isDragging, setIsDragging] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pickerDraftIds, setPickerDraftIds] = useState<UUID[]>([]);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const scrollContainerRef = useRef<HTMLDivElement>(null);
  const bottomEndRef = useRef<HTMLDivElement>(null);
  const nearBottomRef = useRef(true);
  const abortControllerRef = useRef<AbortController | null>(null);
  const handoffCheckedRef = useRef(false);
  const submittingRef = useRef(false);
  const latestMaxSeqRef = useRef(0);
  const initialLoadingRef = useRef(initialLoading);

  useEffect(() => {
    latestMaxSeqRef.current = maxMessageSeq(messages);
    initialLoadingRef.current = initialLoading;
  }, [initialLoading, messages]);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = "smooth") => {
    nearBottomRef.current = true;
    bottomEndRef.current?.scrollIntoView({ behavior, block: "end" });
  }, []);

  const handleScroll = useCallback(() => {
    const el = scrollContainerRef.current;
    if (!el) {
      return;
    }
    const distance = el.scrollHeight - el.scrollTop - el.clientHeight;
    nearBottomRef.current = distance < 80;
    setShowScrollBottom(distance > 160);
  }, []);

  useEffect(() => {
    if (nearBottomRef.current) {
      scrollToBottom("auto");
    }
  }, [messages.length, scrollToBottom]);

  useEffect(() => {
    if (liveStream && nearBottomRef.current) {
      scrollToBottom("auto");
    }
  }, [liveStream, scrollToBottom]);

  useEffect(() => {
    return subscribeComposerPatch((patch) => {
      if (patch.promptAppend) {
        setPrompt((prev) => {
          const trimmed = prev.trimEnd();
          return trimmed ? `${trimmed}\n\n${patch.promptAppend}` : patch.promptAppend!;
        });
      }
      if (patch.assetIds && patch.assetIds.length > 0) {
        setAttachedAssetIds((prev) => {
          const merged = Array.from(new Set([...prev, ...patch.assetIds!]));
          return merged.slice(0, MAX_CHAT_IMAGES);
        });
      }
      textareaRef.current?.focus();
    });
  }, [subscribeComposerPatch]);

  useEffect(() => {
    return () => {
      abortControllerRef.current?.abort();
    };
  }, []);

  const submitTurn = useCallback(
    async (text: string, assetIds: UUID[]) => {
      const trimmed = text.trim();
      if (!trimmed || submittingRef.current) {
        return;
      }

      submittingRef.current = true;
      nearBottomRef.current = true;
      setSubmitError(null);

      const controller = new AbortController();
      abortControllerRef.current = controller;

      const baselineSeq = initialLoadingRef.current
        ? null
        : latestMaxSeqRef.current;

      setLiveStream({
        prompt: trimmed,
        assetIds,
        baselineSeq,
        runId: null,
        turns: [],
        activeTurnNo: 0,
      });

      const payload = {
        content: trimmed,
        assetIds: assetIds.length > 0 ? assetIds : undefined,
      };

      try {
        await conversationsApi.sendStream(
          conversationId,
          payload,
          {
            onEvent: (event: AgentStreamEvent) => {
              if (event.type === "accepted") {
                setTrackedActiveRun(event.runId);
              }
              if (event.type === "done") {
                setTrackedActiveRun(null);
                return;
              }
              if (event.type === "error") {
                setTrackedActiveRun(null);
                setSubmitError(
                  normalizeUnknownError(new Error(event.message), event.message)
                );
                return;
              }
              setLiveStream((prev) =>
                prev ? applyStreamEvent(prev, event) : prev
              );
            },
          },
          controller.signal
        );

        await refreshSilent();
        refreshSidebar();
      } catch (err) {
        if (!controller.signal.aborted) {
          setSubmitError(normalizeUnknownError(err, "对话请求未完成"));
          await refreshSilent();
        }
      } finally {
        submittingRef.current = false;
        abortControllerRef.current = null;
        setLiveStream(null);
      }
    },
    [conversationId, refreshSidebar, refreshSilent, setTrackedActiveRun]
  );

  useEffect(() => {
    if (!userId || handoffCheckedRef.current) {
      return;
    }
    handoffCheckedRef.current = true;
    const pending = draftHandoff.consume(userId, conversationId);
    if (!pending) {
      return;
    }
    Promise.resolve().then(() => {
      void submitTurn(pending.prompt, pending.assetIds);
    });
  }, [conversationId, submitTurn, userId]);

  const handleSend = () => {
    const trimmed = prompt.trim();
    if (!trimmed || liveStream || uploading || activeRun) {
      if (activeRun && trimmed) {
        notifyInfo("当前仍有对话轮次在后台执行，完成后即可继续发送");
      }
      return;
    }
    const assetsToSend = [...attachedAssetIds];
    setPrompt("");
    setAttachedAssetIds([]);
    void submitTurn(trimmed, assetsToSend);
  };

  const handleStopStream = async () => {
    if (stoppingRun) {
      return;
    }
    const targetRunId = liveStream?.runId ?? activeRun?.id ?? null;
    abortControllerRef.current?.abort();
    setLiveStream(null);
    submittingRef.current = false;

    if (!targetRunId) {
      notifyInfo("已停止本地流式接收");
      void refreshSilent();
      return;
    }

    setStoppingRun(true);
    try {
      await agentRunsApi.cancel(targetRunId);
      setTrackedActiveRun(null);
      notifySuccess("已停止当前 AI 轮次");
      await refreshSilent();
      refreshSidebar();
    } catch (err) {
      const apiErr = normalizeUnknownError(err, "停止对话轮次失败");
      if (apiErr.status === 409) {
        setTrackedActiveRun(null);
        notifyInfo("当前轮次刚已完成，正在刷新结果");
        await refreshSilent();
        refreshSidebar();
      } else {
        notifyApiError(err, "停止对话轮次失败");
      }
    } finally {
      setStoppingRun(false);
    }
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (
      event.key === "Enter" &&
      !event.shiftKey &&
      !event.nativeEvent.isComposing
    ) {
      event.preventDefault();
      handleSend();
    }
  };

  const uploadImageFiles = async (picked: File[]) => {
    const imageFiles = picked.filter((file) => file.type.startsWith("image/"));
    if (imageFiles.length === 0) {
      if (picked.length > 0) {
        notifyError("当前输入框仅支持附加图片文件");
      }
      return;
    }
    if (!userId) {
      return;
    }

    const remainingSlots = MAX_CHAT_IMAGES - attachedAssetIds.length;
    if (remainingSlots <= 0) {
      notifyError(`每条消息最多附加 ${MAX_CHAT_IMAGES} 张图片`);
      return;
    }

    const candidates = imageFiles.slice(0, remainingSlots);
    setUploading(true);
    setUploadPercent(0);

    try {
      const addedIds: UUID[] = [];
      for (let i = 0; i < candidates.length; i += 1) {
        const asset = await uploadConversationAsset({
          conversationId,
          file: candidates[i],
          kind: "IMAGE",
          onProgress: (pct) => {
            const overall = Math.round(
              ((i + pct / 100) / candidates.length) * 100
            );
            setUploadPercent(overall);
          },
        });
        registerLocalAsset(asset);
        addedIds.push(asset.id);
      }
      setAttachedAssetIds((prev) =>
        Array.from(new Set([...prev, ...addedIds])).slice(0, MAX_CHAT_IMAGES)
      );
    } catch (err) {
      notifyError(
        err instanceof Error ? err.message : "图片上传失败",
        "请检查网络连接后重试"
      );
    } finally {
      setUploading(false);
      setUploadPercent(0);
    }
  };

  const handleUploadFiles = async (event: ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (!picked.length) {
      return;
    }
    await uploadImageFiles(picked);
  };

  const handleDragOver = (event: DragEvent<HTMLDivElement>) => {
    if (event.dataTransfer.types.includes("Files")) {
      event.preventDefault();
      setIsDragging(true);
    }
  };

  const handleDragLeave = (event: DragEvent<HTMLDivElement>) => {
    if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
      setIsDragging(false);
    }
  };

  const handleDrop = (event: DragEvent<HTMLDivElement>) => {
    event.preventDefault();
    setIsDragging(false);
    const dropped = Array.from(event.dataTransfer.files ?? []);
    if (dropped.length > 0) {
      void uploadImageFiles(dropped);
    }
  };

  const openAssetPicker = () => {
    setPickerDraftIds(attachedAssetIds);
    setPickerOpen(true);
  };

  const togglePickerAsset = (assetId: UUID) => {
    setPickerDraftIds((prev) => {
      if (prev.includes(assetId)) {
        return prev.filter((id) => id !== assetId);
      }
      if (prev.length >= MAX_CHAT_IMAGES) {
        notifyInfo(`最多选择 ${MAX_CHAT_IMAGES} 张参考图`);
        return prev;
      }
      return [...prev, assetId];
    });
  };

  const applyPickerSelection = () => {
    setAttachedAssetIds(pickerDraftIds.slice(0, MAX_CHAT_IMAGES));
    setPickerOpen(false);
  };

  const handleCopyMessage = async (msgId: UUID, content: string) => {
    const ok = await copyToClipboard(content);
    if (ok) {
      setCopiedMessageId(msgId);
      window.setTimeout(() => {
        setCopiedMessageId((curr) => (curr === msgId ? null : curr));
      }, 1800);
    }
  };

  const imageAssets = useMemo(
    () =>
      localAssets.filter(
        (asset) => asset.kind === "IMAGE" || asset.kind === "OTHER"
      ),
    [localAssets]
  );

  const livePresentation = useMemo(
    () => (liveStream ? buildLivePresentation(liveStream) : null),
    [liveStream]
  );

  const isLiveUserPersisted = useMemo(
    () => isLiveUserMessagePersisted(messages, liveStream, initialLoading),
    [initialLoading, liveStream, messages]
  );

  const columnWidthClass = canvasOpen
    ? "mx-auto w-full max-w-2xl"
    : "mx-auto w-full max-w-3xl";

  return (
    <div className="relative flex h-full min-h-0 w-full flex-1 flex-col overflow-hidden bg-background">
      <div
        ref={scrollContainerRef}
        onScroll={handleScroll}
        className="min-h-0 flex-1 overflow-y-auto px-4 pt-6 pb-40"
      >
        <div className={cn("flex min-h-full flex-col gap-6", columnWidthClass)}>
          {initialLoading ? (
            <div className="space-y-4">
              <Skeleton className="ml-auto h-11 w-1/2 rounded-3xl" />
              <Skeleton className="h-24 w-4/5 rounded-2xl" />
              <Skeleton className="ml-auto h-11 w-2/5 rounded-3xl" />
            </div>
          ) : null}

          {!initialLoading &&
          messages.length === 0 &&
          !liveStream &&
          !activeRun ? (
            <div className="my-auto flex flex-col items-center justify-center gap-6 py-12 text-center">
              <div className="flex size-11 items-center justify-center rounded-2xl bg-muted/60">
                <SparklesIcon className="size-5 text-foreground/80" />
              </div>
              <div className="max-w-md space-y-1.5">
                <h2 className="text-lg font-medium tracking-tight">
                  开始创作对话
                </h2>
                <p className="text-sm text-muted-foreground">
                  描述你的创意想法或附带参考图，一起完成剧本、分镜与短片生成。
                </p>
              </div>
              <div className="grid w-full gap-2 text-left sm:grid-cols-3">
                {STARTER_PROMPTS.map((starter) => (
                  <button
                    key={starter}
                    type="button"
                    onClick={() => {
                      setPrompt(starter);
                      textareaRef.current?.focus();
                    }}
                    className="rounded-2xl border border-border/70 bg-background p-3.5 text-xs leading-relaxed text-muted-foreground transition-colors hover:bg-muted/50 hover:text-foreground"
                  >
                    {starter}
                  </button>
                ))}
              </div>
            </div>
          ) : null}

          {messages.map((message) => {
            const attached = message.assetIds ?? [];

            if (message.role === "SYSTEM") {
              return (
                <div
                  key={message.id}
                  className="mx-auto rounded-full bg-muted/60 px-3 py-1 text-xs text-muted-foreground"
                >
                  {message.content}
                </div>
              );
            }

            if (message.role === "USER") {
              return (
                <UserMessageBubble
                  key={message.id}
                  content={message.content}
                  assetIds={attached}
                />
              );
            }

            return (
              <div key={message.id} className="group flex flex-col gap-1">
                {message.process ? (
                  <AgentProcessTimeline
                    process={message.process}
                    onOpenFile={(id) => openCanvas("files", id)}
                    onOpenStoryboard={(id) => openCanvas("storyboard", id)}
                    onOpenTasks={() => openCanvas("tasks")}
                  />
                ) : null}

                <MarkdownView content={message.content} />

                <div className="mt-1 flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100 focus-within:opacity-100">
                  <Tooltip>
                    <TooltipTrigger
                      render={
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon-xs"
                          onClick={() =>
                            void handleCopyMessage(message.id, message.content)
                          }
                          aria-label="复制"
                          className="text-muted-foreground"
                        />
                      }
                    >
                      {copiedMessageId === message.id ? (
                        <CheckIcon />
                      ) : (
                        <CopyIcon />
                      )}
                    </TooltipTrigger>
                    <TooltipContent>复制</TooltipContent>
                  </Tooltip>
                  {message.status === "FAILED" ? (
                    <span className="ml-1 text-xs text-destructive">
                      回复未能完整生成
                    </span>
                  ) : null}
                </div>
              </div>
            );
          })}

          {liveStream ? (
            <>
              {!isLiveUserPersisted ? (
                <UserMessageBubble
                  content={liveStream.prompt}
                  assetIds={liveStream.assetIds}
                />
              ) : null}

              <div className="flex flex-col gap-1">
                {livePresentation?.process ? (
                  <AgentProcessTimeline
                    process={livePresentation.process}
                    isStreaming
                    defaultOpen
                    hasAnswerText={Boolean(livePresentation.answerMarkdown)}
                    onOpenFile={(id) => openCanvas("files", id)}
                    onOpenStoryboard={(id) => openCanvas("storyboard", id)}
                    onOpenTasks={() => openCanvas("tasks")}
                  />
                ) : null}

                {livePresentation?.answerMarkdown ? (
                  <MarkdownView content={livePresentation.answerMarkdown} />
                ) : !livePresentation?.process ? (
                  <div className="flex items-center gap-2 py-2 text-xs text-muted-foreground">
                    <span className="inline-block size-2 animate-pulse motion-reduce:animate-none rounded-full bg-foreground/60" />
                    <ShimmeringText
                      text="正在思考..."
                      color="var(--muted-foreground)"
                      shimmeringColor="var(--foreground)"
                      duration={1.4}
                    />
                  </div>
                ) : null}
              </div>
            </>
          ) : null}

          {!liveStream && activeRun ? (
            <div className="flex flex-col gap-1">
              {activeRun.process ? (
                <AgentProcessTimeline
                  process={activeRun.process}
                  isStreaming
                  defaultOpen
                  onOpenFile={(id) => openCanvas("files", id)}
                  onOpenStoryboard={(id) => openCanvas("storyboard", id)}
                  onOpenTasks={() => openCanvas("tasks")}
                />
              ) : (
                <div className="flex items-center gap-2 py-2 text-xs text-muted-foreground">
                  <span className="inline-block size-2 animate-pulse motion-reduce:animate-none rounded-full bg-foreground/60" />
                  <ShimmeringText
                    text="正在处理..."
                    color="var(--muted-foreground)"
                    shimmeringColor="var(--foreground)"
                    duration={1.4}
                  />
                </div>
              )}
            </div>
          ) : null}

          {submitError ? (
            <ApiErrorAlert error={submitError} title="对话未完成" />
          ) : null}

          <div ref={bottomEndRef} className="h-4" />
        </div>
      </div>

      {/* Floating bottom capsule composer & scroll-to-bottom button */}
      <div className="pointer-events-none absolute inset-x-0 bottom-0 z-10 flex flex-col items-center bg-gradient-to-t from-background via-background/90 to-transparent px-4 pt-8 pb-4">
        <AnimatePresence>
          {showScrollBottom ? (
            <motion.div
              key="scroll-to-bottom"
              initial={{ opacity: 0, y: 6, scale: 0.94 }}
              animate={{ opacity: 1, y: 0, scale: 1 }}
              exit={{ opacity: 0, y: 6, scale: 0.94 }}
              transition={{ duration: 0.16, ease: "easeOut" }}
              className="pointer-events-auto mb-2.5"
            >
              <Button
                type="button"
                variant="outline"
                size="icon"
                onClick={() => scrollToBottom("smooth")}
                aria-label="回到底部"
                className="size-8 rounded-full border-border/80 bg-background/95 shadow-sm backdrop-blur-sm"
              >
                <ArrowDownIcon className="size-4" />
              </Button>
            </motion.div>
          ) : null}
        </AnimatePresence>

        <div
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
          className={cn(
            "pointer-events-auto rounded-3xl border border-border/80 bg-card/95 p-2.5 shadow-lg backdrop-blur-md transition-colors motion-reduce:transition-none",
            isDragging && "border-primary bg-accent/40",
            columnWidthClass
          )}
        >
          {uploading ? (
            <div className="mb-2 space-y-1 px-2 pt-1">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>正在上传参考图...</span>
                <span className="tabular-nums">{uploadPercent}%</span>
              </div>
              <Progress value={uploadPercent} />
            </div>
          ) : null}

          {attachedAssetIds.length > 0 ? (
            <div className="mb-2 flex flex-wrap gap-2 px-1.5 pt-1">
              {attachedAssetIds.map((assetId) => (
                <div key={assetId} className="group relative">
                  <AssetMediaPreview
                    assetId={assetId}
                    kind="IMAGE"
                    alt="已附加图片"
                    allowLightbox={false}
                    className="size-14 rounded-xl"
                  />
                  <Button
                    type="button"
                    size="icon-xs"
                    variant="secondary"
                    onClick={() =>
                      setAttachedAssetIds((prev) =>
                        prev.filter((id) => id !== assetId)
                      )
                    }
                    aria-label="移除参考图"
                    className="absolute -top-1.5 -right-1.5 size-5 rounded-full shadow-xs"
                  >
                    <XIcon className="size-3" />
                  </Button>
                </div>
              ))}
            </div>
          ) : null}

          <div className="flex items-end gap-2">
            <DropdownMenu>
              <DropdownMenuTrigger
                render={
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon"
                    disabled={
                      uploading || attachedAssetIds.length >= MAX_CHAT_IMAGES
                    }
                    aria-label="添加参考图"
                    className="mb-0.5 shrink-0 rounded-full text-muted-foreground hover:text-foreground"
                  />
                }
              >
                <PlusIcon className="size-4" />
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                <DropdownMenuItem
                  onClick={() => fileInputRef.current?.click()}
                >
                  <UploadIcon />
                  <span>上传本地图片</span>
                </DropdownMenuItem>
                <DropdownMenuItem onClick={openAssetPicker}>
                  <PaperclipIcon />
                  <span>从当前会话素材选择</span>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>

            <input
              ref={fileInputRef}
              type="file"
              accept="image/*"
              multiple
              onChange={(event) => void handleUploadFiles(event)}
              className="hidden"
            />

            <Textarea
              ref={textareaRef}
              value={prompt}
              onChange={(event) => setPrompt(event.target.value)}
              onKeyDown={handleKeyDown}
              placeholder={
                activeRun
                  ? "正在处理上一条消息..."
                  : isDragging
                    ? "松开即可附加参考图..."
                    : "输入创作想法..."
              }
              rows={1}
              className="max-h-40 min-h-9 flex-1 resize-none border-0 bg-transparent px-1 py-2 text-sm shadow-none focus-visible:ring-0"
            />

            {liveStream || activeRun ? (
              <Button
                type="button"
                variant="secondary"
                size="icon"
                disabled={stoppingRun}
                onClick={() => void handleStopStream()}
                aria-label="停止生成"
                className="mb-0.5 shrink-0 rounded-full"
              >
                <SquareIcon className="size-3.5" />
              </Button>
            ) : (
              <Button
                type="button"
                size="icon"
                disabled={!prompt.trim() || uploading}
                onClick={handleSend}
                aria-label="发送消息"
                className="mb-0.5 shrink-0 rounded-full"
              >
                <ArrowUpIcon className="size-4" />
              </Button>
            )}
          </div>
        </div>
      </div>

      <Dialog open={pickerOpen} onOpenChange={setPickerOpen}>
        <DialogContent className="sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>从当前会话素材选取参考图</DialogTitle>
            <DialogDescription>
              仅显示当前会话内已上传或已生成的图片资产（最多 {MAX_CHAT_IMAGES} 张）。
            </DialogDescription>
          </DialogHeader>

          {imageAssets.length === 0 ? (
            <div className="rounded-xl border border-dashed p-6 text-center text-xs text-muted-foreground">
              当前会话暂无可复用的图片资产，请先上传或前往右侧「素材与生成」导入。
            </div>
          ) : (
            <div className="grid max-h-80 grid-cols-3 gap-3 overflow-y-auto py-1">
              {imageAssets.map((item) => {
                const selected = pickerDraftIds.includes(item.assetId);
                return (
                  <div
                    key={item.assetId}
                    className={cn(
                      "flex flex-col gap-1.5 rounded-xl border p-2 text-left transition-colors",
                      selected ? "border-primary bg-accent" : "hover:bg-muted/40"
                    )}
                  >
                    <div
                      onClick={() => togglePickerAsset(item.assetId)}
                      className="relative cursor-pointer"
                    >
                      <AssetMediaPreview
                        assetId={item.assetId}
                        kind="IMAGE"
                        alt={item.label}
                        allowLightbox={false}
                        className="aspect-video w-full"
                      />
                    </div>
                    <label className="flex cursor-pointer items-center gap-1.5 text-[11px] font-medium">
                      <Checkbox
                        checked={selected}
                        onCheckedChange={() => togglePickerAsset(item.assetId)}
                      />
                      <span className="truncate">{item.label}</span>
                    </label>
                  </div>
                );
              })}
            </div>
          )}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setPickerOpen(false)}
            >
              取消
            </Button>
            <Button type="button" onClick={applyPickerSelection}>
              确认附加 ({pickerDraftIds.length})
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
