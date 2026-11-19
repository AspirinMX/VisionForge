"use client";

import { useState } from "react";
import {
  ChevronLeftIcon,
  ChevronRightIcon,
  DownloadIcon,
  RefreshCwIcon,
} from "lucide-react";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
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
import { adminAuditLogsApi } from "@/lib/api/endpoints";
import { notifyApiError, notifyError, notifySuccess } from "@/lib/api/feedback";
import type { AuditLogResponse } from "@/lib/api/types";
import { formatDateTime } from "@/lib/format";

const PAGE_SIZE = 30;
const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function formatJsonMaybe(raw: string | null): string {
  if (!raw) {
    return "—";
  }
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function toIsoMaybe(localValue: string): string | undefined {
  const trimmed = localValue.trim();
  if (!trimmed) {
    return undefined;
  }
  const date = new Date(trimmed);
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString();
}

const AUDIT_TYPE_LABEL: Record<string, string> = {
  IDENTITY: "身份与权限",
  USER: "用户管理",
  MODEL: "模型配置",
  BILLING: "计费与额度",
  CONFIG: "系统参数",
  SECURITY: "安全事件",
};

const AUDIT_SUBTYPE_LABEL: Record<string, string> = {
  LOGIN: "登录",
  ROLE_CHANGE: "角色变更",
  STATUS_CHANGE: "状态变更",
  PASSWORD_RESET: "重置密码",
  CREDIT_ADJUST: "额度调整",
  CONFIG_PUBLISH: "参数发布",
  MODEL_UPDATE: "模型更新",
};

function auditTypeLabel(type: string): string {
  return AUDIT_TYPE_LABEL[type] ?? "其他操作";
}

function auditSubTypeLabel(subType: string): string {
  return AUDIT_SUBTYPE_LABEL[subType] ?? "明细";
}

export default function AdminAuditLogsPage() {
  const [page, setPage] = useState(1);

  const [typeInput, setTypeInput] = useState("");
  const [subTypeInput, setSubTypeInput] = useState("");
  const [bizNoInput, setBizNoInput] = useState("");
  const [traceIdInput, setTraceIdInput] = useState("");
  const [operatorIdInput, setOperatorIdInput] = useState("");
  const [successInput, setSuccessInput] = useState<"ALL" | "true" | "false">(
    "ALL"
  );
  const [fromInput, setFromInput] = useState("");
  const [toInput, setToInput] = useState("");

  const [appliedFilters, setAppliedFilters] = useState<{
    type?: string;
    subType?: string;
    bizNo?: string;
    traceId?: string;
    operatorId?: string;
    success?: boolean;
    from?: string;
    to?: string;
  }>({});

  const [exporting, setExporting] = useState(false);
  const [detailRow, setDetailRow] = useState<AuditLogResponse | null>(null);

  const { data, loading, error, reload } = useAsyncData(
    () =>
      adminAuditLogsApi.list({
        page,
        size: PAGE_SIZE,
        ...appliedFilters,
      }),
    `admin-audit-logs:${page}:${JSON.stringify(appliedFilters)}`,
    { errorMessage: "加载审计日志失败" }
  );

  const applyFilters = () => {
    const operatorTrimmed = operatorIdInput.trim();
    if (operatorTrimmed && !UUID_REGEX.test(operatorTrimmed)) {
      notifyError("操作者 ID 必须为合法 UUID 格式");
      return;
    }
    setPage(1);
    setAppliedFilters({
      type: typeInput.trim() || undefined,
      subType: subTypeInput.trim() || undefined,
      bizNo: bizNoInput.trim() || undefined,
      traceId: traceIdInput.trim() || undefined,
      operatorId: operatorTrimmed || undefined,
      success:
        successInput === "ALL" ? undefined : successInput === "true",
      from: toIsoMaybe(fromInput),
      to: toIsoMaybe(toInput),
    });
  };

  const resetFilters = () => {
    setTypeInput("");
    setSubTypeInput("");
    setBizNoInput("");
    setTraceIdInput("");
    setOperatorIdInput("");
    setSuccessInput("ALL");
    setFromInput("");
    setToInput("");
    setPage(1);
    setAppliedFilters({});
  };

  const handleExportCsv = async () => {
    setExporting(true);
    try {
      const filename = await adminAuditLogsApi.exportCsv({
        ...appliedFilters,
        maxRows: 2000,
      });
      notifySuccess(`已导出 ${filename}`);
    } catch (err) {
      notifyApiError(err, "导出审计日志失败");
    } finally {
      setExporting(false);
    }
  };

  const totalPages = data
    ? Math.max(1, Math.ceil(data.total / data.size))
    : 1;

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="审计日志"
        actions={
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={reload}
              disabled={loading}
              aria-label="刷新审计日志"
            >
              <RefreshCwIcon
                className={
                  loading ? "animate-spin motion-reduce:animate-none" : ""
                }
              />
            </Button>
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={exporting}
              onClick={() => void handleExportCsv()}
            >
              <DownloadIcon />
              <span>{exporting ? "导出中..." : "导出 CSV"}</span>
            </Button>
          </div>
        }
      />

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto w-full max-w-7xl space-y-4">
          {/* Filter panel */}
          <div className="grid gap-2 rounded-surface-md border bg-card p-3 sm:grid-cols-2 lg:grid-cols-4">
            <Input
              value={typeInput}
              onChange={(e) => setTypeInput(e.target.value)}
              placeholder="业务大类（如身份 / 模型）"
              className="h-8 text-xs"
            />
            <Input
              value={subTypeInput}
              onChange={(e) => setSubTypeInput(e.target.value)}
              placeholder="操作子类（如角色变更）"
              className="h-8 text-xs"
            />
            <Input
              value={bizNoInput}
              onChange={(e) => setBizNoInput(e.target.value)}
              placeholder="业务编号"
              className="h-8 font-mono text-xs"
            />
            <Input
              value={traceIdInput}
              onChange={(e) => setTraceIdInput(e.target.value)}
              placeholder="链路追踪编号"
              className="h-8 font-mono text-xs"
            />
            <Input
              value={operatorIdInput}
              onChange={(e) => setOperatorIdInput(e.target.value)}
              placeholder="操作者编号"
              className="h-8 font-mono text-xs"
            />
            <Select
              value={successInput}
              items={{ ALL: "全部结果", true: "仅成功", false: "仅失败" }}
              onValueChange={(v) => {
                if (v) {
                  setSuccessInput(v as "ALL" | "true" | "false");
                }
              }}
            >
              <SelectTrigger size="sm">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="ALL">全部结果</SelectItem>
                <SelectItem value="true">仅成功</SelectItem>
                <SelectItem value="false">仅失败</SelectItem>
              </SelectContent>
            </Select>
            <Input
              type="datetime-local"
              value={fromInput}
              onChange={(e) => setFromInput(e.target.value)}
              aria-label="起始时间"
              className="h-8 text-xs"
            />
            <Input
              type="datetime-local"
              value={toInput}
              onChange={(e) => setToInput(e.target.value)}
              aria-label="截止时间"
              className="h-8 text-xs"
            />

            <div className="flex items-center gap-2 sm:col-span-2 lg:col-span-4">
              <Button type="button" size="xs" onClick={applyFilters}>
                查询审计日志
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="xs"
                onClick={resetFilters}
              >
                重置条件
              </Button>
            </div>
          </div>

          <ApiErrorAlert error={error} title="审计日志查询失败" />

          {loading && !data ? (
            <Skeleton className="h-64 w-full rounded-surface-md" />
          ) : !data || data.items.length === 0 ? (
            <div className="rounded-surface-md border border-dashed p-10 text-center text-sm text-muted-foreground">
              暂无符合筛选条件的审计日志。
            </div>
          ) : (
            <>
              <div className="rounded-surface-md border bg-card">
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>时间</TableHead>
                      <TableHead>操作分类</TableHead>
                      <TableHead>操作者</TableHead>
                      <TableHead>结果</TableHead>
                      <TableHead>详情摘要</TableHead>
                      <TableHead className="text-right">操作</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {data.items.map((row) => (
                      <TableRow
                        key={row.id}
                        className="cursor-pointer"
                        onClick={() => setDetailRow(row)}
                      >
                        <TableCell className="text-xs text-muted-foreground tabular-nums">
                          {formatDateTime(row.createdAt)}
                        </TableCell>
                        <TableCell>
                          <div className="flex flex-wrap items-center gap-1">
                            <Badge variant="outline">
                              {auditTypeLabel(row.type)}
                            </Badge>
                            <Badge variant="secondary" className="text-[10px]">
                              {auditSubTypeLabel(row.subType)}
                            </Badge>
                          </div>
                        </TableCell>
                        <TableCell className="text-xs">
                          {row.operatorName || "系统"}
                        </TableCell>
                        <TableCell>
                          <Badge
                            variant={row.success ? "default" : "destructive"}
                          >
                            {row.success ? "成功" : "失败"}
                          </Badge>
                        </TableCell>
                        <TableCell className="max-w-xs truncate text-xs">
                          {row.detail ?? row.errorMessage ?? "—"}
                        </TableCell>
                        <TableCell className="text-right">
                          <Button
                            type="button"
                            variant="ghost"
                            size="xs"
                            onClick={(e) => {
                              e.stopPropagation();
                              setDetailRow(row);
                            }}
                          >
                            详情
                          </Button>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>

              <div className="flex items-center justify-between text-xs text-muted-foreground">
                <span className="tabular-nums">
                  第 {data.page} / {totalPages} 页 · 共 {data.total} 条
                </span>
                <div className="flex items-center gap-2">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={page <= 1 || loading}
                    onClick={() => setPage((p) => Math.max(1, p - 1))}
                  >
                    <ChevronLeftIcon />
                    <span>上一页</span>
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    disabled={page >= totalPages || loading}
                    onClick={() =>
                      setPage((p) => Math.min(totalPages, p + 1))
                    }
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

      {/* Audit Detail Dialog */}
      <Dialog
        open={detailRow !== null}
        onOpenChange={(open) => {
          if (!open) {
            setDetailRow(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle className="flex flex-wrap items-center gap-2">
              <Badge variant="outline">
                {detailRow ? auditTypeLabel(detailRow.type) : "审计详情"}
              </Badge>
              <Badge variant="secondary">
                {detailRow ? auditSubTypeLabel(detailRow.subType) : "明细"}
              </Badge>
              <span>审计详情</span>
            </DialogTitle>
            <DialogDescription className="tabular-nums">
              {formatDateTime(detailRow?.createdAt)}
            </DialogDescription>
          </DialogHeader>

          {detailRow ? (
            <div className="space-y-3 text-xs">
              <div className="grid gap-2 rounded-surface-sm bg-muted/20 p-3 sm:grid-cols-2">
                <div>
                  <span className="text-muted-foreground">操作者：</span>
                  <span>{detailRow.operatorName ?? "系统"}</span>
                </div>
                <div>
                  <span className="text-muted-foreground">结果：</span>
                  <Badge variant={detailRow.success ? "default" : "destructive"}>
                    {detailRow.success ? "成功" : "失败"}
                  </Badge>
                </div>
              </div>

              {detailRow.detail ? (
                <div className="space-y-1">
                  <p className="font-medium text-muted-foreground">操作描述</p>
                  <div className="rounded-surface-sm bg-muted/30 p-3 leading-relaxed">
                    {detailRow.detail}
                  </div>
                </div>
              ) : null}

              {detailRow.errorMessage ? (
                <div className="space-y-1">
                  <p className="font-medium text-destructive">错误信息</p>
                  <pre className="overflow-x-auto rounded-surface-sm border border-destructive/30 bg-destructive/5 p-3 font-mono text-[11px] whitespace-pre-wrap text-destructive">
                    {detailRow.errorMessage}
                  </pre>
                </div>
              ) : null}

              <details className="rounded-surface-sm bg-muted/20 px-3 py-2 text-muted-foreground">
                <summary className="cursor-pointer select-none">
                  技术诊断信息
                </summary>
                <div className="mt-2 grid gap-1 font-mono text-[11px]">
                  <div>操作者编号：{detailRow.operatorId ?? "—"}</div>
                  <div>业务编号：{detailRow.bizNo ?? "—"}</div>
                  <div>追踪编号：{detailRow.traceId ?? "—"}</div>
                </div>
                {detailRow.extra ? (
                  <pre className="mt-2 max-h-60 overflow-auto rounded-surface-sm bg-muted/30 p-3 font-mono text-[11px] leading-relaxed">
                    {formatJsonMaybe(detailRow.extra)}
                  </pre>
                ) : null}
              </details>
            </div>
          ) : null}
        </DialogContent>
      </Dialog>
    </div>
  );
}
