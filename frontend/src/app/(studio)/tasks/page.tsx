"use client";

import { Suspense, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import {
  AlertTriangleIcon,
  ExternalLinkIcon,
  RefreshCwIcon,
  RotateCcwIcon,
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
import { useAsyncData } from "@/hooks/use-async-data";
import { generationApi } from "@/lib/api/endpoints";
import {
  notifyApiError,
  notifySuccess,
} from "@/lib/api/feedback";
import type { GenerationType, UUID } from "@/lib/api/types";
import {
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

function GlobalTasksContent() {
  const searchParams = useSearchParams();
  const urlTaskId = searchParams.get("taskId");
  const { refreshCreditSilent } = useCredit();

  const { data, loading, error, reload, mutate } = useAsyncData(
    () => generationApi.list({ limit: 50 }),
    "global-generation-tasks",
    { errorMessage: "加载全局任务失败" }
  );

  const [typeFilter, setTypeFilter] = useState<GenerationType | "ALL">("ALL");
  const [statusFilter, setStatusFilter] = useState<StatusBucket>("ALL");
  const [selection, setSelection] = useState<{
    manualTaskId: UUID | null;
    dismissedUrlTaskId: string | null;
  }>({
    manualTaskId: null,
    dismissedUrlTaskId: null,
  });
  const [actingTaskId, setActingTaskId] = useState<UUID | null>(null);

  const tasks = useMemo(() => data ?? [], [data]);
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

  const hasActiveTasks = useMemo(
    () => tasks.some((t) => isGenerationStatusActive(t.status)),
    [tasks]
  );

  useEffect(() => {
    if (!hasActiveTasks) {
      return;
    }
    const timer = window.setInterval(() => {
      void generationApi
        .list({ limit: 50 })
        .then((fresh) => {
          mutate(() => fresh);
        })
        .catch(() => {
          // Keep current state when silent background refresh fails transiently.
        });
    }, 4000);
    return () => {
      window.clearInterval(timer);
    };
  }, [hasActiveTasks, mutate]);

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
      enabled: Boolean(selectedTaskId && data && !listedSelectedTask),
      errorMessage: "加载目标任务详情失败",
    }
  );

  const selectedTask = listedSelectedTask ?? fetchedSelectedTask ?? null;

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
      mutate((prev) =>
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

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="全局任务中心"
        actions={
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            onClick={reload}
            disabled={loading}
            aria-label="刷新任务"
          >
            <RefreshCwIcon className={loading ? "animate-spin" : ""} />
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto w-full max-w-6xl space-y-4">
          <ApiErrorAlert error={error} title="任务加载失败" />

          {/* Filter bar */}
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

          {loading && !data ? (
            <Skeleton className="h-64 w-full rounded-2xl" />
          ) : filteredTasks.length === 0 ? (
            <div className="rounded-2xl border border-dashed p-10 text-center text-sm text-muted-foreground">
              暂无符合条件的生成任务。
            </div>
          ) : (
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
                        <Badge
                          variant={
                            task.status === "SUCCEEDED"
                              ? "default"
                              : task.status === "FAILED" ||
                                  task.status === "ARCHIVE_FAILED" ||
                                  task.status === "UNKNOWN" ||
                                  task.status === "DELIVERY_FAILED"
                                ? "destructive"
                                : "secondary"
                          }
                        >
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
          )}
        </div>
      </div>

      {/* Task Detail Sheet */}
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
                  <Badge
                    variant={
                      selectedTask.status === "SUCCEEDED"
                        ? "default"
                        : selectedTask.status === "FAILED" ||
                            selectedTask.status === "ARCHIVE_FAILED" ||
                            selectedTask.status === "UNKNOWN" ||
                            selectedTask.status === "DELIVERY_FAILED"
                          ? "destructive"
                          : "secondary"
                    }
                  >
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
