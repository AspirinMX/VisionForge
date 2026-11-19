"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  DownloadIcon,
  ExternalLinkIcon,
  KeyRoundIcon,
  LogOutIcon,
  PencilIcon,
  RefreshCwIcon,
  TicketIcon,
  WalletIcon,
} from "lucide-react";
import { toast } from "sonner";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { TablePagination } from "@/components/common/table-pagination";
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
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { useAsyncData } from "@/hooks/use-async-data";
import { accountApi, authApi } from "@/lib/api/endpoints";
import {
  notifyApiError,
  notifyError,
  notifySuccess,
} from "@/lib/api/feedback";
import type { CreditLedgerEntryResponse } from "@/lib/api/types";
import {
  CREDIT_ENTRY_LABEL,
  formatDateTime,
  GENERATION_BILLING_LABEL,
  GENERATION_STATUS_LABEL,
  USER_ROLE_LABEL,
  USER_STATUS_LABEL,
} from "@/lib/format";

const LEDGER_PAGE_SIZE = 50;
const LEDGER_VIEW_SIZE = 10;

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
  const [profileDialogOpen, setProfileDialogOpen] = useState(false);
  const [displayNameDraft, setDisplayNameDraft] = useState("");
  const [savingProfile, setSavingProfile] = useState(false);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [changingPassword, setChangingPassword] = useState(false);
  const [redeemCodeInput, setRedeemCodeInput] = useState("");
  const [redeemingCode, setRedeemingCode] = useState(false);
  const [exportingCsv, setExportingCsv] = useState(false);
  const [ledgerGeneration, setLedgerGeneration] = useState(0);
  const [extraLedger, setExtraLedger] = useState<{
    baseKey: string;
    items: CreditLedgerEntryResponse[];
    hasMore: boolean;
  } | null>(null);
  const [loadingMoreLedger, setLoadingMoreLedger] = useState(false);
  const [ledgerPage, setLedgerPage] = useState(1);

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

  const ledgerTotalPages = Math.max(
    1,
    Math.ceil(allLedgerEntries.length / LEDGER_VIEW_SIZE)
  );
  const safeLedgerPage = Math.min(ledgerPage, ledgerTotalPages);
  const visibleLedgerEntries = allLedgerEntries.slice(
    (safeLedgerPage - 1) * LEDGER_VIEW_SIZE,
    safeLedgerPage * LEDGER_VIEW_SIZE
  );

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

  const handleSaveProfile = async () => {
    const trimmed = displayNameDraft.trim();
    if (!trimmed) {
      notifyError("显示名称不能为空");
      return;
    }
    if (trimmed.length > 100) {
      notifyError("显示名称最多 100 个字符");
      return;
    }
    setSavingProfile(true);
    try {
      await authApi.updateProfile({ displayName: trimmed });
      await refreshUser();
      setProfileDialogOpen(false);
      notifySuccess("个人资料已更新");
    } catch (err) {
      notifyApiError(err, "更新个人资料失败");
    } finally {
      setSavingProfile(false);
    }
  };

  const handleChangePassword = async () => {
    if (!currentPassword || !newPassword) {
      notifyError("请完整填写当前密码和新密码");
      return;
    }
    if (newPassword.length < 12 || newPassword.length > 72) {
      notifyError("新密码长度必须在 12 到 72 个字符之间");
      return;
    }
    if (newPassword !== confirmPassword) {
      notifyError("两次输入的新密码不一致");
      return;
    }
    setChangingPassword(true);
    try {
      await authApi.changePassword({ currentPassword, newPassword });
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      notifySuccess("密码已修改，请使用新密码重新登录");
      await logout();
      router.replace("/login");
    } catch (err) {
      notifyApiError(err, "修改密码失败");
    } finally {
      setChangingPassword(false);
    }
  };

  const handleRedeemCode = async () => {
    const code = redeemCodeInput.trim();
    if (!code) {
      notifyError("请输入兑换码");
      return;
    }
    setRedeemingCode(true);
    try {
      const result = await accountApi.redeemCode({ code });
      setRedeemCodeInput("");
      handleRefreshBilling();
      notifySuccess(
        `成功兑换 +${result.amountUnits} 额度`,
        `当前可用余额：${result.newAvailableUnits}`
      );
    } catch (err) {
      notifyApiError(err, "兑换码无效或已达到限制");
    } finally {
      setRedeemingCode(false);
    }
  };

  const handleExportCsv = async () => {
    setExportingCsv(true);
    try {
      const filename = await accountApi.exportLedgerCsv(2000);
      notifySuccess(`已导出 ${filename}`);
    } catch (err) {
      notifyApiError(err, "导出额度流水失败");
    } finally {
      setExportingCsv(false);
    }
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
              <div className="flex items-center gap-2">
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={() => {
                    setDisplayNameDraft(user?.displayName ?? "");
                    setProfileDialogOpen(true);
                  }}
                >
                  <PencilIcon />
                  <span>修改资料</span>
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  onClick={handleRefreshProfile}
                  disabled={refreshingUser}
                >
                  <RefreshCwIcon
                    className={
                      refreshingUser ? "animate-spin motion-reduce:animate-none" : ""
                    }
                  />
                  <span>刷新信息</span>
                </Button>
              </div>
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
                      loadingCredit || loadingLedger
                        ? "animate-spin motion-reduce:animate-none"
                        : ""
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
                  <Skeleton className="h-20 rounded-surface-md" />
                  <Skeleton className="h-20 rounded-surface-md" />
                  <Skeleton className="h-20 rounded-surface-md" />
                </div>
              ) : (
                <div className="grid gap-3 sm:grid-cols-3">
                  <div className="space-y-1 p-3.5">
                    <p className="text-xs text-muted-foreground">可用余额</p>
                    <p className="mt-1 text-xl font-semibold tabular-nums">
                      {credit?.availableUnits ?? 0}
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground tabular-nums">
                      总余额 {credit?.balanceUnits ?? 0} · 更新于{" "}
                      {formatDateTime(credit?.updatedAt)}
                    </p>
                  </div>
                  <div className="space-y-1 p-3.5">
                    <p className="text-xs text-muted-foreground">当前预占中</p>
                    <p className="mt-1 text-xl font-semibold tabular-nums">
                      {credit?.reservedUnits ?? 0}
                    </p>
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      进行中任务冻结的额度
                    </p>
                  </div>
                  <div className="space-y-1 p-3.5">
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

              <div className="rounded-surface-sm bg-muted/20 p-4">
                <div className="flex flex-col gap-3 sm:flex-row sm:items-end">
                  <div className="flex-1 space-y-1.5">
                    <Label
                      htmlFor="redeem-code-input"
                      className="flex items-center gap-1.5 text-xs font-medium"
                    >
                      <TicketIcon className="size-3.5" />
                      <span>使用兑换码（CDK）充值额度</span>
                    </Label>
                    <Input
                      id="redeem-code-input"
                      value={redeemCodeInput}
                      onChange={(e) => setRedeemCodeInput(e.target.value)}
                      placeholder="输入兑换码，例如 VF-XXXX-XXXX"
                      onKeyDown={(e) => {
                        if (e.key === "Enter") {
                          void handleRedeemCode();
                        }
                      }}
                    />
                  </div>
                  <Button
                    type="button"
                    disabled={redeemingCode || !redeemCodeInput.trim()}
                    onClick={() => void handleRedeemCode()}
                  >
                    <TicketIcon />
                    <span>{redeemingCode ? "兑换中..." : "立即兑换"}</span>
                  </Button>
                </div>
              </div>

              <Separator />

              <div className="space-y-2.5">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <h3 className="text-sm font-medium">消费流水</h3>
                  <div className="flex items-center gap-2">
                    <span className="text-xs text-muted-foreground tabular-nums">
                      已加载 {allLedgerEntries.length} 条
                    </span>
                    <Button
                      type="button"
                      variant="outline"
                      size="xs"
                      disabled={exportingCsv}
                      onClick={() => void handleExportCsv()}
                    >
                      <DownloadIcon />
                      <span>{exportingCsv ? "导出中..." : "导出 CSV"}</span>
                    </Button>
                  </div>
                </div>
                <ApiErrorAlert error={ledgerError} title="读取额度流水失败" />
                {loadingLedger && !ledger ? (
                  <Skeleton className="h-24 rounded-surface-md" />
                ) : allLedgerEntries.length === 0 ? (
                  <p className="rounded-surface-sm bg-muted/20 px-3.5 py-6 text-center text-xs text-muted-foreground">
                    暂无额度流水；首次生成或访问本页后会出现初始授予记录。
                  </p>
                ) : (
                  <div className="space-y-2">
                    <div className="divide-y rounded-surface-sm border">
                      {visibleLedgerEntries.map((entry) => (
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
                            <span className="font-medium tabular-nums">
                              {entry.amountUnits}
                            </span>
                            {entry.remark ? (
                              <span className="text-foreground/80">
                                · {entry.remark}
                              </span>
                            ) : null}
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
                    <TablePagination
                      page={safeLedgerPage}
                      pageSize={LEDGER_VIEW_SIZE}
                      total={allLedgerEntries.length}
                      onPageChange={setLedgerPage}
                      itemLabel="条流水"
                    />
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
                  近期任务额度消耗
                </h3>
                <ApiErrorAlert error={usageError} title="读取近期任务汇总失败" />
                {loadingUsage && !usage ? (
                  <Skeleton className="h-16 rounded-surface-md" />
                ) : (usage?.buckets ?? []).length === 0 ? (
                  <p className="text-xs text-muted-foreground">
                    尚无媒体生成任务。
                  </p>
                ) : (
                  <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
                    {(usage?.buckets ?? []).map((bucket) => (
                      <div
                        key={bucket.billingStatus}
                        className="rounded-surface-sm bg-muted/20 p-3"
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
              <CardTitle className="flex items-center gap-2">
                <KeyRoundIcon className="size-4" />
                <span>修改密码</span>
              </CardTitle>
              <CardDescription>
                修改登录密码需 12-72 个字符；修改后将吊销所有活跃会话并需重新登录。
              </CardDescription>
            </CardHeader>
            <CardContent className="grid gap-3 sm:grid-cols-3">
              <div className="space-y-1.5">
                <Label htmlFor="pwd-current">当前密码</Label>
                <Input
                  id="pwd-current"
                  type="password"
                  value={currentPassword}
                  onChange={(e) => setCurrentPassword(e.target.value)}
                  autoComplete="current-password"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="pwd-new">新密码（12-72 位）</Label>
                <Input
                  id="pwd-new"
                  type="password"
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  autoComplete="new-password"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="pwd-confirm">确认新密码</Label>
                <Input
                  id="pwd-confirm"
                  type="password"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  autoComplete="new-password"
                />
              </div>
            </CardContent>
            <CardFooter className="justify-end border-t pt-4">
              <Button
                type="button"
                disabled={
                  changingPassword ||
                  !currentPassword ||
                  !newPassword ||
                  !confirmPassword
                }
                onClick={() => void handleChangePassword()}
              >
                <KeyRoundIcon />
                <span>{changingPassword ? "提交中..." : "更新密码"}</span>
              </Button>
            </CardFooter>
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

      <Dialog open={profileDialogOpen} onOpenChange={setProfileDialogOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>修改个人资料</DialogTitle>
            <DialogDescription>更新您的公开显示名称（最多 100 个字符）。</DialogDescription>
          </DialogHeader>
          <div className="space-y-1.5 py-1">
            <Label htmlFor="profile-display-name">显示名称</Label>
            <Input
              id="profile-display-name"
              maxLength={100}
              value={displayNameDraft}
              onChange={(e) => setDisplayNameDraft(e.target.value)}
            />
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={savingProfile}
              onClick={() => setProfileDialogOpen(false)}
            >
              取消
            </Button>
            <Button
              type="button"
              disabled={savingProfile || !displayNameDraft.trim()}
              onClick={() => void handleSaveProfile()}
            >
              {savingProfile ? "保存中..." : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
