"use client";

import { useMemo, useState } from "react";
import {
  CheckCircle2Icon,
  CodeIcon,
  RefreshCwIcon,
  RotateCcwIcon,
  SaveIcon,
  SlidersHorizontalIcon,
  WifiOffIcon,
} from "lucide-react";
import { toast } from "sonner";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { PageHeader } from "@/components/layout/page-header";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminConfigsApi } from "@/lib/api/endpoints";
import { notifyApiError, notifyError } from "@/lib/api/feedback";
import type { ConfigEntryResponse } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";
import { cn } from "@/lib/utils";

function formatJsonPretty(raw: string): string {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

export default function AdminConfigsPage() {
  const { data, loading, error, reload, mutate } = useAsyncData(
    () => adminConfigsApi.list(),
    "admin-nacos-configs",
    { errorMessage: "加载 Nacos 动态配置失败" }
  );

  const entries = useMemo(() => data?.entries ?? [], [data]);
  const [selectedDataId, setSelectedDataId] = useState<string | null>(null);
  const [draftsById, setDraftsById] = useState<Record<string, string>>({});
  const [publishing, setPublishing] = useState(false);
  const [resetTargetId, setResetTargetId] = useState<string | null>(null);
  const [resetting, setResetting] = useState(false);

  const activeEntry: ConfigEntryResponse | null = useMemo(() => {
    if (entries.length === 0) {
      return null;
    }
    if (selectedDataId) {
      return entries.find((e) => e.dataId === selectedDataId) ?? entries[0];
    }
    return entries[0];
  }, [entries, selectedDataId]);

  const editorContent = activeEntry
    ? draftsById[activeEntry.dataId] ?? formatJsonPretty(activeEntry.content)
    : "";

  const jsonValidationError = useMemo(() => {
    if (!activeEntry) {
      return null;
    }
    try {
      JSON.parse(editorContent);
      return null;
    } catch (err) {
      return err instanceof Error ? err.message : "JSON 语法错误";
    }
  }, [activeEntry, editorContent]);

  const isDirty = Boolean(
    activeEntry &&
      draftsById[activeEntry.dataId] !== undefined &&
      draftsById[activeEntry.dataId] !== formatJsonPretty(activeEntry.content)
  );

  const updateLocalEntry = (updated: ConfigEntryResponse) => {
    mutate((prev) =>
      prev
        ? {
            ...prev,
            entries: prev.entries.map((item) =>
              item.dataId === updated.dataId ? updated : item
            ),
          }
        : prev
    );
    setDraftsById((prev) => {
      const next = { ...prev };
      delete next[updated.dataId];
      return next;
    });
  };

  const handleFormatJson = () => {
    if (!activeEntry) {
      return;
    }
    try {
      const formatted = JSON.stringify(JSON.parse(editorContent), null, 2);
      setDraftsById((prev) => ({
        ...prev,
        [activeEntry.dataId]: formatted,
      }));
    } catch {
      notifyError("当前内容不是合法 JSON，无法格式化");
    }
  };

  const handlePublish = async () => {
    if (!activeEntry || publishing) {
      return;
    }
    if (jsonValidationError) {
      notifyError("请先修正 JSON 格式错误", jsonValidationError);
      return;
    }
    setPublishing(true);
    try {
      const updated = await adminConfigsApi.publish(activeEntry.dataId, {
        content: editorContent,
      });
      updateLocalEntry(updated);
      toast.success(
        `已发布「${updated.dataId}」（生效来源：${updated.source}）`
      );
    } catch (err) {
      notifyApiError(err, "发布配置失败");
    } finally {
      setPublishing(false);
    }
  };

  const handleConfirmReset = async () => {
    if (!resetTargetId || resetting) {
      return;
    }
    setResetting(true);
    try {
      const updated = await adminConfigsApi.reset(resetTargetId);
      updateLocalEntry(updated);
      setResetTargetId(null);
      toast.success(`已将「${updated.dataId}」重置为内置默认配置`);
    } catch (err) {
      notifyApiError(err, "重置配置失败");
    } finally {
      setResetting(false);
    }
  };

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="Nacos 配置中心"
        actions={
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            onClick={reload}
            disabled={loading}
            aria-label="刷新配置"
          >
            <RefreshCwIcon
              className={loading ? "animate-spin motion-reduce:animate-none" : ""}
            />
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto w-full max-w-7xl space-y-4">
          <ApiErrorAlert error={error} title="读取配置中心失败" />

          {loading && !data ? (
            <Skeleton className="h-72 w-full rounded-surface-md" />
          ) : data ? (
            <>
              <Card className="rounded-surface-md">
                <CardHeader className="flex flex-row flex-wrap items-center justify-between gap-3 py-4">
                  <div className="space-y-1">
                    <CardTitle className="flex items-center gap-2 text-base">
                      <SlidersHorizontalIcon className="size-4" />
                      <span>Nacos 运行时配置联动状态</span>
                    </CardTitle>
                    <CardDescription className="font-mono text-xs">
                      Addr: {data.serverAddr || "—"} · Namespace:{" "}
                      {data.namespace || "public"} · Group: {data.group}
                    </CardDescription>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge variant={data.connected ? "default" : "secondary"}>
                      {data.connected ? (
                        <>
                          <CheckCircle2Icon className="size-3" />
                          <span>Nacos 在线同步</span>
                        </>
                      ) : (
                        <>
                          <WifiOffIcon className="size-3" />
                          <span>本地兜底模式</span>
                        </>
                      )}
                    </Badge>
                    <Badge variant="outline">主来源：{data.source}</Badge>
                  </div>
                </CardHeader>
              </Card>

              <div className="grid gap-4 lg:grid-cols-[280px_1fr]">
                {/* DataId List */}
                <div className="space-y-2">
                  {entries.map((entry) => {
                    const selected = activeEntry?.dataId === entry.dataId;
                    const hasDraft =
                      draftsById[entry.dataId] !== undefined &&
                      draftsById[entry.dataId] !== formatJsonPretty(entry.content);
                    return (
                      <button
                        key={entry.dataId}
                        type="button"
                        onClick={() => setSelectedDataId(entry.dataId)}
                        className={cn(
                          "w-full rounded-surface-md border p-3.5 text-left transition-colors motion-reduce:transition-none",
                          selected
                            ? "border-primary bg-accent/60"
                            : "bg-card hover:bg-muted/40"
                        )}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <span className="truncate font-mono text-xs font-semibold">
                            {entry.dataId}
                          </span>
                          {hasDraft ? (
                            <span className="size-2 shrink-0 rounded-full bg-primary" />
                          ) : null}
                        </div>
                        <p className="mt-1 line-clamp-2 text-xs text-muted-foreground">
                          {entry.description}
                        </p>
                        <div className="mt-2 flex items-center justify-between text-[11px] text-muted-foreground">
                          <Badge variant="outline" className="text-[10px]">
                            {entry.source}
                          </Badge>
                          <span className="font-mono">{entry.md5.slice(0, 8)}</span>
                        </div>
                      </button>
                    );
                  })}
                </div>

                {/* Editor */}
                {activeEntry ? (
                  <Card className="rounded-surface-md">
                    <CardHeader className="border-b pb-4">
                      <div className="flex flex-wrap items-start justify-between gap-2">
                        <div className="space-y-1">
                          <CardTitle className="font-mono text-sm">
                            {activeEntry.dataId}
                          </CardTitle>
                          <CardDescription>
                            {activeEntry.description} · 更新于{" "}
                            {formatDateTime(activeEntry.updatedAt)} · MD5{" "}
                            <span className="font-mono">{activeEntry.md5}</span>
                          </CardDescription>
                        </div>

                        <div className="flex flex-wrap items-center gap-1.5">
                          <Button
                            type="button"
                            variant="outline"
                            size="xs"
                            onClick={handleFormatJson}
                          >
                            <CodeIcon />
                            <span>格式化 JSON</span>
                          </Button>
                          {isDirty ? (
                            <Button
                              type="button"
                              variant="ghost"
                              size="xs"
                              onClick={() =>
                                setDraftsById((prev) => {
                                  const next = { ...prev };
                                  delete next[activeEntry.dataId];
                                  return next;
                                })
                              }
                            >
                              放弃未发布修改
                            </Button>
                          ) : null}
                          <Button
                            type="button"
                            variant="outline"
                            size="xs"
                            disabled={publishing || resetting}
                            onClick={() => setResetTargetId(activeEntry.dataId)}
                          >
                            <RotateCcwIcon />
                            <span>重置默认</span>
                          </Button>
                          <Button
                            type="button"
                            size="xs"
                            disabled={publishing || Boolean(jsonValidationError)}
                            onClick={() => void handlePublish()}
                          >
                            <SaveIcon />
                            <span>
                              {publishing ? "发布中..." : "发布并热更新"}
                            </span>
                          </Button>
                        </div>
                      </div>
                    </CardHeader>

                    <CardContent className="space-y-4 pt-4">
                      {jsonValidationError ? (
                        <div className="rounded-xl border border-destructive/40 bg-destructive/10 px-3 py-2 text-xs text-destructive">
                          JSON 校验失败：{jsonValidationError}
                        </div>
                      ) : null}

                      <Textarea
                        rows={16}
                        value={editorContent}
                        onChange={(e) =>
                          setDraftsById((prev) => ({
                            ...prev,
                            [activeEntry.dataId]: e.target.value,
                          }))
                        }
                        className="font-mono text-xs leading-relaxed"
                      />

                      <div className="space-y-1.5">
                        <p className="text-xs font-medium text-muted-foreground">
                          内置默认模板参考（只读）
                        </p>
                        <pre className="max-h-48 overflow-auto rounded-xl border bg-muted/30 p-3 font-mono text-[11px] leading-relaxed">
                          {formatJsonPretty(activeEntry.defaultContent)}
                        </pre>
                      </div>
                    </CardContent>
                  </Card>
                ) : null}
              </div>
            </>
          ) : null}
        </div>
      </div>

      <AlertDialog
        open={resetTargetId !== null}
        onOpenChange={(open) => {
          if (!open && !resetting) {
            setResetTargetId(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>重置配置为默认值</AlertDialogTitle>
            <AlertDialogDescription>
              确定将「{resetTargetId}」恢复为代码默认配置并同步发布？这会覆盖当前 Nacos 或内存中的运行时覆写。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={resetting}>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                event.preventDefault();
                void handleConfirmReset();
              }}
              disabled={resetting}
            >
              {resetting ? "重置中..." : "确认重置"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
