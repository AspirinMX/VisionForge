"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import {
  AlertTriangleIcon,
  CheckIcon,
  CopyIcon,
  ExternalLinkIcon,
  RefreshCwIcon,
  RotateCcwIcon,
  StopCircleIcon,
} from "lucide-react";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { AssetMediaPreview } from "@/components/common/media-preview";
import { PageHeader } from "@/components/layout/page-header";
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
  copyToClipboard,
  notifyApiError,
  notifySuccess,
} from "@/lib/api/feedback";
import type { GenerationType, UUID } from "@/lib/api/types";
import {
  formatDateTime,
  GENERATION_BILLING_LABEL,
  GENERATION_CANCEL_LABEL,
  GENERATION_DELIVERY_LABEL,
  GENERATION_STATUS_LABEL,
} from "@/lib/format";

type StatusBucket =
  | "ALL"
  | "ACTIVE"
  | "ARCHIVE_FAILED"
  | "UNKNOWN"
  | "SUCCEEDED"
  | "FAILED_OR_CANCELLED";

function formatParameters(raw: string): string {
  if (!raw || !raw.trim()) {
    return "{}";
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

export default function GlobalTasksPage() {
  const { data, loading, error, reload, mutate } = useAsyncData(
    () => generationApi.list({ limit: 50 }),
    "global-generation-tasks",
    { errorMessage: "加载全局任务失败" }
  );

  const [typeFilter, setTypeFilter] = useState<GenerationType | "ALL">("ALL");
  const [statusFilter, setStatusFilter] = useState<StatusBucket>("ALL");
  const [selectedTaskId, setSelectedTaskId] = useState<UUID | null>(null);
  const [actingTaskId, setActingTaskId] = useState<UUID | null>(null);
  const [copiedId, setCopiedId] = useState<UUID | null>(null);

  const tasks = useMemo(() => data ?? [], [data]);

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

  const selectedTask = useMemo(
    () =>
      selectedTaskId
        ? tasks.find((t) => t.taskId === selectedTaskId) ?? null
        : null,
    [selectedTaskId, tasks]
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
      mutate((prev) =>
        (prev ?? []).map((t) => (t.taskId === taskId ? updated : t))
      );
      notifySuccess(
        action === "cancel" ? "已请求取消任务" : "已发起重试归档"
      );
    } catch (err) {
      notifyApiError(err, action === "cancel" ? "取消失败" : "重试归档失败");
    } finally {
      setActingTaskId(null);
    }
  };

  const handleCopy = async (id: UUID) => {
    const ok = await copyToClipboard(id);
    if (ok) {
      setCopiedId(id);
      window.setTimeout(() => {
        setCopiedId((curr) => (curr === id ? null : curr));
      }, 1600);
    }
  };

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="全局任务中心"
        subtitle="查看当前账号下的跨会话生成任务（含 4 轴状态与产物回看）"
        actions={
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={reload}
            disabled={loading}
          >
            <RefreshCwIcon className={loading ? "animate-spin" : ""} />
            <span>刷新</span>
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
                  { id: "ALL", label: `全部 (${tasks.length})` },
                  { id: "ACTIVE", label: "进行中" },
                  { id: "ARCHIVE_FAILED", label: "待归档修复" },
                  { id: "UNKNOWN", label: "待核账" },
                  { id: "SUCCEEDED", label: "已交付" },
                  { id: "FAILED_OR_CANCELLED", label: "失败 / 已取消" },
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
                <SelectItem value="IMAGE">图片 (IMAGE)</SelectItem>
                <SelectItem value="VIDEO">视频 (VIDEO)</SelectItem>
              </SelectContent>
            </Select>
          </div>

          {loading && !data ? (
            <Skeleton className="h-64 w-full rounded-2xl" />
          ) : filteredTasks.length === 0 ? (
            <div className="rounded-2xl border border-dashed p-10 text-center text-sm text-muted-foreground">
              暂无符合条件的媒体生成任务记录。
            </div>
          ) : (
            <div className="rounded-2xl border bg-card">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>类型</TableHead>
                    <TableHead>提示词摘要</TableHead>
                    <TableHead>执行状态</TableHead>
                    <TableHead>交付 / 账务</TableHead>
                    <TableHead className="text-right">额度单位</TableHead>
                    <TableHead>创建时间</TableHead>
                    <TableHead className="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filteredTasks.map((task) => (
                    <TableRow
                      key={task.taskId}
                      onClick={() => setSelectedTaskId(task.taskId)}
                      className="cursor-pointer"
                    >
                      <TableCell>
                        <div className="flex items-center gap-1.5">
                          <Badge variant="outline">{task.type}</Badge>
                          {task.shotId ? (
                            <Badge
                              variant="secondary"
                              className="font-mono text-[10px]"
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
                      <TableCell className="text-xs text-muted-foreground">
                        {GENERATION_DELIVERY_LABEL[task.deliveryStatus]} ·{" "}
                        {GENERATION_BILLING_LABEL[task.billingStatus]}
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
                            onClick={() => setSelectedTaskId(task.taskId)}
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
            setSelectedTaskId(null);
          }
        }}
      >
        <SheetContent className="flex w-full flex-col sm:max-w-xl">
          {selectedTask ? (
            <>
              <SheetHeader>
                <SheetTitle className="flex flex-wrap items-center gap-2">
                  <Badge variant="outline">{selectedTask.type}</Badge>
                  <span>任务详情</span>
                  <button
                    type="button"
                    onClick={() => void handleCopy(selectedTask.taskId)}
                    className="inline-flex items-center gap-1 rounded-md border px-2 py-0.5 font-mono text-[11px] font-normal text-muted-foreground hover:text-foreground"
                  >
                    {copiedId === selectedTask.taskId ? (
                      <CheckIcon className="size-3" />
                    ) : (
                      <CopyIcon className="size-3" />
                    )}
                    <span>{selectedTask.taskId.slice(0, 8)}</span>
                  </button>
                </SheetTitle>
                <SheetDescription className="tabular-nums">
                  创建于 {formatDateTime(selectedTask.createdAt)} · 计费{" "}
                  {selectedTask.priceUnits} 单位
                </SheetDescription>
              </SheetHeader>

              <div className="flex-1 space-y-4 overflow-y-auto px-4 py-2">
                {/* 4-axis badges */}
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
                    执行：{GENERATION_STATUS_LABEL[selectedTask.status]}
                  </Badge>
                  <Badge variant="outline">
                    交付：{GENERATION_DELIVERY_LABEL[selectedTask.deliveryStatus]}
                  </Badge>
                  <Badge variant="outline">
                    账务：{GENERATION_BILLING_LABEL[selectedTask.billingStatus]}
                  </Badge>
                  <Badge variant="outline">
                    取消：{GENERATION_CANCEL_LABEL[selectedTask.cancelStatus]}
                  </Badge>
                </div>

                {selectedTask.status === "UNKNOWN" ? (
                  <Alert variant="destructive">
                    <AlertTriangleIcon />
                    <AlertTitle>远端状态未知（PENDING_RECONCILIATION）</AlertTitle>
                    <AlertDescription className="text-xs">
                      请勿重复创建相同任务以免重复扣费；复制上方任务 ID 排查或等待系统核账结束。
                    </AlertDescription>
                  </Alert>
                ) : null}

                {selectedTask.errorMessage ? (
                  <Alert variant="destructive">
                    <AlertTriangleIcon />
                    <AlertTitle>错误信息</AlertTitle>
                    <AlertDescription className="text-xs">
                      {selectedTask.errorMessage}
                    </AlertDescription>
                  </Alert>
                ) : null}

                <div className="space-y-1">
                  <p className="text-xs font-medium text-muted-foreground">
                    提示词 (prompt)
                  </p>
                  <div className="rounded-xl border bg-muted/30 p-3 text-xs leading-relaxed whitespace-pre-wrap">
                    {selectedTask.prompt}
                  </div>
                </div>

                <div className="space-y-1">
                  <p className="text-xs font-medium text-muted-foreground">
                    请求参数 (parameters)
                  </p>
                  <pre className="overflow-x-auto rounded-xl border bg-muted/30 p-3 font-mono text-[11px]">
                    {formatParameters(selectedTask.parameters)}
                  </pre>
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
