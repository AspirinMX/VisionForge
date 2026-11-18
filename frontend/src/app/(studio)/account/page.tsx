"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  ExternalLinkIcon,
  LogOutIcon,
  RefreshCwIcon,
  WalletIcon,
} from "lucide-react";
import { toast } from "sonner";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { PageHeader } from "@/components/layout/page-header";
import { useAuth } from "@/components/providers/auth-provider";
import { useCredit } from "@/components/providers/credit-provider";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { useAsyncData } from "@/hooks/use-async-data";
import { accountApi } from "@/lib/api/endpoints";
import { notifyApiError } from "@/lib/api/feedback";
import type {
  CreditLedgerEntryResponse,
  CreditLedgerEntryType,
} from "@/lib/api/types";
import {
  formatDateTime,
  GENERATION_BILLING_LABEL,
  GENERATION_STATUS_LABEL,
  USER_ROLE_LABEL,
  USER_STATUS_LABEL,
} from "@/lib/format";

const CREDIT_ENTRY_LABEL: Record<CreditLedgerEntryType, string> = {
  GRANT: "初始授予",
  RESERVE: "预占",
  SETTLE: "结算扣费",
  RELEASE: "释放预占",
};

const CREDIT_ENTRY_HINT: Record<CreditLedgerEntryType, string> = {
  GRANT: "可用余额增加",
  RESERVE: "冻结，不影响总余额",
  SETTLE: "总余额与预占同时减少",
  RELEASE: "仅释放冻结",
};

const LEDGER_PAGE_SIZE = 50;

export default function AccountPage() {
  const router = useRouter();
  const { user, refreshUser, logout } = useAuth();
  const {
    credit,
    loading: loadingCredit,
    error: creditError,
    refreshCredit,
  } = useCredit();

  const [refreshingUser, setRefreshingUser] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [ledgerGeneration, setLedgerGeneration] = useState(0);
  const [extraLedger, setExtraLedger] = useState<{
    baseKey: string;
    items: CreditLedgerEntryResponse[];
    hasMore: boolean;
  } | null>(null);
  const [loadingMoreLedger, setLoadingMoreLedger] = useState(false);

  const ledgerBaseKey = user
    ? `account-ledger:${user.id}:${ledgerGeneration}`
    : "account-ledger:anon";

  const {
    data: ledger,
    loading: loadingLedger,
    error: ledgerError,
    reload: reloadLedger,
  } = useAsyncData(
    () => accountApi.ledger({ limit: LEDGER_PAGE_SIZE }),
    ledgerBaseKey,
    {
      enabled: Boolean(user),
      errorMessage: "加载额度流水失败",
    }
  );

  const {
    data: usage,
    loading: loadingUsage,
    error: usageError,
    reload: reloadUsage,
  } = useAsyncData(
    () => accountApi.usage(100),
    user ? `account-usage:${user.id}` : "account-usage:anon",
    {
      enabled: Boolean(user),
      errorMessage: "加载近期任务额度汇总失败",
    }
  );

  const allLedgerEntries = useMemo(() => {
    const first = ledger ?? [];
    if (extraLedger && extraLedger.baseKey === ledgerBaseKey) {
      const seen = new Set(first.map((item) => item.id));
      return [...first, ...extraLedger.items.filter((item) => !seen.has(item.id))];
    }
    return first;
  }, [extraLedger, ledger, ledgerBaseKey]);

  const hasMoreLedger =
    extraLedger?.baseKey === ledgerBaseKey
      ? extraLedger.hasMore
      : (ledger?.length ?? 0) === LEDGER_PAGE_SIZE;

  const handleRefreshBilling = () => {
    refreshCredit();
    setLedgerGeneration((n) => n + 1);
    setExtraLedger(null);
    reloadLedger();
    reloadUsage();
  };

  const handleLoadMoreLedger = async () => {
    if (loadingMoreLedger || allLedgerEntries.length === 0) {
      return;
    }
    const lastEntry = allLedgerEntries[allLedgerEntries.length - 1];
    setLoadingMoreLedger(true);
    try {
      const older = await accountApi.ledger({
        limit: LEDGER_PAGE_SIZE,
        before: lastEntry.id,
      });
      setExtraLedger((prev) => {
        const previousItems =
          prev && prev.baseKey === ledgerBaseKey ? prev.items : [];
        const seen = new Set(previousItems.map((item) => item.id));
        return {
          baseKey: ledgerBaseKey,
          items: [
            ...previousItems,
            ...older.filter((item) => !seen.has(item.id)),
          ],
          hasMore: older.length === LEDGER_PAGE_SIZE,
        };
      });
    } catch (err) {
      notifyApiError(err, "加载更早额度流水失败");
    } finally {
      setLoadingMoreLedger(false);
    }
  };

  const handleRefreshProfile = async () => {
    setRefreshingUser(true);
    try {
      await refreshUser();
      toast.success("已同步最新账户信息");
    } catch (err) {
      notifyApiError(err, "刷新账户信息失败");
    } finally {
      setRefreshingUser(false);
    }
  };

  const handleLogout = async () => {
    setLoggingOut(true);
    await logout();
    router.replace("/login");
  };

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader title="账户与额度" />

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto w-full max-w-3xl space-y-6">
          <Card>
            <CardHeader className="flex flex-row items-start justify-between gap-4">
              <div className="space-y-1">
                <CardTitle>个人资料</CardTitle>
                <CardDescription>当前登录账号信息</CardDescription>
              </div>
              <Button
                type="button"
                variant="outline"
                size="sm"
                onClick={handleRefreshProfile}
                disabled={refreshingUser}
              >
                <RefreshCwIcon className={refreshingUser ? "animate-spin" : ""} />
                <span>刷新信息</span>
              </Button>
            </CardHeader>

            <CardContent className="grid gap-4 sm:grid-cols-2">
              <div>
                <p className="text-xs text-muted-foreground">显示名称</p>
                <p className="mt-1 text-sm font-medium">
                  {user?.displayName ?? "—"}
                </p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">注册邮箱</p>
                <p className="mt-1 text-sm font-medium">{user?.email ?? "—"}</p>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">账号状态</p>
                <div className="mt-1.5 flex items-center gap-2">
                  <Badge variant={user?.role === "ADMIN" ? "default" : "secondary"}>
                    {user ? USER_ROLE_LABEL[user.role] : "—"}
                  </Badge>
                  <Badge variant="outline">
                    {user ? USER_STATUS_LABEL[user.status] : "—"}
                  </Badge>
                </div>
              </div>
              <div>
                <p className="text-xs text-muted-foreground">注册时间</p>
                <p className="mt-1 text-sm tabular-nums">
                  {formatDateTime(user?.createdAt)}
                </p>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <div className="flex items-center justify-between gap-2">
                <div className="space-y-1">
                  <CardTitle className="flex items-center gap-2">
                    <WalletIcon className="size-4" />
                    <span>额度与消费记录</span>
                  </CardTitle>
                  <CardDescription>
                    创作任务额度预占、结算与近 30 天用量概览
                  </CardDescription>
                </div>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={handleRefreshBilling}
                  disabled={loadingCredit || loadingLedger || loadingUsage}
                >
                  <RefreshCwIcon
                    className={
                      loadingCredit || loadingLedger ? "animate-spin" : ""
                    }
                  />
                  <span>刷新额度</span>
                </Button>
              </div>
            </CardHeader>

            <CardContent className="space-y-5">
              <ApiErrorAlert error={creditError} title="读取额度账户失败" />

              {loadingCredit && !credit ? (
                <div className="grid gap-3 sm:grid-cols-3">
                  <Skeleton className="h-20 rounded-2xl" />
                  <Skeleton className="h-20 rounded-2xl" />
                  <Skeleton className="h-20 rounded-2xl" />
                </div>
              ) : (
                <div className="grid gap-3 sm:grid-cols-3">
                  <div className="rounded-2xl border p-3.5">
                    <p className="text-xs text-muted-foreground">可用余额</p>
                    <p className="mt-1 text-xl font-semibold tabular-nums">
                      {credit?.availableUnits ?? 0}
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground tabular-nums">
                      总余额 {credit?.balanceUnits ?? 0} · 更新于{" "}
                      {formatDateTime(credit?.updatedAt)}
                    </p>
                  </div>
                  <div className="rounded-2xl border p-3.5">
                    <p className="text-xs text-muted-foreground">当前预占中</p>
                    <p className="mt-1 text-xl font-semibold tabular-nums">
                      {credit?.reservedUnits ?? 0}
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      进行中任务冻结的额度
                    </p>
                  </div>
                  <div className="rounded-2xl border p-3.5">
                    <p className="text-xs text-muted-foreground">
                      最近 {usage?.window ?? 100} 条任务额度
                    </p>
                    <p className="mt-1 text-xl font-semibold tabular-nums">
                      {usage?.totalPriceUnits ?? 0}
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground tabular-nums">
                      共{" "}
                      {(usage?.buckets ?? []).reduce(
                        (sum, bucket) => sum + bucket.taskCount,
                        0
                      )}{" "}
                      个任务
                    </p>
                  </div>
                </div>
              )}

              <Separator />

              <div className="space-y-2.5">
                <div className="flex items-center justify-between">
                  <h3 className="text-sm font-medium">消费流水</h3>
                  <span className="text-xs text-muted-foreground tabular-nums">
                    已加载 {allLedgerEntries.length} 条
                  </span>
                </div>
                <ApiErrorAlert error={ledgerError} title="读取额度流水失败" />
                {loadingLedger && !ledger ? (
                  <Skeleton className="h-24 rounded-2xl" />
                ) : allLedgerEntries.length === 0 ? (
                  <p className="rounded-2xl border px-3.5 py-6 text-center text-xs text-muted-foreground">
                    暂无额度流水；首次生成或访问本页后会出现初始授予记录。
                  </p>
                ) : (
                  <div className="space-y-2">
                    <div className="divide-y rounded-2xl border">
                      {allLedgerEntries.map((entry) => (
                        <div
                          key={entry.id}
                          className="flex flex-wrap items-center justify-between gap-2 px-3.5 py-2.5 text-xs"
                        >
                          <div className="flex flex-wrap items-center gap-2">
                            <Badge
                              variant={
                                entry.entryType === "SETTLE"
                                  ? "default"
                                  : entry.entryType === "GRANT"
                                    ? "secondary"
                                    : "outline"
                              }
                            >
                              {CREDIT_ENTRY_LABEL[entry.entryType]}
                            </Badge>
                            <span className="font-mono font-medium tabular-nums">
                              {entry.amountUnits}
                            </span>
                            <span className="text-muted-foreground">
                              {CREDIT_ENTRY_HINT[entry.entryType]}
                            </span>
                          </div>
                          <div className="flex flex-wrap items-center gap-2 text-muted-foreground tabular-nums">
                            {entry.taskType ? (
                              <Badge variant="outline">
                                {entry.taskType === "VIDEO" ? "视频" : "图片"}
                              </Badge>
                            ) : null}
                            {entry.taskStatus ? (
                              <span>
                                {GENERATION_STATUS_LABEL[entry.taskStatus]}
                              </span>
                            ) : null}
                            {entry.taskId ? (
                              <Button
                                variant="link"
                                size="xs"
                                nativeButton={false}
                                render={
                                  <Link href={`/tasks?taskId=${entry.taskId}`} />
                                }
                                className="h-auto p-0 text-[11px]"
                              >
                                <span>查看任务</span>
                                <ExternalLinkIcon className="size-3" />
                              </Button>
                            ) : null}
                            <span>{formatDateTime(entry.createdAt)}</span>
                          </div>
                        </div>
                      ))}
                    </div>
                    {hasMoreLedger ? (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        onClick={() => void handleLoadMoreLedger()}
                        disabled={loadingMoreLedger}
                        className="w-full"
                      >
                        {loadingMoreLedger ? "加载中..." : "加载更早流水"}
                      </Button>
                    ) : null}
                  </div>
                )}
              </div>

              <Separator />

              <div className="space-y-2.5">
                <h3 className="text-sm font-medium">
                  最近任务 priceUnits 汇总
                </h3>
                <ApiErrorAlert error={usageError} title="读取近期任务汇总失败" />
                {loadingUsage && !usage ? (
                  <Skeleton className="h-16 rounded-2xl" />
                ) : (usage?.buckets ?? []).length === 0 ? (
                  <p className="text-xs text-muted-foreground">
                    尚无媒体生成任务。
                  </p>
                ) : (
                  <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                    {(usage?.buckets ?? []).map((bucket) => (
                      <div
                        key={bucket.billingStatus}
                        className="rounded-2xl border p-3"
                      >
                        <p className="text-xs text-muted-foreground">
                          {GENERATION_BILLING_LABEL[bucket.billingStatus]}
                        </p>
                        <p className="mt-1 text-lg font-semibold tabular-nums">
                          {bucket.priceUnits}
                        </p>
                        <p className="text-xs text-muted-foreground tabular-nums">
                          {bucket.taskCount} 个任务
                        </p>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <CardTitle>退出登录</CardTitle>
              <CardDescription>
                退出当前设备上的账号登录状态。
              </CardDescription>
            </CardHeader>
            <Separator />
            <CardFooter className="justify-end pt-4">
              <Button
                type="button"
                variant="destructive"
                onClick={handleLogout}
                disabled={loggingOut}
              >
                <LogOutIcon />
                <span>{loggingOut ? "退出中..." : "退出登录"}</span>
              </Button>
            </CardFooter>
          </Card>
        </div>
      </div>
    </div>
  );
}
