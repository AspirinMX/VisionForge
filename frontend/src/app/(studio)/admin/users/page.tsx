"use client";

import { useState } from "react";
import { ChevronLeftIcon, ChevronRightIcon, RefreshCwIcon } from "lucide-react";
import { toast } from "sonner";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { AdminRoleGuard } from "@/components/common/auth-guard";
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
import { adminUsersApi } from "@/lib/api/endpoints";
import { notifyApiError } from "@/lib/api/feedback";
import type { UserResponse, UserRole } from "@/lib/api/types";
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

function UsersAdminContent() {
  const { user: currentUser } = useAuth();
  const [page, setPage] = useState(1);
  const [pendingChange, setPendingChange] = useState<PendingRoleChange | null>(
    null
  );
  const [updatingRole, setUpdatingRole] = useState(false);

  const { data: result, loading, error, reload } = useAsyncData(
    () => adminUsersApi.list({ page, size: PAGE_SIZE }),
    `admin-users:${page}`,
    { errorMessage: "加载用户列表失败" }
  );

  const confirmRoleChange = async () => {
    if (!pendingChange || updatingRole) {
      return;
    }
    setUpdatingRole(true);
    try {
      const updated = await adminUsersApi.changeRole(pendingChange.user.id, {
        role: pendingChange.nextRole,
      });
      toast.success(
        `已将用户「${updated.displayName}」的角色更新为 ${USER_ROLE_LABEL[updated.role]}`
      );
      setPendingChange(null);
      reload();
    } catch (err) {
      notifyApiError(err, "更新用户角色失败");
    } finally {
      setUpdatingRole(false);
    }
  };

  const totalPages = result
    ? Math.max(1, Math.ceil(result.total / result.size))
    : 1;

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="用户与角色管理"
        subtitle="管理系统账号与运维角色（ADMIN 不具备跨用户内容访问权限）"
        actions={
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            onClick={reload}
            disabled={loading}
            aria-label="刷新用户列表"
          >
            <RefreshCwIcon className={loading ? "animate-spin" : ""} />
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto w-full max-w-6xl space-y-4">
          <ApiErrorAlert error={error} title="用户列表加载失败" />

          {loading && !result ? (
            <Skeleton className="h-72 w-full rounded-2xl" />
          ) : !result || result.items.length === 0 ? (
            <div className="rounded-2xl border border-dashed p-10 text-center text-sm text-muted-foreground">
              暂无注册用户记录。
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
                      <TableHead>注册时间</TableHead>
                      <TableHead className="text-right">角色调整</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {result.items.map((item) => {
                      const isSelf = item.id === currentUser?.id;
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
                            <p className="mt-0.5 font-mono text-xs text-muted-foreground">
                              {item.id}
                            </p>
                          </TableCell>
                          <TableCell>{item.email}</TableCell>
                          <TableCell>
                            <Badge variant="outline">
                              {USER_STATUS_LABEL[item.status]}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-xs text-muted-foreground tabular-nums">
                            {formatDateTime(item.createdAt)}
                          </TableCell>
                          <TableCell className="text-right">
                            <div className="inline-flex justify-end">
                              <Select
                                value={item.role}
                                onValueChange={(value) => {
                                  if (value && value !== item.role) {
                                    setPendingChange({
                                      user: item,
                                      nextRole: value as UserRole,
                                    });
                                  }
                                }}
                              >
                                <SelectTrigger className="w-32">
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

      <AlertDialog
        open={pendingChange !== null}
        onOpenChange={(open) => {
          if (!open && !updatingRole) {
            setPendingChange(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>确认变更用户角色</AlertDialogTitle>
            <AlertDialogDescription>
              确定将「{pendingChange?.user.displayName}」（
              {pendingChange?.user.email}）的系统角色修改为{" "}
              <strong>
                {pendingChange
                  ? USER_ROLE_LABEL[pendingChange.nextRole]
                  : ""}
              </strong>
              ？系统管理员角色仅授予 `/api/v1/admin/**`
              运维接口权限，不包含跨用户会话或素材访问权限。
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
    </div>
  );
}

export default function AdminUsersPage() {
  return (
    <AdminRoleGuard>
      <UsersAdminContent />
    </AdminRoleGuard>
  );
}
