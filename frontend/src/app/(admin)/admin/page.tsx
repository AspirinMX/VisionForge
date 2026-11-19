"use client";

import Link from "next/link";
import {
  ActivityIcon,
  AlertTriangleIcon,
  ArrowRightIcon,
  BellRingIcon,
  CpuIcon,
  RefreshCwIcon,
  ScrollTextIcon,
  SlidersHorizontalIcon,
  TicketIcon,
  UsersIcon,
} from "lucide-react";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { PageHeader } from "@/components/layout/page-header";
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
import { useAsyncData } from "@/hooks/use-async-data";
import { adminOverviewApi } from "@/lib/api/endpoints";

interface MetricCardItem {
  label: string;
  value: number;
  hint: string;
  dangerWhenPositive?: boolean;
}

export default function AdminOverviewPage() {
  const { data, loading, error, reload } = useAsyncData(
    () => adminOverviewApi.get(),
    "admin-overview",
    { errorMessage: "加载运维总览指标失败" }
  );

  const attentionCount = data
    ? data.archiveFailedGenerationTasks +
      data.pendingReconciliationTasks +
      data.failedWorkflowStarts
    : 0;

  const summaryCards: MetricCardItem[] = data
    ? [
        {
          label: "注册用户总数",
          value: data.totalUsers,
          hint: `正常 ${data.activeUsers} · 禁用 ${data.disabledUsers}`,
        },
        {
          label: "活跃创作会话",
          value: data.activeConversations,
          hint: "未删除的持久化会话数",
        },
        {
          label: "已启用模型路由",
          value: data.enabledModels,
          hint: "对话 / 生图 / 视频路由总计",
        },
        {
          label: "进行中 Agent Run",
          value: data.activeAgentRuns,
          hint: `累计失败 ${data.failedAgentRuns}`,
        },
        {
          label: "进行中媒体生成",
          value: data.activeGenerationTasks,
          hint: "PENDING / SUBMITTING / RUNNING 等",
        },
        {
          label: "进行中视频合成",
          value: data.activeCompositionTasks,
          hint: `合成失败 ${data.failedCompositionTasks}`,
        },
      ]
    : [];

  const anomalyCards: MetricCardItem[] = data
    ? [
        {
          label: "ARCHIVE_FAILED 归档失败",
          value: data.archiveFailedGenerationTasks,
          hint: "对象存储归档未完成的生成任务",
          dangerWhenPositive: true,
        },
        {
          label: "PENDING_RECONCILIATION 待核账",
          value: data.pendingReconciliationTasks,
          hint: "需人工或定时核对的额度预占单",
          dangerWhenPositive: true,
        },
        {
          label: "工作流启动补偿失败",
          value: data.failedWorkflowStarts,
          hint: "Temporal Workflow 启动补偿耗尽记录",
          dangerWhenPositive: true,
        },
      ]
    : [];

  const quickLinks = [
    {
      href: "/admin/models",
      title: "模型配置与探活",
      desc: "管理多协议大模型密钥、定价并探测上游连通性",
      icon: CpuIcon,
    },
    {
      href: "/admin/configs",
      title: "Nacos 配置中心",
      desc: "动态发布安全策略、限流阈值、默认配额及告警配置",
      icon: SlidersHorizontalIcon,
    },
    {
      href: "/admin/alerts",
      title: "告警中心",
      desc: "配置 SMTP 邮件与 HMAC 签名 Webhook 渠道并审计投递",
      icon: BellRingIcon,
    },
    {
      href: "/admin/users",
      title: "用户生命周期管理",
      desc: "开通账号、启停状态、重置密码、强制吊销会话与人工调额",
      icon: UsersIcon,
    },
    {
      href: "/admin/billing",
      title: "计费与兑换码",
      desc: "批量发行/作废 CDK 兑换码并检索全站额度流水",
      icon: TicketIcon,
    },
    {
      href: "/admin/audit-logs",
      title: "审计日志台",
      desc: "按 TraceId/BizNo 检索管理员与业务操作并导出 CSV",
      icon: ScrollTextIcon,
    },
  ];

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="运维总览"
        actions={
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            onClick={reload}
            disabled={loading}
            aria-label="刷新总览指标"
          >
            <RefreshCwIcon
              className={
                loading ? "animate-spin motion-reduce:animate-none" : ""
              }
            />
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto w-full max-w-7xl space-y-6">
          <ApiErrorAlert error={error} title="获取系统运行指标失败" />

          {loading && !data ? (
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
              {Array.from({ length: 6 }).map((_, idx) => (
                <Skeleton key={idx} className="h-28 rounded-surface-md" />
              ))}
            </div>
          ) : data ? (
            <>
              <section className="space-y-3">
                <div className="flex items-center gap-2">
                  <ActivityIcon className="size-4 text-muted-foreground" />
                  <h2 className="text-sm font-medium">核心运行指标</h2>
                </div>
                <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
                  {summaryCards.map((item) => (
                    <Card key={item.label} className="rounded-surface-md">
                      <CardHeader className="pb-2">
                        <CardDescription>{item.label}</CardDescription>
                        <CardTitle className="text-2xl tabular-nums">
                          {item.value}
                        </CardTitle>
                      </CardHeader>
                      <CardContent className="text-xs text-muted-foreground">
                        {item.hint}
                      </CardContent>
                    </Card>
                  ))}
                </div>
              </section>

              <section className="space-y-3">
                <div className="flex items-center justify-between gap-2">
                  <div className="flex items-center gap-2">
                    <AlertTriangleIcon className="size-4 text-muted-foreground" />
                    <h2 className="text-sm font-medium">异常处理与补偿态势</h2>
                  </div>
                  <Badge variant={attentionCount > 0 ? "destructive" : "secondary"}>
                    {attentionCount > 0
                      ? `需关注 ${attentionCount} 项`
                      : "全链路健康"}
                  </Badge>
                </div>

                <div className="grid gap-3 sm:grid-cols-3">
                  {anomalyCards.map((item) => {
                    const flagged = item.dangerWhenPositive && item.value > 0;
                    return (
                      <Card
                        key={item.label}
                        className={
                          flagged
                            ? "rounded-surface-md border-destructive/40 bg-destructive/5"
                            : "rounded-surface-md"
                        }
                      >
                        <CardHeader className="pb-2">
                          <CardDescription>{item.label}</CardDescription>
                          <CardTitle
                            className={
                              flagged
                                ? "text-2xl text-destructive tabular-nums"
                                : "text-2xl tabular-nums"
                            }
                          >
                            {item.value}
                          </CardTitle>
                        </CardHeader>
                        <CardContent className="text-xs text-muted-foreground">
                          {item.hint}
                        </CardContent>
                      </Card>
                    );
                  })}
                </div>
              </section>
            </>
          ) : null}

          <section className="space-y-3">
            <h2 className="text-sm font-medium">控制台功能模块</h2>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {quickLinks.map((entry) => {
                const Icon = entry.icon;
                return (
                  <Link
                    key={entry.href}
                    href={entry.href}
                    className="group rounded-surface-md border bg-card p-4 transition-colors motion-reduce:transition-none hover:border-foreground/20 hover:bg-muted/30"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <div className="flex items-center gap-2 font-medium">
                        <Icon className="size-4 text-muted-foreground" />
                        <span className="text-sm">{entry.title}</span>
                      </div>
                      <ArrowRightIcon className="size-4 text-muted-foreground transition-transform motion-reduce:transition-none group-hover:translate-x-0.5" />
                    </div>
                    <p className="mt-2 text-xs leading-relaxed text-muted-foreground">
                      {entry.desc}
                    </p>
                  </Link>
                );
              })}
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}
