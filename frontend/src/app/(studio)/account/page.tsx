"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import {
  CheckIcon,
  CopyIcon,
  LogOutIcon,
  RefreshCwIcon,
  WalletIcon,
} from "lucide-react";
import { toast } from "sonner";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { PageHeader } from "@/components/layout/page-header";
import { useAuth } from "@/components/providers/auth-provider";
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
import { copyToClipboard, notifyApiError } from "@/lib/api/feedback";
import type { CreditLedgerEntryType } from "@/lib/api/types";
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

export default function AccountPage() {
  const router = useRouter();
  const { user, refreshUser, logout } = useAuth();

  const [refreshingUser, setRefreshingUser] = useState(false);
  const [copiedId, setCopiedId] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);

  const {
    data: credit,
    loading: loadingCredit,
    error: creditError,
  } = useAsyncData(
    () => accountApi.credit(),
    user ? `account-credit:${user.id}` : "account-credit:anon",
    {
      enabled: Boolean(user),
      errorMessage: "加载额度余额失败",
    }
  );

  const {
    data: ledger,
    loading: loadingLedger,
    error: ledgerError,
  } = useAsyncData(
    () => accountApi.ledger({ limit: 50 }),
    user ? `account-ledger:${user.id}` : "account-ledger:anon",
    {
      enabled: Boolean(user),
      errorMessage: "加载额度流水失败",
    }
  );

  const {
    data: usage,
    loading: loadingUsage,
    error: usageError,
  } = useAsyncData(
    () => accountApi.usage(100),
    user ? `account-usage:${user.id}` : "account-usage:anon",
    {
      enabled: Boolean(user),
      errorMessage: "加载近期任务额度汇总失败",
    }
  );

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

  const handleCopyUserId = async () => {
    if (!user?.id) {
      return;
    }
    const ok = await copyToClipboard(user.id);
    if (ok) {
      setCopiedId(true);
      window.setTimeout(() => setCopiedId(false), 1800);
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
                <CardTitle>个人身份信息</CardTitle>
                <CardDescription>
                  来自服务端 `/api/v1/auth/me` 的当前登录账号档案
                </CardDescription>
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
                <p className="text-xs text-muted-foreground">系统角色与状态</p>
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
              <div className="sm:col-span-2">
                <p className="text-xs text-muted-foreground">用户 ID</p>
                <div className="mt-1 flex items-center gap-2">
                  <code className="rounded-lg bg-muted px-2 py-1 font-mono text-xs">
                    {user?.id ?? "—"}
                  </code>
                  <Button
                    type="button"
                    variant="ghost"
                    size="xs"
                    onClick={handleCopyUserId}
                  >
                    {copiedId ? <CheckIcon /> : <CopyIcon />}
                    <span>{copiedId ? "已复制" : "复制"}</span>
                  </Button>
                </div>
              </div>
            </CardContent>
          </Card>

          <Card>
            <CardHeader>
              <div className="flex items-center justify-between gap-2">
                <div className="space-y-1">
                  <CardTitle className="flex items-center gap-2">
                    <WalletIcon className="size-4" />
                    <span>额度账户与近期任务统计</span>
                  </CardTitle>
                  <CardDescription>
                    服务端按最小计量单位预占与结算媒体生成费用
                  </CardDescription>
                </div>
                <Badge variant="outline">实时账本</Badge>
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
                  <span className="text-xs text-muted-foreground">
                    按 UUIDv7 倒序，最近 50 条
                  </span>
                </div>
                <ApiErrorAlert error={ledgerError} title="读取额度流水失败" />
                {loadingLedger && !ledger ? (
                  <Skeleton className="h-24 rounded-2xl" />
                ) : (ledger ?? []).length === 0 ? (
                  <p className="rounded-2xl border px-3.5 py-6 text-center text-xs text-muted-foreground">
                    暂无额度流水；首次生成或访问本页后会出现初始授予记录。
                  </p>
                ) : (
                  <div className="divide-y rounded-2xl border">
                    {(ledger ?? []).map((entry) => (
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
                            <Badge variant="outline">{entry.taskType}</Badge>
                          ) : null}
                          {entry.taskStatus ? (
                            <span>{GENERATION_STATUS_LABEL[entry.taskStatus]}</span>
                          ) : null}
                          {entry.taskId ? (
                            <code className="font-mono text-[11px]">
                              {entry.taskId.slice(0, 8)}
                            </code>
                          ) : null}
                          <span>{formatDateTime(entry.createdAt)}</span>
                        </div>
                      </div>
                    ))}
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
              <CardTitle>会话与安全</CardTitle>
              <CardDescription>
                退出登录会向服务端吊销当前设备的 Refresh Token 并清空本地凭证缓存。
              </CardDescription>
            </CardHeader>
            <Separator />
            <CardFooter className="justify-between pt-4">
              <span className="text-xs text-muted-foreground">
                Access Token 有效期 15 分钟，过期时自动轮换续期。
              </span>
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
