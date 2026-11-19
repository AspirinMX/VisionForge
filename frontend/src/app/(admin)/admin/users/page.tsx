"use client";

import { useState } from "react";
import {
  ChevronLeftIcon,
  ChevronRightIcon,
  CoinsIcon,
  KeyRoundIcon,
  MoreHorizontalIcon,
  PlusIcon,
  RefreshCwIcon,
  SearchIcon,
  ShieldBanIcon,
  UserCheckIcon,
  UserXIcon,
} from "lucide-react";
import { toast } from "sonner";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { PageHeader } from "@/components/layout/page-header";
import { useAuth } from "@/components/providers/auth-provider";
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
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
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
import { createIdempotencyKey } from "@/lib/api/client";
import { adminBillingApi, adminUsersApi } from "@/lib/api/endpoints";
import { notifyApiError, notifyError } from "@/lib/api/feedback";
import type { UserResponse, UserRole, UserStatus } from "@/lib/api/types";
import {
  formatDateTime,
  USER_ROLE_LABEL,
  USER_STATUS_LABEL,
} from "@/lib/format";

const PAGE_SIZE = 20;

interface PendingRoleChange {
  user: UserResponse;
  nextRole: UserRole;
}

interface PendingStatusChange {
  user: UserResponse;
  nextStatus: UserStatus;
}

function UsersAdminContent() {
  const { user: currentUser } = useAuth();
  const [page, setPage] = useState(1);
  const [searchInput, setSearchInput] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");
  const [roleFilter, setRoleFilter] = useState<UserRole | "ALL">("ALL");
  const [statusFilter, setStatusFilter] = useState<UserStatus | "ALL">("ALL");

  const [pendingRoleChange, setPendingRoleChange] =
    useState<PendingRoleChange | null>(null);
  const [updatingRole, setUpdatingRole] = useState(false);

  const [pendingStatusChange, setPendingStatusChange] =
    useState<PendingStatusChange | null>(null);
  const [updatingStatus, setUpdatingStatus] = useState(false);

  const [revokeTarget, setRevokeTarget] = useState<UserResponse | null>(null);
  const [revokingSessions, setRevokingSessions] = useState(false);

  // Create user dialog
  const [createOpen, setCreateOpen] = useState(false);
  const [createEmail, setCreateEmail] = useState("");
  const [createDisplayName, setCreateDisplayName] = useState("");
  const [createPassword, setCreatePassword] = useState("");
  const [createRole, setCreateRole] = useState<UserRole>("USER");
  const [creatingUser, setCreatingUser] = useState(false);

  // Reset password dialog
  const [resetTarget, setResetTarget] = useState<UserResponse | null>(null);
  const [resetPasswordValue, setResetPasswordValue] = useState("");
  const [resettingPassword, setResettingPassword] = useState(false);

  // Adjust credit dialog
  const [creditTarget, setCreditTarget] = useState<UserResponse | null>(null);
  const [creditDelta, setCreditDelta] = useState("100");
  const [creditRemark, setCreditRemark] = useState("");
  const [adjustingCredit, setAdjustingCredit] = useState(false);

  const { data: result, loading, error, reload } = useAsyncData(
    () =>
      adminUsersApi.list({
        page,
        size: PAGE_SIZE,
        q: appliedQuery.trim() || undefined,
        role: roleFilter === "ALL" ? undefined : roleFilter,
        status: statusFilter === "ALL" ? undefined : statusFilter,
      }),
    `admin-users:${page}:${appliedQuery.trim()}:${roleFilter}:${statusFilter}`,
    { errorMessage: "加载用户列表失败" }
  );

  const confirmRoleChange = async () => {
    if (!pendingRoleChange || updatingRole) {
      return;
    }
    setUpdatingRole(true);
    try {
      const updated = await adminUsersApi.changeRole(
        pendingRoleChange.user.id,
        { role: pendingRoleChange.nextRole }
      );
      toast.success(
        `已将用户「${updated.displayName}」的角色更新为 ${USER_ROLE_LABEL[updated.role]}`
      );
      setPendingRoleChange(null);
      reload();
    } catch (err) {
      notifyApiError(err, "更新用户角色失败");
    } finally {
      setUpdatingRole(false);
    }
  };

  const confirmStatusChange = async () => {
    if (!pendingStatusChange || updatingStatus) {
      return;
    }
    setUpdatingStatus(true);
    try {
      const updated = await adminUsersApi.changeStatus(
        pendingStatusChange.user.id,
        { status: pendingStatusChange.nextStatus }
      );
      toast.success(
        `用户「${updated.displayName}」状态已切换为 ${USER_STATUS_LABEL[updated.status]}`
      );
      setPendingStatusChange(null);
      reload();
    } catch (err) {
      notifyApiError(err, "更新用户状态失败");
    } finally {
      setUpdatingStatus(false);
    }
  };

  const confirmRevokeSessions = async () => {
    if (!revokeTarget || revokingSessions) {
      return;
    }
    setRevokingSessions(true);
    try {
      const res = await adminUsersApi.revokeSessions(revokeTarget.id);
      toast.success(
        `已吊销「${revokeTarget.displayName}」的 ${res.revokedCount} 个活跃会话令牌`
      );
      setRevokeTarget(null);
    } catch (err) {
      notifyApiError(err, "吊销用户会话失败");
    } finally {
      setRevokingSessions(false);
    }
  };

  const handleCreateUser = async () => {
    const email = createEmail.trim();
    const displayName = createDisplayName.trim();
    if (!email || !displayName || !createPassword) {
      notifyError("请完整填写邮箱、显示名称和初始密码");
      return;
    }
    if (createPassword.length < 12 || createPassword.length > 72) {
      notifyError("初始密码长度必须在 12 到 72 个字符之间");
      return;
    }
    setCreatingUser(true);
    try {
      const created = await adminUsersApi.create({
        email,
        displayName,
        password: createPassword,
        role: createRole,
      });
      toast.success(`已创建用户「${created.displayName}」`);
      setCreateOpen(false);
      setCreateEmail("");
      setCreateDisplayName("");
      setCreatePassword("");
      setCreateRole("USER");
      setPage(1);
      reload();
    } catch (err) {
      notifyApiError(err, "创建用户失败");
    } finally {
      setCreatingUser(false);
    }
  };

  const handleResetPassword = async () => {
    if (!resetTarget || resettingPassword) {
      return;
    }
    if (resetPasswordValue.length < 12 || resetPasswordValue.length > 72) {
      notifyError("新密码长度必须在 12 到 72 个字符之间");
      return;
    }
    setResettingPassword(true);
    try {
      await adminUsersApi.resetPassword(resetTarget.id, {
        newPassword: resetPasswordValue,
      });
      toast.success(`已重置「${resetTarget.displayName}」的密码并吊销其旧会话`);
      setResetTarget(null);
      setResetPasswordValue("");
    } catch (err) {
      notifyApiError(err, "重置密码失败");
    } finally {
      setResettingPassword(false);
    }
  };

  const handleAdjustCredit = async () => {
    if (!creditTarget || adjustingCredit) {
      return;
    }
    const parsedDelta = Number(creditDelta.trim());
    if (!Number.isInteger(parsedDelta) || parsedDelta === 0) {
      notifyError("调整额度必须为非零整数（正数为增配，负数为扣减）");
      return;
    }
    setAdjustingCredit(true);
    try {
      const entry = await adminBillingApi.adjustCredit(creditTarget.id, {
        deltaUnits: parsedDelta,
        idempotencyKey: `admin-ui:${createIdempotencyKey()}`,
        remark: creditRemark.trim() || undefined,
      });
      toast.success(
        `已为「${creditTarget.displayName}」调账 ${entry.amountUnits > 0 ? `+${entry.amountUnits}` : entry.amountUnits} 额度`
      );
      setCreditTarget(null);
      setCreditRemark("");
    } catch (err) {
      notifyApiError(err, "调整用户额度失败");
    } finally {
      setAdjustingCredit(false);
    }
  };

  const totalPages = result
    ? Math.max(1, Math.ceil(result.total / result.size))
    : 1;

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="用户管理"
        actions={
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={reload}
              disabled={loading}
              aria-label="刷新用户列表"
            >
              <RefreshCwIcon
                className={loading ? "animate-spin motion-reduce:animate-none" : ""}
              />
            </Button>
            <Button type="button" size="sm" onClick={() => setCreateOpen(true)}>
              <PlusIcon />
              <span>新建用户</span>
            </Button>
          </div>
        }
      />

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto w-full max-w-7xl space-y-4">
          {/* Search & filter bar */}
          <div className="flex flex-wrap items-center gap-2">
            <div className="relative flex min-w-56 flex-1 items-center sm:max-w-xs">
              <SearchIcon className="pointer-events-none absolute left-2.5 size-3.5 text-muted-foreground" />
              <Input
                value={searchInput}
                onChange={(e) => setSearchInput(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    setPage(1);
                    setAppliedQuery(searchInput);
                  }
                }}
                placeholder="搜索邮箱或显示名称..."
                className="h-9 pl-8"
              />
            </div>

            <Select
              value={roleFilter}
              items={{
                ALL: "全部角色",
                USER: USER_ROLE_LABEL.USER,
                ADMIN: USER_ROLE_LABEL.ADMIN,
              }}
              onValueChange={(v) => {
                if (v) {
                  setPage(1);
                  setRoleFilter(v as UserRole | "ALL");
                }
              }}
            >
              <SelectTrigger className="w-32">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部角色</SelectItem>
                <SelectItem value="USER">{USER_ROLE_LABEL.USER}</SelectItem>
                <SelectItem value="ADMIN">{USER_ROLE_LABEL.ADMIN}</SelectItem>
              </SelectContent>
            </Select>

            <Select
              value={statusFilter}
              items={{
                ALL: "全部状态",
                ACTIVE: USER_STATUS_LABEL.ACTIVE,
                DISABLED: USER_STATUS_LABEL.DISABLED,
              }}
              onValueChange={(v) => {
                if (v) {
                  setPage(1);
                  setStatusFilter(v as UserStatus | "ALL");
                }
              }}
            >
              <SelectTrigger className="w-32">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部状态</SelectItem>
                <SelectItem value="ACTIVE">
                  {USER_STATUS_LABEL.ACTIVE}
                </SelectItem>
                <SelectItem value="DISABLED">
                  {USER_STATUS_LABEL.DISABLED}
                </SelectItem>
              </SelectContent>
            </Select>

            <Button
              type="button"
              variant="secondary"
              size="sm"
              onClick={() => {
                setPage(1);
                setAppliedQuery(searchInput);
              }}
            >
              搜索
            </Button>

            {(appliedQuery || roleFilter !== "ALL" || statusFilter !== "ALL") ? (
              <Button
                type="button"
                variant="ghost"
                size="sm"
                onClick={() => {
                  setSearchInput("");
                  setAppliedQuery("");
                  setRoleFilter("ALL");
                  setStatusFilter("ALL");
                  setPage(1);
                }}
              >
                重置
              </Button>
            ) : null}
          </div>

          <ApiErrorAlert error={error} title="用户列表加载失败" />

          {loading && !result ? (
            <Skeleton className="h-72 w-full rounded-2xl" />
          ) : !result || result.items.length === 0 ? (
            <div className="rounded-2xl border border-dashed p-10 text-center text-sm text-muted-foreground">
              暂无符合条件的用户记录。
            </div>
          ) : (
            <>
              <div className="rounded-2xl border bg-card">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>用户</TableHead>
                      <TableHead>邮箱</TableHead>
                      <TableHead>状态</TableHead>
                      <TableHead>角色</TableHead>
                      <TableHead>注册时间</TableHead>
                      <TableHead className="text-right">操作</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {result.items.map((item) => {
                      const isSelf = item.id === currentUser?.id;
                      const nextStatus: UserStatus =
                        item.status === "ACTIVE" ? "DISABLED" : "ACTIVE";
                      return (
                        <TableRow key={item.id}>
                          <TableCell>
                            <div className="flex items-center gap-2">
                              <span className="font-medium">
                                {item.displayName}
                              </span>
                              {isSelf ? (
                                <Badge variant="secondary">当前登录</Badge>
                              ) : null}
                            </div>
                          </TableCell>
                          <TableCell>{item.email}</TableCell>
                          <TableCell>
                            <Badge
                              variant={
                                item.status === "ACTIVE"
                                  ? "outline"
                                  : "destructive"
                              }
                            >
                              {USER_STATUS_LABEL[item.status]}
                            </Badge>
                          </TableCell>
                          <TableCell>
                            <Select
                              value={item.role}
                              items={USER_ROLE_LABEL}
                              onValueChange={(value) => {
                                if (value && value !== item.role) {
                                  setPendingRoleChange({
                                    user: item,
                                    nextRole: value as UserRole,
                                  });
                                }
                              }}
                            >
                              <SelectTrigger className="h-8 w-28">
                                <SelectValue />
                              </SelectTrigger>
                              <SelectContent>
                                <SelectItem value="USER">
                                  {USER_ROLE_LABEL.USER}
                                </SelectItem>
                                <SelectItem value="ADMIN">
                                  {USER_ROLE_LABEL.ADMIN}
                                </SelectItem>
                              </SelectContent>
                            </Select>
                          </TableCell>
                          <TableCell className="text-xs text-muted-foreground tabular-nums">
                            {formatDateTime(item.createdAt)}
                          </TableCell>
                          <TableCell className="text-right">
                            <div className="inline-flex items-center justify-end gap-1">
                              <Button
                                type="button"
                                variant="outline"
                                size="xs"
                                onClick={() => {
                                  setCreditTarget(item);
                                  setCreditDelta("100");
                                  setCreditRemark("");
                                }}
                              >
                                <CoinsIcon />
                                <span>调额</span>
                              </Button>

                              <DropdownMenu>
                                <DropdownMenuTrigger
                                  render={
                                    <Button
                                      type="button"
                                      variant="ghost"
                                      size="icon-xs"
                                      aria-label={`操作 ${item.displayName}`}
                                    />
                                  }
                                >
                                  <MoreHorizontalIcon />
                                </DropdownMenuTrigger>
                                <DropdownMenuContent align="end">
                                  <DropdownMenuItem
                                    onClick={() => {
                                      setResetTarget(item);
                                      setResetPasswordValue("");
                                    }}
                                  >
                                    <KeyRoundIcon />
                                    <span>重置密码</span>
                                  </DropdownMenuItem>
                                  <DropdownMenuItem
                                    onClick={() => setRevokeTarget(item)}
                                  >
                                    <ShieldBanIcon />
                                    <span>强制下线（吊销会话）</span>
                                  </DropdownMenuItem>
                                  <DropdownMenuSeparator />
                                  <DropdownMenuItem
                                    disabled={isSelf}
                                    variant={
                                      nextStatus === "DISABLED"
                                        ? "destructive"
                                        : "default"
                                    }
                                    onClick={() =>
                                      setPendingStatusChange({
                                        user: item,
                                        nextStatus,
                                      })
                                    }
                                  >
                                    {nextStatus === "DISABLED" ? (
                                      <UserXIcon />
                                    ) : (
                                      <UserCheckIcon />
                                    )}
                                    <span>
                                      {isSelf
                                        ? "不可禁用当前登录账号"
                                        : nextStatus === "DISABLED"
                                          ? "禁用账号"
                                          : "重新启用"}
                                    </span>
                                  </DropdownMenuItem>
                                </DropdownMenuContent>
                              </DropdownMenu>
                            </div>
                          </TableCell>
                        </TableRow>
                      );
                    })}
                  </TableBody>
                </Table>
              </div>

              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span className="tabular-nums">
                  第 {result.page} / {totalPages} 页 · 共 {result.total} 位用户
                </span>
                <div className="flex items-center gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setPage((p) => Math.max(1, p - 1))}
                    disabled={page <= 1 || loading}
                  >
                    <ChevronLeftIcon />
                    <span>上一页</span>
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    onClick={() => setPage((p) => Math.min(totalPages, p + 1))}
                    disabled={page >= totalPages || loading}
                  >
                    <span>下一页</span>
                    <ChevronRightIcon />
                  </Button>
                </div>
              </div>
            </>
          )}
        </div>
      </div>

      {/* Role Confirm */}
      <AlertDialog
        open={pendingRoleChange !== null}
        onOpenChange={(open) => {
          if (!open && !updatingRole) {
            setPendingRoleChange(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认变更用户角色</AlertDialogTitle>
            <AlertDialogDescription>
              确定将「{pendingRoleChange?.user.displayName}」（
              {pendingRoleChange?.user.email}）的系统角色修改为{" "}
              <strong>
                {pendingRoleChange
                  ? USER_ROLE_LABEL[pendingRoleChange.nextRole]
                  : ""}
              </strong>
              ？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={updatingRole}>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                event.preventDefault();
                void confirmRoleChange();
              }}
              disabled={updatingRole}
            >
              {updatingRole ? "更新中..." : "确认修改"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Status Confirm */}
      <AlertDialog
        open={pendingStatusChange !== null}
        onOpenChange={(open) => {
          if (!open && !updatingStatus) {
            setPendingStatusChange(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {pendingStatusChange?.nextStatus === "DISABLED"
                ? "确认禁用用户账号"
                : "确认启用用户账号"}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {pendingStatusChange?.nextStatus === "DISABLED"
                ? `禁用「${pendingStatusChange?.user.displayName}」后，系统将立即吊销其全部刷新令牌并阻止新登录。`
                : `确定恢复启用「${pendingStatusChange?.user.displayName}」的登录权限？`}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={updatingStatus}>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                event.preventDefault();
                void confirmStatusChange();
              }}
              disabled={updatingStatus}
            >
              {updatingStatus ? "处理中..." : "确认操作"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Revoke Sessions Confirm */}
      <AlertDialog
        open={revokeTarget !== null}
        onOpenChange={(open) => {
          if (!open && !revokingSessions) {
            setRevokeTarget(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>强制吊销活跃会话</AlertDialogTitle>
            <AlertDialogDescription>
              确定吊销「{revokeTarget?.displayName}」的全部刷新令牌？该用户在当前访问令牌过期后必须重新登录。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={revokingSessions}>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                event.preventDefault();
                void confirmRevokeSessions();
              }}
              disabled={revokingSessions}
            >
              {revokingSessions ? "吊销中..." : "确认吊销"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Create User Dialog */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>管理员创建用户</DialogTitle>
            <DialogDescription>
              创建新账号并分配初始系统角色（密码需 12-72 个字符）。
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 py-1">
            <div className="space-y-1.5">
              <Label htmlFor="admin-create-email">邮箱</Label>
              <Input
                id="admin-create-email"
                type="email"
                value={createEmail}
                onChange={(e) => setCreateEmail(e.target.value)}
                placeholder="operator@example.com"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="admin-create-name">显示名称</Label>
              <Input
                id="admin-create-name"
                value={createDisplayName}
                onChange={(e) => setCreateDisplayName(e.target.value)}
                placeholder="例如：创作成员 A"
              />
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="admin-create-password">初始密码（12-72 位）</Label>
              <Input
                id="admin-create-password"
                type="password"
                value={createPassword}
                onChange={(e) => setCreatePassword(e.target.value)}
              />
            </div>
            <div className="space-y-1.5">
              <Label>系统角色</Label>
              <Select
                value={createRole}
                items={USER_ROLE_LABEL}
                onValueChange={(v) => {
                  if (v) {
                    setCreateRole(v as UserRole);
                  }
                }}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="USER">{USER_ROLE_LABEL.USER}</SelectItem>
                  <SelectItem value="ADMIN">{USER_ROLE_LABEL.ADMIN}</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={creatingUser}
              onClick={() => setCreateOpen(false)}
            >
              取消
            </Button>
            <Button
              type="button"
              disabled={creatingUser}
              onClick={() => void handleCreateUser()}
            >
              {creatingUser ? "创建中..." : "创建账号"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Reset Password Dialog */}
      <Dialog
        open={resetTarget !== null}
        onOpenChange={(open) => {
          if (!open && !resettingPassword) {
            setResetTarget(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>重置登录密码</DialogTitle>
            <DialogDescription>
              为「{resetTarget?.displayName}」设置新密码（12-72 位），同时强制吊销其历史刷新令牌。
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-1.5 py-1">
            <Label htmlFor="admin-reset-password">新密码</Label>
            <Input
              id="admin-reset-password"
              type="password"
              value={resetPasswordValue}
              onChange={(e) => setResetPasswordValue(e.target.value)}
            />
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={resettingPassword}
              onClick={() => setResetTarget(null)}
            >
              取消
            </Button>
            <Button
              type="button"
              disabled={resettingPassword || resetPasswordValue.length < 12}
              onClick={() => void handleResetPassword()}
            >
              {resettingPassword ? "提交中..." : "确认重置"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Credit Adjust Dialog */}
      <Dialog
        open={creditTarget !== null}
        onOpenChange={(open) => {
          if (!open && !adjustingCredit) {
            setCreditTarget(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>人工调整用户额度</DialogTitle>
            <DialogDescription>
              向「{creditTarget?.displayName}」（{creditTarget?.email}）追加或扣减可用额度，流水类型记为 ADJUST。
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3 py-1">
            <div className="space-y-1.5">
              <Label htmlFor="admin-credit-delta">变更额度（整数，正增负减）</Label>
              <Input
                id="admin-credit-delta"
                type="number"
                step={1}
                value={creditDelta}
                onChange={(e) => setCreditDelta(e.target.value)}
              />
              <div className="flex flex-wrap gap-1.5 pt-1">
                {["+100", "+500", "+1000", "-100"].map((preset) => (
                  <Button
                    key={preset}
                    type="button"
                    size="xs"
                    variant="outline"
                    onClick={() => setCreditDelta(preset.replace("+", ""))}
                  >
                    {preset}
                  </Button>
                ))}
              </div>
            </div>
            <div className="space-y-1.5">
              <Label htmlFor="admin-credit-remark">调账备注（选填）</Label>
              <Input
                id="admin-credit-remark"
                maxLength={255}
                value={creditRemark}
                onChange={(e) => setCreditRemark(e.target.value)}
                placeholder="例如：测试补发 / 工单退费"
              />
            </div>
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={adjustingCredit}
              onClick={() => setCreditTarget(null)}
            >
              取消
            </Button>
            <Button
              type="button"
              disabled={adjustingCredit}
              onClick={() => void handleAdjustCredit()}
            >
              {adjustingCredit ? "记账中..." : "确认调账"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}

export default function AdminUsersPage() {
  return <UsersAdminContent />;
}
