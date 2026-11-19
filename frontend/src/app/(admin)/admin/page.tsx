"use client";

import { useEffect, useMemo, useState } from "react";
import {
  ActivityIcon,
  CircleCheckBigIcon,
  CoinsIcon,
  CpuIcon,
  RefreshCwIcon,
  UsersIcon,
} from "lucide-react";

import { AttentionCard } from "@/components/admin/overview/attention-card";
import { ChartCard } from "@/components/admin/overview/chart-card";
import {
  BillingTrendChart,
  TaskOutcomeChart,
  TokenTrendChart,
} from "@/components/admin/overview/charts";
import { LeaderboardCard } from "@/components/admin/overview/leaderboard-card";
import {
  HeroStatCard,
  StatTile,
} from "@/components/admin/overview/stat-tile";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminDashboardApi } from "@/lib/api/endpoints";
import type { AdminDashboardLive } from "@/lib/api/types";
import {
  formatCompactNumber,
  formatInteger,
  formatPercent,
  formatShortTime,
} from "@/lib/format";
import { cn } from "@/lib/utils";

const RANGE_OPTIONS = [7, 30, 90] as const;
const REFRESH_INTERVAL_MS = 60_000;

export default function AdminOverviewPage() {
  const [rangeDays, setRangeDays] = useState<number>(30);
  const { data, loading, error, reload } = useAsyncData(
    () => adminDashboardApi.get(rangeDays),
    `admin-dashboard-${rangeDays}`,
    { errorMessage: "加载运维数据失败" }
  );

  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") {
        reload();
      }
    }, REFRESH_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [reload]);

  const daily = useMemo(() => data?.daily ?? [], [data?.daily]);
  const totals = data?.totals;
  const tokenSeries = useMemo(
    () => daily.map((point) => point.inputTokens + point.outputTokens),
    [daily]
  );
  const creditSeries = useMemo(
    () => daily.map((point) => point.settledCredits),
    [daily]
  );
  const outcomeTotals = useMemo(
    () =>
      daily.reduce(
        (accumulator, point) => ({
          succeeded: accumulator.succeeded + point.succeeded,
          failed: accumulator.failed + point.failed,
          cancelled: accumulator.cancelled + point.cancelled,
        }),
        { succeeded: 0, failed: 0, cancelled: 0 }
      ),
    [daily]
  );

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
            aria-label="刷新运维数据"
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
        <div
          className={cn(
            "mx-auto w-full max-w-7xl space-y-4 transition-opacity motion-reduce:transition-none",
            loading && data ? "opacity-60" : null
          )}
        >
          <ApiErrorAlert error={error} title="获取运维数据失败" />

          <div className="flex flex-wrap items-center justify-between gap-3">
            <Tabs
              value={String(rangeDays)}
              onValueChange={(value) => setRangeDays(Number(value))}
            >
              <TabsList>
                {RANGE_OPTIONS.map((option) => (
                  <TabsTrigger key={option} value={String(option)}>
                    近 {option} 天
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>

            <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span className="tabular-nums">
                {data
                  ? `数据截至 ${formatShortTime(data.generatedAt)}`
                  : "正在读取数据"}
              </span>
              <span aria-hidden="true">·</span>
              <span>每 60 秒更新</span>
              {data ? <LiveStatus live={data.live} /> : null}
            </div>
          </div>

          {loading && !data ? (
            <DashboardSkeleton />
          ) : data && totals ? (
            <>
              <section className="grid gap-4 lg:grid-cols-12">
                <div className="lg:col-span-5">
                  <HeroStatCard
                    icon={CoinsIcon}
                    label="全站计费总额"
                    value={formatInteger(totals.settledCredits)}
                    unit="额度"
                    hint={`近 ${rangeDays} 天 · 今日 ${formatCompactNumber(
                      totals.todayCredits
                    )} 额度`}
                    delta={data.deltas.settledCredits}
                    series={creditSeries}
                  />
                </div>
                <div className="grid gap-4 sm:grid-cols-3 lg:col-span-7">
                  <StatTile
                    icon={CpuIcon}
                    label="Token 消耗"
                    value={formatCompactNumber(
                      totals.inputTokens + totals.outputTokens
                    )}
                    hint={`输入 ${formatCompactNumber(
                      totals.inputTokens
                    )} · 输出 ${formatCompactNumber(totals.outputTokens)}`}
                    delta={data.deltas.totalTokens}
                    series={tokenSeries}
                  />
                  <StatTile
                    icon={CircleCheckBigIcon}
                    label="生成成功率"
                    value={formatPercent(totals.successRate)}
                    hint={`完成 ${formatInteger(
                      outcomeTotals.succeeded
                    )} · 失败 ${formatInteger(
                      outcomeTotals.failed
                    )} · 取消 ${formatInteger(outcomeTotals.cancelled)}`}
                    delta={data.deltas.successRatePoints}
                    deltaKind="points"
                    meter={totals.successRate}
                  />
                  <StatTile
                    icon={UsersIcon}
                    label="活跃用户"
                    value={formatInteger(totals.activeUsers)}
                    hint={`新增 ${formatInteger(
                      totals.newUsers
                    )} · 近 ${rangeDays} 天`}
                    delta={data.deltas.activeUsers}
                  />
                </div>
              </section>

              <section className="grid gap-4 lg:grid-cols-12">
                <ChartCard
                  icon={CoinsIcon}
                  title="额度消耗趋势"
                  description={`近 ${rangeDays} 天已结算额度与 7 日均线`}
                  className="lg:col-span-7"
                  table={{
                    columns: [
                      { label: "日期" },
                      { label: "已结算额度", align: "right" },
                      { label: "完成", align: "right" },
                      { label: "失败", align: "right" },
                    ],
                    rows: daily.map((point) => [
                      point.date,
                      formatInteger(point.settledCredits),
                      formatInteger(point.succeeded),
                      formatInteger(point.failed),
                    ]),
                  }}
                >
                  <BillingTrendChart daily={daily} />
                </ChartCard>

                <LeaderboardCard
                  className="lg:col-span-5"
                  rangeDays={rangeDays}
                  credits={data.topUsersByCredits}
                  tokens={data.topUsersByTokens}
                  creditTotal={totals.settledCredits}
                  tokenTotal={totals.inputTokens + totals.outputTokens}
                />
              </section>

              <section className="grid gap-4 lg:grid-cols-12">
                <ChartCard
                  icon={CpuIcon}
                  title="Token 消耗趋势"
                  description={`近 ${rangeDays} 天对话输入与输出 Token`}
                  className="lg:col-span-7"
                  table={{
                    columns: [
                      { label: "日期" },
                      { label: "输入 Token", align: "right" },
                      { label: "输出 Token", align: "right" },
                      { label: "成功调用", align: "right" },
                    ],
                    rows: daily.map((point) => [
                      point.date,
                      formatInteger(point.inputTokens),
                      formatInteger(point.outputTokens),
                      formatInteger(point.invocations),
                    ]),
                  }}
                >
                  <TokenTrendChart daily={daily} />
                </ChartCard>

                <AttentionCard
                  className="lg:col-span-5"
                  attention={data.attention}
                />
              </section>

              <section className="grid gap-4 lg:grid-cols-12">
                <ChartCard
                  icon={ActivityIcon}
                  title="任务结果趋势"
                  description={`近 ${rangeDays} 天媒体生成任务的完成、失败与取消`}
                  className="lg:col-span-12"
                  table={{
                    columns: [
                      { label: "日期" },
                      { label: "完成", align: "right" },
                      { label: "失败", align: "right" },
                      { label: "取消", align: "right" },
                      { label: "成功率", align: "right" },
                    ],
                    rows: daily.map((point) => {
                      const finished =
                        point.succeeded + point.failed + point.cancelled;
                      return [
                        point.date,
                        formatInteger(point.succeeded),
                        formatInteger(point.failed),
                        formatInteger(point.cancelled),
                        formatPercent(
                          finished > 0 ? point.succeeded / finished : null
                        ),
                      ];
                    }),
                  }}
                >
                  <TaskOutcomeChart daily={daily} />
                </ChartCard>
              </section>
            </>
          ) : null}
        </div>
      </div>
    </div>
  );
}

function LiveStatus({ live }: { live: AdminDashboardLive }) {
  const items = [
    { label: "对话处理中", value: live.activeAgentRuns },
    { label: "媒体生成中", value: live.activeGenerationTasks },
    { label: "视频合成中", value: live.activeCompositionTasks },
  ];

  return (
    <div className="flex flex-wrap items-center gap-2">
      {items.map((item) => (
        <Badge
          key={item.label}
          variant="outline"
          className="gap-1.5 bg-background font-normal"
        >
          <span
            className={cn(
              "size-1.5 rounded-full",
              item.value > 0
                ? "animate-pulse bg-primary motion-reduce:animate-none"
                : "bg-muted-foreground/40"
            )}
          />
          {item.label}
          <span className="tabular-nums">{item.value}</span>
        </Badge>
      ))}
    </div>
  );
}

function DashboardSkeleton() {
  return (
    <div className="space-y-4">
      <section className="grid gap-4 lg:grid-cols-12">
        <Skeleton className="h-40 rounded-surface-md lg:col-span-5" />
        <div className="grid gap-4 sm:grid-cols-3 lg:col-span-7">
          {Array.from({ length: 3 }).map((_, index) => (
            <Skeleton key={index} className="h-40 rounded-surface-md" />
          ))}
        </div>
      </section>
      <section className="grid gap-4 lg:grid-cols-12">
        <Skeleton className="h-[360px] rounded-surface-md lg:col-span-7" />
        <Skeleton className="h-[360px] rounded-surface-md lg:col-span-5" />
      </section>
      <section className="grid gap-4 lg:grid-cols-12">
        <Skeleton className="h-[360px] rounded-surface-md lg:col-span-7" />
        <Skeleton className="h-[360px] rounded-surface-md lg:col-span-5" />
      </section>
      <Skeleton className="h-[360px] rounded-surface-md" />
    </div>
  );
}
