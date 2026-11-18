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
import {
  ArrowUpIcon,
  BrainCircuitIcon,
  CheckIcon,
  ChevronDownIcon,
  CopyIcon,
  FileTextIcon,
  FilmIcon,
  ImagePlusIcon,
  LayersIcon,
  ListChecksIcon,
  PaperclipIcon,
  SparklesIcon,
  SquareIcon,
  UploadIcon,
  XIcon,
} from "lucide-react";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { MarkdownView } from "@/components/common/markdown-view";
import { AssetMediaPreview } from "@/components/common/media-preview";
import { useConversations } from "@/components/providers/conversations-provider";
import { useStudio } from "@/components/studio/studio-context";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
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
import { conversationsApi } from "@/lib/api/endpoints";
import { normalizeUnknownError, type ApiError } from "@/lib/api/errors";
import { copyToClipboard, notifyError, notifyInfo } from "@/lib/api/feedback";
import type { AgentStreamEvent, UUID } from "@/lib/api/types";
import {
  AGENT_RUN_STATUS_LABEL,
  formatShortTime,
  GENERATION_STATUS_LABEL,
} from "@/lib/format";
import { draftHandoff } from "@/lib/storage/draft-handoff";
import { uploadConversationAsset } from "@/lib/studio/asset-cache";
import { cn } from "@/lib/utils";

const MAX_CHAT_IMAGES = 8;

const STARTER_PROMPTS = [
  "我想做一个 15 秒的产品宣传短片，请先帮我梳理创作 Brief 和分场脚本。",
  "请为一段深夜咖啡馆偶遇的 20 秒故事生成 Markdown 分场剧本，并拆分为 4 个可执行分镜。",
  "根据我刚上传的参考图设定主角视觉风格，给出完整分镜 JSON 草案。",
];

interface LiveStreamState {
  prompt: string;
  assetIds: UUID[];
  runId: UUID | null;
  reasoning: string;
  reasoningSummary: string;
  delta: string;
  mode: "sse" | "poll";
}

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

export function ChatPane() {
  const { refresh: refreshSidebar } = useConversations();
  const {
    conversationId,
    userId,
    messages,
    files,
    generationTasks,
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
  const [deliveryMode, setDeliveryMode] = useState<"sse" | "poll">("sse");
  const [liveStream, setLiveStream] = useState<LiveStreamState | null>(null);
  const [reasoningOpen, setReasoningOpen] = useState(false);
  const [submitError, setSubmitError] = useState<ApiError | null>(null);
  const [copiedMessageId, setCopiedMessageId] = useState<UUID | null>(null);

  const [uploading, setUploading] = useState(false);
  const [uploadPercent, setUploadPercent] = useState(0);
  const [isDragging, setIsDragging] = useState(false);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [pickerDraftIds, setPickerDraftIds] = useState<UUID[]>([]);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const bottomEndRef = useRef<HTMLDivElement>(null);
  const abortControllerRef = useRef<AbortController | null>(null);
  const handoffCheckedRef = useRef(false);
  const submittingRef = useRef(false);

  const scrollToBottom = useCallback((behavior: ScrollBehavior = "smooth") => {
    bottomEndRef.current?.scrollIntoView({ behavior, block: "end" });
  }, []);

  useEffect(() => {
    scrollToBottom("auto");
  }, [messages.length, scrollToBottom]);

  useEffect(() => {
    if (liveStream?.delta || liveStream?.reasoningSummary) {
      scrollToBottom("auto");
    }
  }, [liveStream?.delta, liveStream?.reasoningSummary, scrollToBottom]);

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

  const pollUntilComplete = useCallback(
    async (runId: UUID, signal: AbortSignal) => {
      while (!signal.aborted) {
        const run = await conversationsApi.getAgentRun(runId);
        if (run.status === "COMPLETED") {
          setTrackedActiveRun(null);
          await refreshSilent();
          refreshSidebar();
          return;
        }
        if (run.status === "FAILED") {
          setTrackedActiveRun(null);
          await refreshSilent();
          refreshSidebar();
          throw new Error(run.errorMessage || "Agent 执行失败");
        }
        await new Promise((resolve) => window.setTimeout(resolve, 1800));
      }
    },
    [refreshSidebar, refreshSilent, setTrackedActiveRun]
  );

  const submitTurn = useCallback(
    async (text: string, assetIds: UUID[]) => {
      const trimmed = text.trim();
      if (!trimmed || submittingRef.current) {
        return;
      }

      submittingRef.current = true;
      setSubmitError(null);
      setReasoningOpen(false);

      const controller = new AbortController();
      abortControllerRef.current = controller;

      setLiveStream({
        prompt: trimmed,
        assetIds,
        runId: null,
        reasoning: "",
        reasoningSummary: "",
        delta: "",
        mode: deliveryMode,
      });

      const payload = {
        content: trimmed,
        assetIds: assetIds.length > 0 ? assetIds : undefined,
      };

      try {
        if (deliveryMode === "sse") {
          await conversationsApi.sendStream(
            conversationId,
            payload,
            {
              onEvent: (event: AgentStreamEvent) => {
                if (event.type === "accepted") {
                  setTrackedActiveRun(event.runId);
                  setLiveStream((prev) =>
                    prev ? { ...prev, runId: event.runId } : prev
                  );
                } else if (event.type === "reasoning") {
                  setLiveStream((prev) =>
                    prev
                      ? { ...prev, reasoning: prev.reasoning + event.content }
                      : prev
                  );
                } else if (event.type === "reasoning_summary") {
                  setLiveStream((prev) =>
                    prev
                      ? {
                          ...prev,
                          reasoningSummary:
                            prev.reasoningSummary + event.content,
                        }
                      : prev
                  );
                } else if (event.type === "delta") {
                  setLiveStream((prev) =>
                    prev ? { ...prev, delta: prev.delta + event.content } : prev
                  );
                } else if (event.type === "done") {
                  setTrackedActiveRun(null);
                } else if (event.type === "error") {
                  setTrackedActiveRun(null);
                  setSubmitError(
                    normalizeUnknownError(new Error(event.message), event.message)
                  );
                }
              },
            },
            controller.signal
          );

          await refreshSilent();
          refreshSidebar();
        } else {
          const turn = await conversationsApi.send(conversationId, payload);
          setTrackedActiveRun(turn.runId);
          setLiveStream((prev) =>
            prev ? { ...prev, runId: turn.runId } : prev
          );
          await pollUntilComplete(turn.runId, controller.signal);
        }
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
    [
      conversationId,
      deliveryMode,
      pollUntilComplete,
      refreshSidebar,
      refreshSilent,
      setTrackedActiveRun,
    ]
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

  const handleStopStream = () => {
    abortControllerRef.current?.abort();
    setLiveStream(null);
    submittingRef.current = false;
    notifyInfo("已中断前端流式接收；后台任务状态将自动同步");
    void refreshSilent();
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
        "请确认 RustFS 对象存储 CORS 与网络状态"
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

  const storyboardFile = useMemo(
    () => files.find((file) => file.kind === "STORYBOARD") ?? null,
    [files]
  );
  const scriptFile = useMemo(
    () => files.find((file) => file.kind === "SCRIPT") ?? null,
    [files]
  );
  const tasksById = useMemo(
    () => new Map(generationTasks.map((task) => [task.taskId, task])),
    [generationTasks]
  );

  const columnWidthClass = canvasOpen
    ? "mx-auto w-full max-w-2xl"
    : "mx-auto w-full max-w-3xl";

  return (
    <div className="flex h-full min-h-0 flex-col bg-background">
      <div className="flex-1 overflow-y-auto px-4 pt-6 pb-4">
        <div className={cn("flex flex-col gap-5", columnWidthClass)}>
          {initialLoading ? (
            <div className="space-y-4">
              <Skeleton className="ml-auto h-14 w-2/3 rounded-2xl" />
              <Skeleton className="h-28 w-4/5 rounded-2xl" />
              <Skeleton className="ml-auto h-12 w-1/2 rounded-2xl" />
            </div>
          ) : null}

          {!initialLoading &&
          messages.length === 0 &&
          !liveStream &&
          !activeRun ? (
            <div className="my-auto flex flex-col items-center justify-center gap-6 py-10 text-center">
              <div className="flex size-12 items-center justify-center rounded-2xl border bg-muted/40">
                <SparklesIcon className="size-5 text-primary" />
              </div>
              <div className="max-w-lg space-y-1.5">
                <h2 className="text-lg font-semibold tracking-tight">
                  开始这段短片创作对话
                </h2>
                <p className="text-sm text-muted-foreground">
                  你可以先告诉我创意目标或上传参考图，导演 Agent 会协助产出剧本、分镜 JSON，并直接驱动出图、视频与最终成片合成。
                </p>
              </div>
              <div className="grid w-full gap-2.5 text-left sm:grid-cols-3">
                {STARTER_PROMPTS.map((starter) => (
                  <button
                    key={starter}
                    type="button"
                    onClick={() => {
                      setPrompt(starter);
                      textareaRef.current?.focus();
                    }}
                    className="rounded-2xl border bg-card p-3.5 text-xs leading-relaxed text-card-foreground transition-colors hover:bg-accent"
                  >
                    {starter}
                  </button>
                ))}
              </div>
            </div>
          ) : null}

          {messages.map((message, idx) => {
            const isLastAssistant =
              message.role === "ASSISTANT" &&
              idx === messages.length - 1 &&
              !liveStream;
            const attached = message.assetIds ?? [];

            if (message.role === "SYSTEM") {
              return (
                <div
                  key={message.id}
                  className="mx-auto rounded-full border bg-muted/40 px-3 py-1 text-xs text-muted-foreground"
                >
                  {message.content}
                </div>
              );
            }

            if (message.role === "USER") {
              return (
                <div key={message.id} className="ml-auto flex max-w-[86%] flex-col items-end gap-1.5">
                  <MessageAttachments assetIds={attached} />
                  <div className="rounded-2xl bg-primary px-4 py-2.5 text-sm leading-relaxed text-primary-foreground">
                    <div className="whitespace-pre-wrap">{message.content}</div>
                  </div>
                  <span className="text-[11px] text-muted-foreground tabular-nums">
                    {formatShortTime(message.createdAt)}
                  </span>
                </div>
              );
            }

            return (
              <div
                key={message.id}
                className="group flex max-w-[94%] flex-col gap-2 rounded-2xl border bg-card px-4 py-3.5 text-sm text-card-foreground"
              >
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <span className="font-medium text-foreground">
                      幻影导演 Agent
                    </span>
                    <span className="tabular-nums">
                      {formatShortTime(message.createdAt)}
                    </span>
                    {message.status === "FAILED" ? (
                      <Badge variant="destructive">部分失败</Badge>
                    ) : null}
                  </div>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-xs"
                    onClick={() =>
                      void handleCopyMessage(message.id, message.content)
                    }
                    aria-label="复制回复内容"
                  >
                    {copiedMessageId === message.id ? (
                      <CheckIcon />
                    ) : (
                      <CopyIcon />
                    )}
                  </Button>
                </div>

                <MarkdownView content={message.content} />

                {(message.generationTaskIds?.length ?? 0) > 0 ? (
                  <div className="flex flex-wrap items-center gap-1.5 pt-1">
                    {message.generationTaskIds.map((taskId) => {
                      const task = tasksById.get(taskId);
                      return (
                        <Button
                          key={taskId}
                          type="button"
                          variant="secondary"
                          size="xs"
                          onClick={() => openCanvas("tasks")}
                        >
                          <ListChecksIcon />
                          <span>
                            {task
                              ? `${task.type} · ${GENERATION_STATUS_LABEL[task.status]}`
                              : `生成任务 ${taskId.slice(0, 8)}`}
                          </span>
                        </Button>
                      );
                    })}
                  </div>
                ) : null}

                {isLastAssistant &&
                (files.length > 0 || generationTasks.length > 0) ? (
                  <div className="mt-1 flex flex-wrap items-center gap-1.5 border-t pt-2.5">
                    {storyboardFile ? (
                      <Button
                        type="button"
                        variant="outline"
                        size="xs"
                        onClick={() =>
                          openCanvas("storyboard", storyboardFile.id)
                        }
                      >
                        <FilmIcon />
                        <span>打开分镜画布</span>
                      </Button>
                    ) : null}
                    {scriptFile ? (
                      <Button
                        type="button"
                        variant="outline"
                        size="xs"
                        onClick={() => openCanvas("files", scriptFile.id)}
                      >
                        <FileTextIcon />
                        <span>查看剧本</span>
                      </Button>
                    ) : null}
                    {files.length > 0 ? (
                      <Button
                        type="button"
                        variant="ghost"
                        size="xs"
                        onClick={() => openCanvas("files")}
                      >
                        <LayersIcon />
                        <span className="tabular-nums">
                          全部文件 ({files.length})
                        </span>
                      </Button>
                    ) : null}
                    {generationTasks.length > 0 ? (
                      <Button
                        type="button"
                        variant="ghost"
                        size="xs"
                        onClick={() => openCanvas("tasks")}
                      >
                        <ListChecksIcon />
                        <span className="tabular-nums">
                          任务进度 ({generationTasks.length})
                        </span>
                      </Button>
                    ) : null}
                  </div>
                ) : null}
              </div>
            );
          })}

          {liveStream ? (
            <>
              <div className="ml-auto flex max-w-[86%] flex-col items-end gap-1.5">
                <MessageAttachments assetIds={liveStream.assetIds} />
                <div className="rounded-2xl bg-primary px-4 py-2.5 text-sm leading-relaxed text-primary-foreground">
                  <div className="whitespace-pre-wrap">{liveStream.prompt}</div>
                </div>
              </div>

              <div className="flex max-w-[94%] flex-col gap-3 rounded-2xl border bg-card px-4 py-3.5 text-sm">
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2 text-xs text-muted-foreground">
                    <span className="inline-block size-2 animate-pulse rounded-full bg-primary" />
                    <span className="font-medium text-foreground">
                      {liveStream.mode === "sse"
                        ? "正在流式响应..."
                        : "后台执行中..."}
                    </span>
                    {liveStream.runId ? (
                      <Badge variant="outline" className="font-mono text-[10px]">
                        {liveStream.runId.slice(0, 8)}
                      </Badge>
                    ) : null}
                  </div>
                  <Button
                    type="button"
                    variant="outline"
                    size="xs"
                    onClick={handleStopStream}
                  >
                    <SquareIcon />
                    <span>停止接收</span>
                  </Button>
                </div>

                {liveStream.reasoningSummary || liveStream.reasoning ? (
                  <Collapsible
                    open={reasoningOpen}
                    onOpenChange={setReasoningOpen}
                    className="rounded-xl border bg-muted/40"
                  >
                    <CollapsibleTrigger
                      render={
                        <button
                          type="button"
                          className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-xs text-muted-foreground hover:text-foreground"
                        />
                      }
                    >
                      <span className="flex items-center gap-1.5 font-medium">
                        <BrainCircuitIcon className="size-3.5" />
                        <span>思考过程</span>
                      </span>
                      <ChevronDownIcon
                        className={cn(
                          "size-3.5 transition-transform",
                          reasoningOpen && "rotate-180"
                        )}
                      />
                    </CollapsibleTrigger>
                    <CollapsibleContent className="border-t px-3 py-2 text-xs leading-relaxed whitespace-pre-wrap text-muted-foreground">
                      {liveStream.reasoningSummary || liveStream.reasoning}
                    </CollapsibleContent>
                  </Collapsible>
                ) : null}

                {liveStream.delta ? (
                  <MarkdownView content={liveStream.delta} />
                ) : (
                  <div className="space-y-2 py-1">
                    <Skeleton className="h-3.5 w-3/4" />
                    <Skeleton className="h-3.5 w-1/2" />
                  </div>
                )}
              </div>
            </>
          ) : null}

          {!liveStream && activeRun ? (
            <div className="flex items-center justify-between gap-3 rounded-2xl border bg-card px-4 py-3 text-xs">
              <div className="flex items-center gap-2.5">
                <span className="inline-block size-2 animate-pulse rounded-full bg-primary" />
                <span className="font-medium">
                  后台对话运行中 · {AGENT_RUN_STATUS_LABEL[activeRun.status]}
                </span>
                <Badge variant="outline" className="font-mono text-[10px]">
                  {activeRun.id.slice(0, 8)}
                </Badge>
              </div>
              <span className="text-muted-foreground">
                完成后可发送下一条消息（避免并发冲突）
              </span>
            </div>
          ) : null}

          {submitError ? (
            <ApiErrorAlert error={submitError} title="对话执行遇到问题" />
          ) : null}

          <div ref={bottomEndRef} />
        </div>
      </div>

      {/* Floating Gemini/ChatGPT style multimodal composer */}
      <div className="shrink-0 px-4 pt-1 pb-4">
        <div
          onDragOver={handleDragOver}
          onDragLeave={handleDragLeave}
          onDrop={handleDrop}
          className={cn(
            "rounded-3xl border bg-card p-3 shadow-sm transition-colors",
            isDragging && "border-primary bg-accent/40",
            columnWidthClass
          )}
        >
          {uploading ? (
            <div className="mb-2.5 space-y-1 px-1">
              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span>正在上传参考图至 RustFS...</span>
                <span className="tabular-nums">{uploadPercent}%</span>
              </div>
              <Progress value={uploadPercent} />
            </div>
          ) : null}

          {attachedAssetIds.length > 0 ? (
            <div className="mb-2.5 flex flex-wrap gap-2 px-1">
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

          <Textarea
            ref={textareaRef}
            value={prompt}
            onChange={(event) => setPrompt(event.target.value)}
            onKeyDown={handleKeyDown}
            placeholder={
              activeRun
                ? "上一轮对话仍在后台执行（可能正在等待生成任务），请稍候..."
                : isDragging
                  ? "松开即可附加参考图片到当前输入..."
                  : "告诉幻影导演下一步想怎么修改剧本、分镜或生成镜头... (支持拖拽图片，Enter 发送)"
            }
            rows={2}
            className="max-h-44 min-h-12 resize-none border-0 bg-transparent px-1.5 py-1 text-sm shadow-none focus-visible:ring-0"
          />

          <input
            ref={fileInputRef}
            type="file"
            accept="image/*"
            multiple
            onChange={(event) => void handleUploadFiles(event)}
            className="hidden"
          />

          <div className="mt-2 flex flex-wrap items-center justify-between gap-2 px-1">
            <div className="flex flex-wrap items-center gap-1.5">
              <DropdownMenu>
                <DropdownMenuTrigger
                  render={
                    <Button
                      type="button"
                      variant="ghost"
                      size="sm"
                      disabled={
                        uploading || attachedAssetIds.length >= MAX_CHAT_IMAGES
                      }
                    />
                  }
                >
                  <ImagePlusIcon />
                  <span>
                    参考图 ({attachedAssetIds.length}/{MAX_CHAT_IMAGES})
                  </span>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="start">
                  <DropdownMenuItem
                    onClick={() => fileInputRef.current?.click()}
                  >
                    <UploadIcon />
                    <span>上传本地图片 (RustFS)</span>
                  </DropdownMenuItem>
                  <DropdownMenuItem onClick={openAssetPicker}>
                    <PaperclipIcon />
                    <span>从当前会话素材选择 ({imageAssets.length})</span>
                  </DropdownMenuItem>
                </DropdownMenuContent>
              </DropdownMenu>

              <Button
                type="button"
                variant="ghost"
                size="xs"
                onClick={() =>
                  setDeliveryMode((mode) => (mode === "sse" ? "poll" : "sse"))
                }
                className="text-xs text-muted-foreground"
              >
                <span>
                  {deliveryMode === "sse" ? "响应：流式 SSE" : "响应：异步轮询"}
                </span>
              </Button>
            </div>

            <div className="flex items-center gap-1.5">
              {files.length > 0 && !canvasOpen ? (
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => openCanvas("storyboard")}
                >
                  <FilmIcon />
                  <span>打开工作台</span>
                </Button>
              ) : null}

              {liveStream ? (
                <Button
                  type="button"
                  variant="secondary"
                  size="icon"
                  onClick={handleStopStream}
                  aria-label="停止接收"
                  className="rounded-full"
                >
                  <SquareIcon className="size-4" />
                </Button>
              ) : (
                <Button
                  type="button"
                  size="icon"
                  disabled={!prompt.trim() || uploading || Boolean(activeRun)}
                  onClick={handleSend}
                  aria-label="发送消息"
                  className="rounded-full"
                >
                  <ArrowUpIcon className="size-4" />
                </Button>
              )}
            </div>
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
