"use client";

import { useMemo, useState } from "react";
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
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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
import { generationApi } from "@/lib/api/endpoints";
import { copyToClipboard, notifyApiError } from "@/lib/api/feedback";
import {
  formatDateTime,
  USER_ROLE_LABEL,
  USER_STATUS_LABEL,
} from "@/lib/format";

export default function AccountPage() {
  const router = useRouter();
  const { user, refreshUser, logout } = useAuth();

  const [refreshingUser, setRefreshingUser] = useState(false);
  const [copiedId, setCopiedId] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);

  const {
    data: tasksData,
    loading: loadingTasks,
    error: tasksError,
  } = useAsyncData(
    () => generationApi.list({ limit: 100 }),
    user ? `account-tasks:${user.id}` : "account-tasks:anon",
    {
      enabled: Boolean(user),
      errorMessage: "加载最近任务额度统计失败",
    }
  );

  const tasks = useMemo(() => tasksData ?? [], [tasksData]);

  const creditStats = useMemo(() => {
    let settledUnits = 0;
    let reservedUnits = 0;
    let pendingReconcileUnits = 0;
    let settledCount = 0;
    let reservedCount = 0;

    for (const task of tasks) {
      if (task.billingStatus === "SETTLED") {
        settledUnits += task.priceUnits;
        settledCount += 1;
      } else if (task.billingStatus === "RESERVED") {
        reservedUnits += task.priceUnits;
        reservedCount += 1;
      } else if (task.billingStatus === "PENDING_RECONCILIATION") {
        pendingReconcileUnits += task.priceUnits;
      }
    }
    return {
      settledUnits,
      reservedUnits,
      pendingReconcileUnits,
      settledCount,
      reservedCount,
    };
  }, [tasks]);

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
                <Badge variant="outline">余额接口占位</Badge>
              </div>
            </CardHeader>

            <CardContent className="space-y-4">
              <Alert>
                <AlertTitle>实时余额查询接口暂未暴露（前端占位状态）</AlertTitle>
                <AlertDescription>
                  后端 billing 模块已实现额度预占与结算账本，新注册账户初始额度由服务端环境变量{" "}
                  <code className="font-mono text-xs">
                    BILLING_INITIAL_CREDIT_UNITS
                  </code>
                  （默认 10000）授予；当前尚未开放余额及流水查询 API，提交生成时如余额不足会由服务端直接拦截提示。
                </AlertDescription>
              </Alert>

              <ApiErrorAlert error={tasksError} title="读取近期任务记录失败" />

              {loadingTasks && !tasksData ? (
                <div className="grid gap-3 sm:grid-cols-3">
                  <Skeleton className="h-20 rounded-2xl" />
                  <Skeleton className="h-20 rounded-2xl" />
                  <Skeleton className="h-20 rounded-2xl" />
                </div>
              ) : (
                <div className="grid gap-3 sm:grid-cols-3">
                  <div className="rounded-2xl border p-3.5">
                    <p className="text-xs text-muted-foreground">
                      近 {tasks.length} 条已结算额度
                    </p>
                    <p className="mt-1 text-xl font-semibold tabular-nums">
                      {creditStats.settledUnits}
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground tabular-nums">
                      共 {creditStats.settledCount} 个已交付任务
                    </p>
                  </div>
                  <div className="rounded-2xl border p-3.5">
                    <p className="text-xs text-muted-foreground">当前预占中</p>
                    <p className="mt-1 text-xl font-semibold tabular-nums">
                      {creditStats.reservedUnits}
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground tabular-nums">
                      {creditStats.reservedCount} 个进行中/待归档任务
                    </p>
                  </div>
                  <div className="rounded-2xl border p-3.5">
                    <p className="text-xs text-muted-foreground">待核账挂起</p>
                    <p className="mt-1 text-xl font-semibold tabular-nums">
                      {creditStats.pendingReconcileUnits}
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      UNKNOWN 状态任务预占保留
                    </p>
                  </div>
                </div>
              )}
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
