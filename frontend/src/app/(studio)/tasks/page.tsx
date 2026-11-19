"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import {
  AlertTriangleIcon,
  ExternalLinkIcon,
  FilmIcon,
  RefreshCwIcon,
  RotateCcwIcon,
  SparklesIcon,
  StopCircleIcon,
} from "lucide-react";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { AssetMediaPreview } from "@/components/common/media-preview";
import { PageHeader } from "@/components/layout/page-header";
import { useCredit } from "@/components/providers/credit-provider";
import { isGenerationStatusActive } from "@/components/studio/studio-context";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAsyncData } from "@/hooks/use-async-data";
import { compositionApi, generationApi } from "@/lib/api/endpoints";
import { notifyApiError, notifySuccess } from "@/lib/api/feedback";
import type {
  CompositionSpec,
  CompositionStatus,
  CompositionTaskResponse,
  GenerationStatus,
  GenerationType,
  UUID,
} from "@/lib/api/types";
import {
  COMPOSITION_STATUS_LABEL,
  formatDateTime,
  GENERATION_STATUS_LABEL,
} from "@/lib/format";

type StatusBucket =
  | "ALL"
  | "ACTIVE"
  | "ARCHIVE_FAILED"
  | "UNKNOWN"
  | "SUCCEEDED"
  | "FAILED_OR_CANCELLED";

type CompositionBucket = "ALL" | "ACTIVE" | "SUCCEEDED" | "FAILED_OR_CANCELLED";

const PAGE_STEP = 50;
const MAX_TASK_LIMIT = 200;

const ACTIVE_COMPOSITION_STATUSES = new Set<CompositionStatus>([
  "PENDING",
  "VALIDATING",
  "COMPOSING",
  "UPLOADING",
  "PUBLISHING",
]);

function isCompositionActive(status: CompositionStatus): boolean {
  return ACTIVE_COMPOSITION_STATUSES.has(status);
}

function generationBadgeVariant(
  status: GenerationStatus
): "default" | "destructive" | "secondary" {
  if (status === "SUCCEEDED" || status === "PARTIAL_SUCCEEDED") {
    return "default";
  }
  if (
    status === "FAILED" ||
    status === "ARCHIVE_FAILED" ||
    status === "UNKNOWN" ||
    status === "DELIVERY_FAILED"
  ) {
    return "destructive";
  }
  return "secondary";
}

function compositionBadgeVariant(
  status: CompositionStatus
): "default" | "destructive" | "secondary" {
  if (status === "SUCCEEDED") {
    return "default";
  }
  if (status === "FAILED") {
    return "destructive";
  }
  return "secondary";
}

function parseCompositionSpec(raw: string): Partial<CompositionSpec> | null {
  try {
    return JSON.parse(raw) as Partial<CompositionSpec>;
  } catch {
    return null;
  }
}

function GlobalTasksContent() {
  const searchParams = useSearchParams();
  const urlTaskId = searchParams.get("taskId");
  const { refreshCreditSilent } = useCredit();

  const [activeTab, setActiveTab] = useState<"generation" | "composition">(
    "generation"
  );
  const [generationLimit, setGenerationLimit] = useState(PAGE_STEP);
  const [compositionLimit, setCompositionLimit] = useState(PAGE_STEP);

  const {
    data: generationData,
    loading: loadingGeneration,
    error: generationError,
    reload: reloadGeneration,
    mutate: mutateGeneration,
  } = useAsyncData(
    () => generationApi.list({ limit: generationLimit }),
    `global-generation-tasks:${generationLimit}`,
    { errorMessage: "加载媒体生成任务失败" }
  );

  const {
    data: compositionData,
    loading: loadingComposition,
    error: compositionError,
    reload: reloadComposition,
    mutate: mutateComposition,
  } = useAsyncData(
    () => compositionApi.list({ limit: compositionLimit }),
    `global-composition-tasks:${compositionLimit}`,
    { errorMessage: "加载视频合成任务失败" }
  );

  const [typeFilter, setTypeFilter] = useState<GenerationType | "ALL">("ALL");
  const [statusFilter, setStatusFilter] = useState<StatusBucket>("ALL");
  const [compositionFilter, setCompositionFilter] =
    useState<CompositionBucket>("ALL");

  const [selection, setSelection] = useState<{
    manualTaskId: UUID | null;
    dismissedUrlTaskId: string | null;
  }>({
    manualTaskId: null,
    dismissedUrlTaskId: null,
  });
  const [selectedCompositionId, setSelectedCompositionId] =
    useState<UUID | null>(null);
  const [actingTaskId, setActingTaskId] = useState<UUID | null>(null);
  const [actingCompositionId, setActingCompositionId] = useState<UUID | null>(
    null
  );

  const tasks = useMemo(() => generationData ?? [], [generationData]);
  const compositions = useMemo(
    () => compositionData ?? [],
    [compositionData]
  );

  const selectedTaskId =
    selection.manualTaskId ??
    (urlTaskId && selection.dismissedUrlTaskId !== urlTaskId ? urlTaskId : null);

  const selectTask = (taskId: UUID | null) => {
    if (taskId === null) {
      setSelection({ manualTaskId: null, dismissedUrlTaskId: urlTaskId });
    } else {
      setSelection((prev) => ({ ...prev, manualTaskId: taskId }));
    }
  };

  const hasActiveGeneration = useMemo(
    () => tasks.some((t) => isGenerationStatusActive(t.status)),
    [tasks]
  );
  const hasActiveComposition = useMemo(
    () => compositions.some((c) => isCompositionActive(c.status)),
    [compositions]
  );

  useEffect(() => {
    if (!hasActiveGeneration) {
      return;
    }
    const timer = window.setInterval(() => {
      void generationApi
        .list({ limit: generationLimit })
        .then((fresh) => {
          mutateGeneration(() => fresh);
        })
        .catch(() => {
          // Keep current state on transient poll failure.
        });
    }, 4000);
    return () => window.clearInterval(timer);
  }, [generationLimit, hasActiveGeneration, mutateGeneration]);

  useEffect(() => {
    if (!hasActiveComposition) {
      return;
    }
    const timer = window.setInterval(() => {
      void compositionApi
        .list({ limit: compositionLimit })
        .then((fresh) => {
          mutateComposition(() => fresh);
        })
        .catch(() => {
          // Keep current state on transient poll failure.
        });
    }, 4000);
    return () => window.clearInterval(timer);
  }, [compositionLimit, hasActiveComposition, mutateComposition]);

  const filteredTasks = useMemo(() => {
    return tasks.filter((task) => {
      if (typeFilter !== "ALL" && task.type !== typeFilter) {
        return false;
      }
      if (statusFilter === "ACTIVE") {
        return isGenerationStatusActive(task.status);
      }
      if (statusFilter === "ARCHIVE_FAILED") {
        return task.status === "ARCHIVE_FAILED";
      }
      if (statusFilter === "UNKNOWN") {
        return task.status === "UNKNOWN";
      }
      if (statusFilter === "SUCCEEDED") {
        return (
          task.status === "SUCCEEDED" || task.status === "PARTIAL_SUCCEEDED"
        );
      }
      if (statusFilter === "FAILED_OR_CANCELLED") {
        return (
          task.status === "FAILED" ||
          task.status === "DELIVERY_FAILED" ||
          task.status === "CANCELLED"
        );
      }
      return true;
    });
  }, [statusFilter, tasks, typeFilter]);

  const filteredCompositions = useMemo(() => {
    return compositions.filter((task) => {
      if (compositionFilter === "ACTIVE") {
        return isCompositionActive(task.status);
      }
      if (compositionFilter === "SUCCEEDED") {
        return task.status === "SUCCEEDED";
      }
      if (compositionFilter === "FAILED_OR_CANCELLED") {
        return task.status === "FAILED" || task.status === "CANCELLED";
      }
      return true;
    });
  }, [compositionFilter, compositions]);

  const listedSelectedTask = useMemo(
    () =>
      selectedTaskId
        ? tasks.find((t) => t.taskId === selectedTaskId) ?? null
        : null,
    [selectedTaskId, tasks]
  );

  const { data: fetchedSelectedTask } = useAsyncData(
    () => {
      if (!selectedTaskId) {
        return Promise.reject(new Error("No taskId"));
      }
      return generationApi.get(selectedTaskId);
    },
    selectedTaskId ? `global-task-detail:${selectedTaskId}` : "global-task-detail:none",
    {
      enabled: Boolean(selectedTaskId && generationData && !listedSelectedTask),
      errorMessage: "加载目标任务详情失败",
    }
  );

  const selectedTask = listedSelectedTask ?? fetchedSelectedTask ?? null;
  const selectedComposition: CompositionTaskResponse | null = useMemo(
    () =>
      selectedCompositionId
        ? compositions.find((c) => c.taskId === selectedCompositionId) ?? null
        : null,
    [compositions, selectedCompositionId]
  );

  const handleTaskAction = async (
    taskId: UUID,
    action: "cancel" | "retryArchive"
  ) => {
    setActingTaskId(taskId);
    try {
      const updated =
        action === "cancel"
          ? await generationApi.cancel(taskId)
          : await generationApi.retryArchive(taskId);
      mutateGeneration((prev) =>
        (prev ?? []).map((t) => (t.taskId === taskId ? updated : t))
      );
      void refreshCreditSilent();
      notifySuccess(
        action === "cancel" ? "已请求取消任务" : "已发起重试归档"
      );
    } catch (err) {
      notifyApiError(err, action === "cancel" ? "取消失败" : "重试归档失败");
    } finally {
      setActingTaskId(null);
    }
  };

  const handleCompositionAction = async (
    taskId: UUID,
    action: "cancel" | "retry"
  ) => {
    setActingCompositionId(taskId);
    try {
      const updated =
        action === "cancel"
          ? await compositionApi.cancel(taskId)
          : await compositionApi.retry(taskId);
      mutateComposition((prev) =>
        (prev ?? []).map((t) => (t.taskId === taskId ? updated : t))
      );
      notifySuccess(
        action === "cancel" ? "已请求取消合成" : "已重新排队合成"
      );
    } catch (err) {
      notifyApiError(err, action === "cancel" ? "取消失败" : "重试合成失败");
    } finally {
      setActingCompositionId(null);
    }
  };

  const isRefreshing =
    activeTab === "generation" ? loadingGeneration : loadingComposition;

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="全局任务中心"
        actions={
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            onClick={() => {
              if (activeTab === "generation") {
                reloadGeneration();
              } else {
                reloadComposition();
              }
            }}
            disabled={isRefreshing}
            aria-label="刷新任务"
          >
            <RefreshCwIcon
              className={
                isRefreshing ? "animate-spin motion-reduce:animate-none" : ""
              }
            />
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto w-full max-w-6xl space-y-4">
          <Tabs
            value={activeTab}
            onValueChange={(value) =>
              setActiveTab(value as "generation" | "composition")
            }
          >
            <TabsList>
              <TabsTrigger value="generation">
                <SparklesIcon className="size-3.5" />
                <span>媒体生成任务 ({tasks.length})</span>
              </TabsTrigger>
              <TabsTrigger value="composition">
                <FilmIcon className="size-3.5" />
                <span>视频合成任务 ({compositions.length})</span>
              </TabsTrigger>
            </TabsList>

            <TabsContent value="generation" className="mt-4 space-y-4">
              <ApiErrorAlert error={generationError} title="生成任务加载失败" />

              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-1.5">
                  {(
                    [
                      { id: "ALL", label: "全部" },
                      { id: "ACTIVE", label: "进行中" },
                      { id: "ARCHIVE_FAILED", label: "待归档修复" },
                      { id: "UNKNOWN", label: "待核账" },
                      { id: "SUCCEEDED", label: "已交付" },
                      { id: "FAILED_OR_CANCELLED", label: "已结束" },
                    ] as const
                  ).map((item) => (
                    <Button
                      key={item.id}
                      type="button"
                      size="xs"
                      variant={statusFilter === item.id ? "secondary" : "outline"}
                      onClick={() => setStatusFilter(item.id)}
                    >
                      {item.label}
                    </Button>
                  ))}
                </div>

                <Select
                  value={typeFilter}
                  items={{
                    ALL: "全部类型",
                    IMAGE: "图片",
                    VIDEO: "视频",
                  }}
                  onValueChange={(v) => {
                    if (v) {
                      setTypeFilter(v as GenerationType | "ALL");
                    }
                  }}
                >
                  <SelectTrigger size="sm" className="w-32">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ALL">全部类型</SelectItem>
                    <SelectItem value="IMAGE">图片</SelectItem>
                    <SelectItem value="VIDEO">视频</SelectItem>
                  </SelectContent>
                </Select>
              </div>

              {loadingGeneration && !generationData ? (
                <Skeleton className="h-64 w-full rounded-2xl" />
              ) : filteredTasks.length === 0 ? (
                <div className="rounded-2xl border border-dashed p-10 text-center text-sm text-muted-foreground">
                  暂无符合条件的生成任务。
                </div>
              ) : (
                <div className="space-y-3">
                  <div className="rounded-2xl border bg-card">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>类型</TableHead>
                          <TableHead>描述摘要</TableHead>
                          <TableHead>状态</TableHead>
                          <TableHead className="text-right">消耗</TableHead>
                          <TableHead>创建时间</TableHead>
                          <TableHead className="text-right">操作</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {filteredTasks.map((task) => (
                          <TableRow
                            key={task.taskId}
                            onClick={() => selectTask(task.taskId)}
                            className="cursor-pointer"
                          >
                            <TableCell>
                              <div className="flex items-center gap-1.5">
                                <Badge variant="outline">
                                  {task.type === "VIDEO" ? "视频" : "图片"}
                                </Badge>
                                {task.shotId ? (
                                  <Badge
                                    variant="secondary"
                                    className="text-[11px]"
                                  >
                                    {task.shotId}
                                  </Badge>
                                ) : null}
                              </div>
                            </TableCell>
                            <TableCell className="max-w-xs truncate">
                              {task.prompt}
                            </TableCell>
                            <TableCell>
                              <Badge variant={generationBadgeVariant(task.status)}>
                                {GENERATION_STATUS_LABEL[task.status]}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-right tabular-nums">
                              {task.priceUnits}
                            </TableCell>
                            <TableCell className="text-xs text-muted-foreground tabular-nums">
                              {formatDateTime(task.createdAt)}
                            </TableCell>
                            <TableCell
                              className="text-right"
                              onClick={(e) => e.stopPropagation()}
                            >
                              <div className="inline-flex items-center gap-1">
                                <Button
                                  type="button"
                                  variant="ghost"
                                  size="xs"
                                  onClick={() => selectTask(task.taskId)}
                                >
                                  详情
                                </Button>
                                <Button
                                  variant="link"
                                  size="xs"
                                  nativeButton={false}
                                  render={
                                    <Link
                                      href={`/conversations/${task.conversationId}?panel=tasks`}
                                    />
                                  }
                                >
                                  打开会话
                                </Button>
                              </div>
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>

                  {tasks.length >= generationLimit &&
                  generationLimit < MAX_TASK_LIMIT ? (
                    <div className="flex justify-center">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={loadingGeneration}
                        onClick={() =>
                          setGenerationLimit((prev) =>
                            Math.min(MAX_TASK_LIMIT, prev + PAGE_STEP)
                          )
                        }
                      >
                        {loadingGeneration
                          ? "加载中..."
                          : `加载更多（当前已加载 ${tasks.length} 条）`}
                      </Button>
                    </div>
                  ) : null}
                </div>
              )}
            </TabsContent>

            <TabsContent value="composition" className="mt-4 space-y-4">
              <ApiErrorAlert error={compositionError} title="合成任务加载失败" />

              <div className="flex flex-wrap items-center gap-1.5">
                {(
                  [
                    { id: "ALL", label: "全部" },
                    { id: "ACTIVE", label: "合成中" },
                    { id: "SUCCEEDED", label: "成片就绪" },
                    { id: "FAILED_OR_CANCELLED", label: "失败 / 已取消" },
                  ] as const
                ).map((item) => (
                  <Button
                    key={item.id}
                    type="button"
                    size="xs"
                    variant={
                      compositionFilter === item.id ? "secondary" : "outline"
                    }
                    onClick={() => setCompositionFilter(item.id)}
                  >
                    {item.label}
                  </Button>
                ))}
              </div>

              {loadingComposition && !compositionData ? (
                <Skeleton className="h-64 w-full rounded-2xl" />
              ) : filteredCompositions.length === 0 ? (
                <div className="rounded-2xl border border-dashed p-10 text-center text-sm text-muted-foreground">
                  暂无符合条件的视频合成任务。
                </div>
              ) : (
                <div className="space-y-3">
                  <div className="rounded-2xl border bg-card">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>分镜版本</TableHead>
                          <TableHead>输出规格</TableHead>
                          <TableHead>片段数</TableHead>
                          <TableHead>状态</TableHead>
                          <TableHead>创建时间</TableHead>
                          <TableHead className="text-right">操作</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {filteredCompositions.map((comp) => {
                          const spec = parseCompositionSpec(comp.outputSpec);
                          return (
                            <TableRow
                              key={comp.taskId}
                              onClick={() => setSelectedCompositionId(comp.taskId)}
                              className="cursor-pointer"
                            >
                              <TableCell className="font-mono text-xs tabular-nums">
                                v{comp.storyboardVersion}
                              </TableCell>
                              <TableCell className="text-xs text-muted-foreground tabular-nums">
                                {spec?.width && spec?.height
                                  ? `${spec.width}×${spec.height} · ${spec.fps ?? 24}fps`
                                  : "默认规格"}
                              </TableCell>
                              <TableCell className="tabular-nums">
                                {comp.inputs.length} 段
                              </TableCell>
                              <TableCell>
                                <Badge variant={compositionBadgeVariant(comp.status)}>
                                  {COMPOSITION_STATUS_LABEL[comp.status]}
                                </Badge>
                              </TableCell>
                              <TableCell className="text-xs text-muted-foreground tabular-nums">
                                {formatDateTime(comp.createdAt)}
                              </TableCell>
                              <TableCell
                                className="text-right"
                                onClick={(e) => e.stopPropagation()}
                              >
                                <div className="inline-flex items-center gap-1">
                                  <Button
                                    type="button"
                                    variant="ghost"
                                    size="xs"
                                    onClick={() =>
                                      setSelectedCompositionId(comp.taskId)
                                    }
                                  >
                                    详情
                                  </Button>
                                  <Button
                                    variant="link"
                                    size="xs"
                                    nativeButton={false}
                                    render={
                                      <Link
                                        href={`/conversations/${comp.conversationId}?panel=storyboard&file=${comp.storyboardFileId}`}
                                      />
                                    }
                                  >
                                    查看分镜
                                  </Button>
                                </div>
                              </TableCell>
                            </TableRow>
                          );
                        })}
                      </TableBody>
                    </Table>
                  </div>

                  {compositions.length >= compositionLimit &&
                  compositionLimit < MAX_TASK_LIMIT ? (
                    <div className="flex justify-center">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={loadingComposition}
                        onClick={() =>
                          setCompositionLimit((prev) =>
                            Math.min(MAX_TASK_LIMIT, prev + PAGE_STEP)
                          )
                        }
                      >
                        {loadingComposition
                          ? "加载中..."
                          : `加载更多（当前已加载 ${compositions.length} 条）`}
                      </Button>
                    </div>
                  ) : null}
                </div>
              )}
            </TabsContent>
          </Tabs>
        </div>
      </div>

      {/* Generation Task Detail Sheet */}
      <Sheet
        open={Boolean(selectedTask)}
        onOpenChange={(open) => {
          if (!open) {
            selectTask(null);
          }
        }}
      >
        <SheetContent className="flex w-full flex-col sm:max-w-xl">
          {selectedTask ? (
            <>
              <SheetHeader>
                <SheetTitle className="flex flex-wrap items-center gap-2">
                  <Badge variant="outline">
                    {selectedTask.type === "VIDEO" ? "视频" : "图片"}
                  </Badge>
                  <span>任务详情</span>
                </SheetTitle>
                <SheetDescription className="tabular-nums">
                  {formatDateTime(selectedTask.createdAt)} · 消耗{" "}
                  {selectedTask.priceUnits} 额度
                </SheetDescription>
              </SheetHeader>

              <div className="flex-1 space-y-4 overflow-y-auto px-4 py-2">
                <div className="flex flex-wrap gap-1.5">
                  <Badge variant={generationBadgeVariant(selectedTask.status)}>
                    {GENERATION_STATUS_LABEL[selectedTask.status]}
                  </Badge>
                </div>

                {selectedTask.status === "UNKNOWN" ? (
                  <Alert variant="destructive">
                    <AlertTriangleIcon />
                    <AlertTitle>生成结果核对中</AlertTitle>
                    <AlertDescription className="text-xs">
                      为避免重复扣费，当前暂不支持重复提交相同任务，请稍后刷新查看最终结果。
                    </AlertDescription>
                  </Alert>
                ) : null}

                {selectedTask.errorMessage ? (
                  <Alert variant="destructive">
                    <AlertTriangleIcon />
                    <AlertTitle>未成功原因</AlertTitle>
                    <AlertDescription className="text-xs">
                      {selectedTask.errorMessage}
                    </AlertDescription>
                  </Alert>
                ) : null}

                <div className="space-y-1">
                  <p className="text-xs font-medium text-muted-foreground">
                    提示词
                  </p>
                  <div className="rounded-xl border bg-muted/30 p-3 text-xs leading-relaxed whitespace-pre-wrap">
                    {selectedTask.prompt}
                  </div>
                </div>

                {selectedTask.inputs.length > 0 ? (
                  <div className="space-y-2">
                    <p className="text-xs font-medium text-muted-foreground">
                      输入资产 ({selectedTask.inputs.length})
                    </p>
                    <div className="grid grid-cols-2 gap-2">
                      {selectedTask.inputs.map((inp) => (
                        <div
                          key={`${inp.ordinal}-${inp.assetId}`}
                          className="space-y-1 rounded-xl border p-2"
                        >
                          <Badge variant="outline" className="text-[10px]">
                            {inp.role}
                          </Badge>
                          <AssetMediaPreview
                            assetId={inp.assetId}
                            kind="IMAGE"
                            className="aspect-video w-full"
                          />
                        </div>
                      ))}
                    </div>
                  </div>
                ) : null}

                {selectedTask.outputs.some((o) => o.assetId) ? (
                  <div className="space-y-2">
                    <p className="text-xs font-medium text-muted-foreground">
                      输出产物 ({selectedTask.outputs.length})
                    </p>
                    <div className="space-y-3">
                      {selectedTask.outputs.map((out) =>
                        out.assetId ? (
                          <AssetMediaPreview
                            key={out.outputIndex}
                            assetId={out.assetId}
                            kind={selectedTask.type}
                            alt={`输出 #${out.outputIndex}`}
                            className="aspect-video w-full"
                          />
                        ) : null
                      )}
                    </div>
                  </div>
                ) : null}
              </div>

              <SheetFooter className="flex-row flex-wrap gap-2 border-t pt-3">
                {isGenerationStatusActive(selectedTask.status) ? (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={actingTaskId === selectedTask.taskId}
                    onClick={() =>
                      void handleTaskAction(selectedTask.taskId, "cancel")
                    }
                  >
                    <StopCircleIcon />
                    <span>请求取消</span>
                  </Button>
                ) : null}

                {selectedTask.status === "ARCHIVE_FAILED" ? (
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    disabled={actingTaskId === selectedTask.taskId}
                    onClick={() =>
                      void handleTaskAction(selectedTask.taskId, "retryArchive")
                    }
                  >
                    <RotateCcwIcon />
                    <span>重试归档</span>
                  </Button>
                ) : null}

                <Button
                  size="sm"
                  nativeButton={false}
                  render={
                    <Link
                      href={
                        selectedTask.storyboardFileId
                          ? `/conversations/${selectedTask.conversationId}?panel=storyboard&file=${selectedTask.storyboardFileId}`
                          : `/conversations/${selectedTask.conversationId}?panel=tasks`
                      }
                    />
                  }
                >
                  <ExternalLinkIcon />
                  <span>跳转到所属会话工作区</span>
                </Button>
              </SheetFooter>
            </>
          ) : null}
        </SheetContent>
      </Sheet>

      {/* Composition Task Detail Sheet */}
      <Sheet
        open={Boolean(selectedComposition)}
        onOpenChange={(open) => {
          if (!open) {
            setSelectedCompositionId(null);
          }
        }}
      >
        <SheetContent className="flex w-full flex-col sm:max-w-xl">
          {selectedComposition ? (
            <>
              <SheetHeader>
                <SheetTitle className="flex flex-wrap items-center gap-2">
                  <Badge variant="outline">视频合成</Badge>
                  <span>任务详情</span>
                </SheetTitle>
                <SheetDescription className="tabular-nums">
                  {formatDateTime(selectedComposition.createdAt)} · 分镜 v
                  {selectedComposition.storyboardVersion}
                </SheetDescription>
              </SheetHeader>

              <div className="flex-1 space-y-4 overflow-y-auto px-4 py-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge
                    variant={compositionBadgeVariant(selectedComposition.status)}
                  >
                    {COMPOSITION_STATUS_LABEL[selectedComposition.status]}
                  </Badge>
                </div>

                {selectedComposition.errorMessage ? (
                  <Alert variant="destructive">
                    <AlertTriangleIcon />
                    <AlertTitle>合成未成功原因</AlertTitle>
                    <AlertDescription className="text-xs">
                      {selectedComposition.errorMessage}
                    </AlertDescription>
                  </Alert>
                ) : null}

                {selectedComposition.outputAssetId ? (
                  <div className="space-y-2">
                    <p className="text-xs font-medium text-muted-foreground">
                      成片视频预览
                    </p>
                    <AssetMediaPreview
                      assetId={selectedComposition.outputAssetId}
                      kind="VIDEO"
                      alt="合成成片"
                      className="aspect-video w-full"
                    />
                  </div>
                ) : null}

                <div className="space-y-2">
                  <p className="text-xs font-medium text-muted-foreground">
                    输入分镜片段 ({selectedComposition.inputs.length})
                  </p>
                  <div className="divide-y rounded-xl border text-xs">
                    {selectedComposition.inputs.map((inp) => (
                      <div
                        key={`${inp.ordinal}-${inp.generationTaskId}`}
                        className="flex items-center justify-between gap-2 px-3 py-2"
                      >
                        <div className="flex items-center gap-2">
                          <Badge variant="secondary">#{inp.ordinal}</Badge>
                          <span className="font-mono">{inp.shotId}</span>
                        </div>
                        <span className="text-muted-foreground tabular-nums">
                          {inp.startSeconds}s → {inp.endSeconds}s
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              </div>

              <SheetFooter className="flex-row flex-wrap gap-2 border-t pt-3">
                {isCompositionActive(selectedComposition.status) ? (
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={actingCompositionId === selectedComposition.taskId}
                    onClick={() =>
                      void handleCompositionAction(
                        selectedComposition.taskId,
                        "cancel"
                      )
                    }
                  >
                    <StopCircleIcon />
                    <span>取消合成</span>
                  </Button>
                ) : null}

                {(selectedComposition.status === "FAILED" ||
                  selectedComposition.status === "CANCELLED") ? (
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    disabled={actingCompositionId === selectedComposition.taskId}
                    onClick={() =>
                      void handleCompositionAction(
                        selectedComposition.taskId,
                        "retry"
                      )
                    }
                  >
                    <RotateCcwIcon />
                    <span>重试合成</span>
                  </Button>
                ) : null}

                <Button
                  size="sm"
                  nativeButton={false}
                  render={
                    <Link
                      href={`/conversations/${selectedComposition.conversationId}?panel=storyboard&file=${selectedComposition.storyboardFileId}`}
                    />
                  }
                >
                  <ExternalLinkIcon />
                  <span>跳转到分镜工作区</span>
                </Button>
              </SheetFooter>
            </>
          ) : null}
        </SheetContent>
      </Sheet>
    </div>
  );
}

export default function GlobalTasksPage() {
  return (
    <Suspense
      fallback={
        <div className="flex flex-1 flex-col gap-4 p-4 md:p-6">
          <Skeleton className="h-12 w-full rounded-xl" />
          <Skeleton className="h-64 w-full rounded-2xl" />
        </div>
      }
    >
      <GlobalTasksContent />
    </Suspense>
  );
}
