"use client";

import Link from "next/link";
import { RefreshCwIcon } from "lucide-react";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
  formatDateTime,
  GENERATION_BILLING_LABEL,
  GENERATION_STATUS_LABEL,
} from "@/lib/format";

export default function GlobalTasksPage() {
  const { data, loading, error, reload } = useAsyncData(
    () => generationApi.list({ limit: 50 }),
    "global-generation-tasks",
    { errorMessage: "加载全局任务失败" }
  );

  const tasks = data ?? [];

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="全局任务中心"
        subtitle="查看当前账号下的跨会话生成任务"
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

          {loading && !data ? (
            <Skeleton className="h-64 w-full rounded-2xl" />
          ) : tasks.length === 0 ? (
            <div className="rounded-2xl border border-dashed p-10 text-center text-sm text-muted-foreground">
              暂无媒体生成任务记录。
            </div>
          ) : (
            <div className="rounded-2xl border bg-card">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>类型</TableHead>
                    <TableHead>提示词摘要</TableHead>
                    <TableHead>执行状态</TableHead>
                    <TableHead>账务状态</TableHead>
                    <TableHead className="text-right">额度单位</TableHead>
                    <TableHead>创建时间</TableHead>
                    <TableHead className="text-right">所属会话</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {tasks.map((task) => (
                    <TableRow key={task.taskId}>
                      <TableCell>
                        <Badge variant="outline">{task.type}</Badge>
                      </TableCell>
                      <TableCell className="max-w-xs truncate">
                        {task.prompt}
                      </TableCell>
                      <TableCell>
                        <Badge variant="secondary">
                          {GENERATION_STATUS_LABEL[task.status]}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        {GENERATION_BILLING_LABEL[task.billingStatus]}
                      </TableCell>
                      <TableCell className="text-right tabular-nums">
                        {task.priceUnits}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground tabular-nums">
                        {formatDateTime(task.createdAt)}
                      </TableCell>
                      <TableCell className="text-right">
                        <Button
                          variant="link"
                          size="sm"
                          nativeButton={false}
                          render={
                            <Link href={`/conversations/${task.conversationId}`} />
                          }
                        >
                          打开会话
                        </Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
