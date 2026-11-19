"use client";

import Link from "next/link";
import {
  ArrowRightIcon,
  CircleCheckIcon,
  TriangleAlertIcon,
  type LucideIcon,
} from "lucide-react";

import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import type { AdminDashboardAttention } from "@/lib/api/types";
import { cn } from "@/lib/utils";

interface AttentionItem {
  key: keyof AdminDashboardAttention;
  label: string;
  hint: string;
  href: string;
  icon: LucideIcon;
}

const ATTENTION_ITEMS: AttentionItem[] = [
  {
    key: "archiveFailed",
    label: "保存异常",
    hint: "生成结果归档未完成",
    href: "/tasks",
    icon: TriangleAlertIcon,
  },
  {
    key: "pendingReconciliation",
    label: "待核对额度",
    hint: "预占额度需要人工确认",
    href: "/admin/billing",
    icon: TriangleAlertIcon,
  },
  {
    key: "failedWorkflowStarts",
    label: "工作流补偿失败",
    hint: "启动补偿次数已耗尽",
    href: "/admin/audit-logs",
    icon: TriangleAlertIcon,
  },
  {
    key: "failedTasks24h",
    label: "近 24 小时失败任务",
    hint: "生成或合成任务未完成",
    href: "/tasks",
    icon: TriangleAlertIcon,
  },
  {
    key: "failedCompositionTasks",
    label: "视频合成失败",
    hint: "合成任务历史失败数",
    href: "/tasks",
    icon: TriangleAlertIcon,
  },
];

export function AttentionCard({
  attention,
  className,
}: {
  attention: AdminDashboardAttention;
  className?: string;
}) {
  const total = ATTENTION_ITEMS.reduce(
    (sum, item) => sum + attention[item.key],
    0
  );
  const activeItems = ATTENTION_ITEMS.filter(
    (item) => attention[item.key] > 0
  );
  const visibleItems = total > 0 ? activeItems : ATTENTION_ITEMS;

  return (
    <Card size="sm" className={cn("flex h-full flex-col", className)}>
      <CardHeader>
        <div className="flex items-center gap-2">
          <span
            className={cn(
              "flex size-7 items-center justify-center rounded-control-md",
              total > 0
                ? "bg-destructive/10 text-destructive"
                : "bg-muted/50 text-muted-foreground"
            )}
          >
            <TriangleAlertIcon className="size-3.5" />
          </span>
          <CardTitle className="text-sm">待处理异常</CardTitle>
        </div>
        <CardDescription className="text-xs">
          按影响面排序，点击直达处理页
        </CardDescription>
        <CardAction>
          <Badge variant={total > 0 ? "destructive" : "secondary"}>
            {total > 0 ? `需关注 ${total} 项` : "全部正常"}
          </Badge>
        </CardAction>
      </CardHeader>

      <CardContent className="flex-1">
        {total === 0 ? (
          <div className="flex h-full min-h-[220px] flex-col items-center justify-center gap-2 text-center">
            <span className="flex size-10 items-center justify-center rounded-control-md bg-success/10 text-success">
              <CircleCheckIcon className="size-4" />
            </span>
            <p className="text-sm font-medium">当前无待处理异常</p>
            <p className="text-xs text-muted-foreground">
              最近一次检查未发现需要人工介入的任务
            </p>
          </div>
        ) : (
          <div className="divide-y divide-border/50">
            {visibleItems.map((item) => {
              const count = attention[item.key];
              return (
                <Link
                  key={item.key}
                  href={item.href}
                  className="group flex items-center gap-3 py-2.5 first:pt-0 last:pb-0"
                >
                  <span className="flex size-8 shrink-0 items-center justify-center rounded-control-md bg-destructive/10 text-destructive">
                    <item.icon className="size-3.5" />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="text-xs font-medium">{item.label}</p>
                    <p className="truncate text-[11px] text-muted-foreground">
                      {item.hint}
                    </p>
                  </div>
                  <span className="text-sm font-semibold text-destructive tabular-nums">
                    {count}
                  </span>
                  <ArrowRightIcon className="size-3.5 text-muted-foreground opacity-0 transition-opacity motion-reduce:transition-none group-hover:opacity-100" />
                </Link>
              );
            })}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
