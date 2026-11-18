"use client";

import { useMemo, useState } from "react";
import {
  AlertTriangleIcon,
  FileTextIcon,
  RefreshCwIcon,
  RotateCcwIcon,
  StopCircleIcon,
} from "lucide-react";

import { AssetMediaPreview } from "@/components/common/media-preview";
import {
  isGenerationStatusActive,
  useStudio,
} from "@/components/studio/studio-context";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { compositionApi, generationApi } from "@/lib/api/endpoints";
import {
  notifyApiError,
  notifySuccess,
} from "@/lib/api/feedback";
import type { GenerationTaskResponse, UUID } from "@/lib/api/types";
import {
  COMPOSITION_STATUS_LABEL,
  formatDateTime,
  GENERATION_STATUS_LABEL,
} from "@/lib/format";

type TaskFilter =
  | "ALL"
  | "ACTIVE"
  | "ARCHIVE_FAILED"
  | "UNKNOWN"
  | "SUCCEEDED"
  | "FAILED_OR_CANCELLED";

function parseParamsJson(raw: string): Record<string, unknown> | undefined {
  if (!raw || !raw.trim()) {
    return undefined;
  }
  try {
    const parsed = JSON.parse(raw) as unknown;
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
    return undefined;
  } catch {
    return undefined;
  }
}

export function TasksPanel() {
  const {
    generationTasks,
    compositionTasks,
    refreshing,
    refreshAll,
    refreshSilent,
    openCanvas,
    openQuickGenerate,
  } = useStudio();

  const [filter, setFilter] = useState<TaskFilter>("ALL");
  const [actingTaskId, setActingTaskId] = useState<UUID | null>(null);

  const filteredTasks = useMemo(() => {
    return generationTasks.filter((task) => {
      if (filter === "ACTIVE") {
        return isGenerationStatusActive(task.status);
      }
      if (filter === "ARCHIVE_FAILED") {
        return task.status === "ARCHIVE_FAILED";
      }
      if (filter === "UNKNOWN") {
        return task.status === "UNKNOWN";
      }
      if (filter === "SUCCEEDED") {
        return (
          task.status === "SUCCEEDED" || task.status === "PARTIAL_SUCCEEDED"
        );
      }
      if (filter === "FAILED_OR_CANCELLED") {
        return (
          task.status === "FAILED" ||
          task.status === "DELIVERY_FAILED" ||
          task.status === "CANCELLED"
        );
      }
      return true;
    });
  }, [filter, generationTasks]);

  const handleGenerationAction = async (
    taskId: UUID,
    action: "cancel" | "retryArchive"
  ) => {
    setActingTaskId(taskId);
    try {
      if (action === "cancel") {
        await generationApi.cancel(taskId);
        notifySuccess("已请求取消生成任务");
      } else {
        await generationApi.retryArchive(taskId);
        notifySuccess("已发起重试归档");
      }
      await refreshSilent();
    } catch (err) {
      notifyApiError(
        err,
        action === "cancel" ? "取消生成任务失败" : "重试归档失败"
      );
    } finally {
      setActingTaskId(null);
    }
  };

  const handleCompositionAction = async (
    taskId: UUID,
    action: "cancel" | "retry"
  ) => {
    setActingTaskId(taskId);
    try {
      if (action === "cancel") {
        await compositionApi.cancel(taskId);
        notifySuccess("已请求取消成片合成");
      } else {
        await compositionApi.retry(taskId);
        notifySuccess("已重试成片合成");
      }
      await refreshSilent();
    } catch (err) {
      notifyApiError(err, action === "cancel" ? "取消失败" : "重试失败");
    } finally {
      setActingTaskId(null);
    }
  };

  const handleRegenerateWithSameParams = (task: GenerationTaskResponse) => {
    openQuickGenerate({
      type: task.type,
      prompt: task.prompt,
      parameters: parseParamsJson(task.parameters),
      inputs: task.inputs.map((inp) => ({
        assetId: inp.assetId,
        fileVersionId: inp.fileVersionId,
        role: inp.role,
      })),
      sourceTaskId: task.taskId,
      shotId: task.shotId,
      storyboardFileId: task.storyboardFileId,
      storyboardVersion: task.storyboardVersion,
    });
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* Filter Header */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-2.5">
        <div className="flex flex-wrap items-center gap-1">
          {(
            [
              { id: "ALL", label: `全部 (${generationTasks.length})` },
              { id: "ACTIVE", label: "进行中" },
              { id: "ARCHIVE_FAILED", label: "待归档修复" },
              { id: "UNKNOWN", label: "待核账" },
              { id: "SUCCEEDED", label: "已交付" },
              { id: "FAILED_OR_CANCELLED", label: "失败/已取消" },
            ] as const
          ).map((item) => (
            <Button
              key={item.id}
              type="button"
              size="xs"
              variant={filter === item.id ? "secondary" : "ghost"}
              onClick={() => setFilter(item.id)}
            >
              {item.label}
            </Button>
          ))}
        </div>

        <Button
          type="button"
          variant="outline"
          size="xs"
          disabled={refreshing}
          onClick={refreshAll}
        >
          <RefreshCwIcon
            className={refreshing ? "animate-spin motion-reduce:animate-none" : ""}
          />
          <span>刷新</span>
        </Button>
      </div>

      <div className="flex-1 space-y-4 overflow-y-auto p-4">
        {/* Tracked Composition Tasks */}
        {compositionTasks.length > 0 ? (
          <div className="space-y-2">
            <div className="flex items-center justify-between text-xs font-medium text-muted-foreground">
              <span>FFmpeg 合成任务 ({compositionTasks.length})</span>
              <Button
                type="button"
                variant="link"
                size="xs"
                onClick={() => openCanvas("storyboard")}
              >
                前往分镜合片面板
              </Button>
            </div>
            {compositionTasks.map((comp) => {
              const active =
                comp.status === "PENDING" ||
                comp.status === "VALIDATING" ||
                comp.status === "COMPOSING" ||
                comp.status === "UPLOADING" ||
                comp.status === "PUBLISHING";
              return (
                <div
                  key={comp.taskId}
                  className="space-y-2.5 rounded-2xl border bg-card p-3.5"
                >
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="flex items-center gap-2">
                      <Badge
                        variant={
                          comp.status === "SUCCEEDED"
                            ? "default"
                            : comp.status === "FAILED"
                              ? "destructive"
                              : "secondary"
                        }
                      >
                        {COMPOSITION_STATUS_LABEL[comp.status]}
                      </Badge>
                      <Badge variant="outline" className="text-[11px]">
                        分镜 v{comp.storyboardVersion}
                      </Badge>
                    </div>
                    <span className="text-[11px] text-muted-foreground tabular-nums">
                      {formatDateTime(comp.createdAt)}
                    </span>
                  </div>

                  {comp.outputAssetId ? (
                    <AssetMediaPreview
                      assetId={comp.outputAssetId}
                      kind="VIDEO"
                      alt={`合成成片 ${comp.taskId}`}
                      className="aspect-video w-full"
                    />
                  ) : null}

                  <div className="flex gap-1.5">
                    {active ? (
                      <Button
                        type="button"
                        variant="outline"
                        size="xs"
                        disabled={actingTaskId === comp.taskId}
                        onClick={() =>
                          void handleCompositionAction(comp.taskId, "cancel")
                        }
                      >
                        取消合成
                      </Button>
                    ) : null}
                    {comp.status === "FAILED" ||
                    comp.status === "CANCELLED" ? (
                      <Button
                        type="button"
                        variant="outline"
                        size="xs"
                        disabled={actingTaskId === comp.taskId}
                        onClick={() =>
                          void handleCompositionAction(comp.taskId, "retry")
                        }
                      >
                        重试合成
                      </Button>
                    ) : null}
                  </div>
                </div>
              );
            })}
          </div>
        ) : null}

        {/* Generation Tasks */}
        {filteredTasks.length === 0 ? (
          <div className="rounded-2xl border border-dashed p-8 text-center text-xs text-muted-foreground">
            暂无符合条件的媒体生成任务。
          </div>
        ) : (
          <div className="space-y-3">
            {filteredTasks.map((task) => {
              const isActive = isGenerationStatusActive(task.status);
              const isArchiveFailed = task.status === "ARCHIVE_FAILED";
              const isUnknown = task.status === "UNKNOWN";
              const canRegenerate =
                !isActive && !isArchiveFailed && !isUnknown;

              return (
                <div
                  key={task.taskId}
                  className="space-y-3 rounded-2xl border bg-card p-3.5"
                >
                  {/* Top row */}
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <Badge variant="outline">
                        {task.type === "VIDEO" ? "视频" : "图片"}
                      </Badge>
                      <Badge
                        variant={
                          task.status === "SUCCEEDED"
                            ? "default"
                            : task.status === "FAILED" ||
                                isArchiveFailed ||
                                isUnknown ||
                                task.status === "DELIVERY_FAILED"
                              ? "destructive"
                              : "secondary"
                        }
                      >
                        {GENERATION_STATUS_LABEL[task.status]}
                      </Badge>
                      {task.shotId ? (
                        <Badge variant="secondary" className="text-[11px]">
                          {task.shotId}
                        </Badge>
                      ) : null}
                    </div>

                    <div className="flex items-center gap-2 text-[11px] text-muted-foreground tabular-nums">
                      <span>{task.priceUnits} 额度</span>
                      <span>·</span>
                      <span>{formatDateTime(task.createdAt)}</span>
                    </div>
                  </div>

                  <p className="line-clamp-2 text-xs leading-relaxed text-foreground">
                    {task.prompt}
                  </p>

                  {isUnknown ? (
                    <Alert variant="destructive">
                      <AlertTriangleIcon />
                      <AlertTitle>生成结果核对中</AlertTitle>
                      <AlertDescription className="text-xs">
                        正在确认任务最终结果，为避免重复扣费，暂不支持重复发起同参生成。
                      </AlertDescription>
                    </Alert>
                  ) : null}

                  {task.errorMessage ? (
                    <p className="rounded-lg bg-destructive/10 px-2.5 py-1.5 text-xs text-destructive">
                      {task.errorMessage}
                    </p>
                  ) : null}

                  {/* Outputs */}
                  {task.outputs.some((o) => o.assetId) ? (
                    <div className="grid gap-2 sm:grid-cols-2">
                      {task.outputs.map((out) =>
                        out.assetId ? (
                          <div key={out.outputIndex} className="space-y-1">
                            <AssetMediaPreview
                              assetId={out.assetId}
                              kind={task.type}
                              alt={`生成产物 #${out.outputIndex}`}
                              className="aspect-video w-full"
                            />
                            {out.fileId ? (
                              <Button
                                type="button"
                                variant="ghost"
                                size="xs"
                                onClick={() =>
                                  openCanvas("files", out.fileId)
                                }
                              >
                                <FileTextIcon />
                                <span>查看归档文件</span>
                              </Button>
                            ) : null}
                          </div>
                        ) : null
                      )}
                    </div>
                  ) : null}

                  {/* Action footer */}
                  <div className="flex flex-wrap gap-1.5 border-t pt-2.5">
                    {isActive ? (
                      <Button
                        type="button"
                        variant="outline"
                        size="xs"
                        disabled={actingTaskId === task.taskId}
                        onClick={() =>
                          void handleGenerationAction(task.taskId, "cancel")
                        }
                      >
                        <StopCircleIcon />
                        <span>请求取消</span>
                      </Button>
                    ) : null}

                    {isArchiveFailed ? (
                      <Button
                        type="button"
                        variant="secondary"
                        size="xs"
                        disabled={actingTaskId === task.taskId}
                        onClick={() =>
                          void handleGenerationAction(
                            task.taskId,
                            "retryArchive"
                          )
                        }
                      >
                        <RotateCcwIcon />
                        <span>重试归档 (不重扣费)</span>
                      </Button>
                    ) : null}

                    {canRegenerate ? (
                      <Button
                        type="button"
                        variant="outline"
                        size="xs"
                        onClick={() => handleRegenerateWithSameParams(task)}
                      >
                        <RotateCcwIcon />
                        <span>同参再生成</span>
                      </Button>
                    ) : null}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
