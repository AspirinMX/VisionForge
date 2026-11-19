"use client";

import { useState } from "react";
import {
  ChevronLeftIcon,
  ChevronRightIcon,
  CopyIcon,
  PlusIcon,
  RefreshCwIcon,
  TicketIcon,
} from "lucide-react";
import { toast } from "sonner";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { PageHeader } from "@/components/layout/page-header";
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminBillingApi } from "@/lib/api/endpoints";
import { copyToClipboard, notifyApiError, notifyError } from "@/lib/api/feedback";
import type {
  CreditLedgerEntryType,
  RedemptionCodeResponse,
  RedemptionCodeStatus,
} from "@/lib/api/types";
import {
  CREDIT_ENTRY_LABEL,
  formatDateTime,
  REDEMPTION_STATUS_LABEL,
} from "@/lib/format";

const PAGE_SIZE = 30;
const UUID_REGEX =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export default function AdminBillingPage() {
  const [activeTab, setActiveTab] = useState<"redemptions" | "ledger">(
    "redemptions"
  );

  // Redemptions filters
  const [cdkPage, setCdkPage] = useState(1);
  const [batchInput, setBatchInput] = useState("");
  const [appliedBatch, setAppliedBatch] = useState("");
  const [statusFilter, setStatusFilter] = useState<
    RedemptionCodeStatus | "ALL"
  >("ALL");

  // Ledger filters
  const [ledgerPage, setLedgerPage] = useState(1);
  const [userIdInput, setUserIdInput] = useState("");
  const [appliedUserId, setAppliedUserId] = useState("");
  const [entryTypeFilter, setEntryTypeFilter] = useState<
    CreditLedgerEntryType | "ALL"
  >("ALL");

  const {
    data: redemptionsPage,
    loading: loadingRedemptions,
    error: redemptionsError,
    reload: reloadRedemptions,
  } = useAsyncData(
    () =>
      adminBillingApi.listRedemptions({
        page: cdkPage,
        size: PAGE_SIZE,
        batchName: appliedBatch.trim() || undefined,
        status: statusFilter === "ALL" ? undefined : statusFilter,
      }),
    `admin-redemptions:${cdkPage}:${appliedBatch.trim()}:${statusFilter}`,
    { errorMessage: "加载兑换码列表失败" }
  );

  const {
    data: ledgerPageData,
    loading: loadingLedger,
    error: ledgerError,
    reload: reloadLedger,
  } = useAsyncData(
    () =>
      adminBillingApi.ledger({
        page: ledgerPage,
        size: PAGE_SIZE,
        userId: appliedUserId.trim() || undefined,
        entryType: entryTypeFilter === "ALL" ? undefined : entryTypeFilter,
      }),
    `admin-billing-ledger:${ledgerPage}:${appliedUserId.trim()}:${entryTypeFilter}`,
    { errorMessage: "加载全站额度流水失败" }
  );

  // Batch create dialog
  const [createOpen, setCreateOpen] = useState(false);
  const [batchName, setBatchName] = useState("");
  const [count, setCount] = useState("10");
  const [amountUnits, setAmountUnits] = useState("500");
  const [maxUsesPerCode, setMaxUsesPerCode] = useState("1");
  const [perUserLimit, setPerUserLimit] = useState("1");
  const [customPrefix, setCustomPrefix] = useState("");
  const [expiresAtLocal, setExpiresAtLocal] = useState("");
  const [creatingBatch, setCreatingBatch] = useState(false);

  const [generatedCodes, setGeneratedCodes] = useState<
    RedemptionCodeResponse[]
  >([]);
  const [voidTarget, setVoidTarget] = useState<RedemptionCodeResponse | null>(
    null
  );
  const [voiding, setVoiding] = useState(false);

  const handleCopyCode = async (code: string) => {
    const ok = await copyToClipboard(code);
    if (ok) {
      toast.success(`已复制兑换码：${code}`);
    } else {
      notifyError("当前浏览器不支持剪贴板写入");
    }
  };

  const handleCopyAllGenerated = async () => {
    const text = generatedCodes.map((c) => c.code).join("\n");
    const ok = await copyToClipboard(text);
    if (ok) {
      toast.success(`已复制全部 ${generatedCodes.length} 个兑换码`);
    } else {
      notifyError("当前浏览器不支持剪贴板写入");
    }
  };

  const handleCreateBatch = async () => {
    const trimmedBatch = batchName.trim();
    const parsedCount = Number(count);
    const parsedAmount = Number(amountUnits);
    const parsedMaxUses = Number(maxUsesPerCode);
    const parsedPerUser = Number(perUserLimit);

    if (!trimmedBatch) {
      notifyError("请填写批次名称");
      return;
    }
    if (
      !Number.isInteger(parsedCount) ||
      parsedCount < 1 ||
      parsedCount > 100
    ) {
      notifyError("单次生成数量必须在 1 到 100 之间");
      return;
    }
    if (!Number.isInteger(parsedAmount) || parsedAmount <= 0) {
      notifyError("单码面额必须为正整数");
      return;
    }
    if (!Number.isInteger(parsedMaxUses) || parsedMaxUses < 1) {
      notifyError("单码最大兑换次数至少为 1");
      return;
    }
    if (!Number.isInteger(parsedPerUser) || parsedPerUser < 1) {
      notifyError("每用户限兑次数至少为 1");
      return;
    }

    let expiresAtIso: string | undefined;
    if (expiresAtLocal.trim()) {
      const parsedDate = new Date(expiresAtLocal);
      if (Number.isNaN(parsedDate.getTime())) {
        notifyError("过期时间格式不正确");
        return;
      }
      expiresAtIso = parsedDate.toISOString();
    }

    setCreatingBatch(true);
    try {
      const created = await adminBillingApi.createRedemptions({
        batchName: trimmedBatch,
        count: parsedCount,
        amountUnits: parsedAmount,
        maxUsesPerCode: parsedMaxUses,
        perUserLimit: parsedPerUser,
        customPrefix: customPrefix.trim() || undefined,
        expiresAt: expiresAtIso,
      });
      setCreateOpen(false);
      setGeneratedCodes(created);
      setBatchName("");
      setCdkPage(1);
      reloadRedemptions();
      toast.success(`成功生成 ${created.length} 个兑换码`);
    } catch (err) {
      notifyApiError(err, "批量生成兑换码失败");
    } finally {
      setCreatingBatch(false);
    }
  };

  const handleConfirmVoid = async () => {
    if (!voidTarget || voiding) {
      return;
    }
    setVoiding(true);
    try {
      await adminBillingApi.voidRedemption(voidTarget.id);
      toast.success(`兑换码 ${voidTarget.code} 已作废`);
      setVoidTarget(null);
      reloadRedemptions();
    } catch (err) {
      notifyApiError(err, "作废兑换码失败");
    } finally {
      setVoiding(false);
    }
  };

  const applyLedgerUserFilter = () => {
    const trimmed = userIdInput.trim();
    if (trimmed && !UUID_REGEX.test(trimmed)) {
      notifyError("用户 ID 必须为合法 UUID 格式");
      return;
    }
    setLedgerPage(1);
    setAppliedUserId(trimmed);
  };

  const totalCdkPages = redemptionsPage
    ? Math.max(1, Math.ceil(redemptionsPage.total / redemptionsPage.size))
    : 1;
  const totalLedgerPages = ledgerPageData
    ? Math.max(1, Math.ceil(ledgerPageData.total / ledgerPageData.size))
    : 1;

  const isRefreshing =
    activeTab === "redemptions" ? loadingRedemptions : loadingLedger;

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="计费与兑换码"
        actions={
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={() => {
                reloadRedemptions();
                reloadLedger();
              }}
              disabled={isRefreshing}
              aria-label="刷新账务数据"
            >
              <RefreshCwIcon
                className={
                  isRefreshing ? "animate-spin motion-reduce:animate-none" : ""
                }
              />
            </Button>
            <Button type="button" size="sm" onClick={() => setCreateOpen(true)}>
              <PlusIcon />
              <span>批量生成兑换码</span>
            </Button>
          </div>
        }
      />

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto w-full max-w-7xl space-y-4">
          <Tabs
            value={activeTab}
            onValueChange={(v) => setActiveTab(v as "redemptions" | "ledger")}
          >
            <TabsList>
              <TabsTrigger value="redemptions">
                CDK 兑换码管理 ({redemptionsPage?.total ?? 0})
              </TabsTrigger>
              <TabsTrigger value="ledger">
                全站额度流水 ({ledgerPageData?.total ?? 0})
              </TabsTrigger>
            </TabsList>

            <TabsContent value="redemptions" className="mt-4 space-y-4">
              <div className="flex flex-wrap items-center gap-2">
                <Input
                  value={batchInput}
                  onChange={(e) => setBatchInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      setCdkPage(1);
                      setAppliedBatch(batchInput);
                    }
                  }}
                  placeholder="按批次名称筛选..."
                  className="h-9 max-w-xs"
                />
                <Select
                  value={statusFilter}
                  items={{
                    ALL: "全部状态",
                    ACTIVE: REDEMPTION_STATUS_LABEL.ACTIVE,
                    VOIDED: REDEMPTION_STATUS_LABEL.VOIDED,
                  }}
                  onValueChange={(v) => {
                    if (v) {
                      setCdkPage(1);
                      setStatusFilter(v as RedemptionCodeStatus | "ALL");
                    }
                  }}
                >
                  <SelectTrigger className="w-32">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ALL">全部状态</SelectItem>
                    <SelectItem value="ACTIVE">
                      {REDEMPTION_STATUS_LABEL.ACTIVE}
                    </SelectItem>
                    <SelectItem value="VOIDED">
                      {REDEMPTION_STATUS_LABEL.VOIDED}
                    </SelectItem>
                  </SelectContent>
                </Select>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={() => {
                    setCdkPage(1);
                    setAppliedBatch(batchInput);
                  }}
                >
                  查询
                </Button>
              </div>

              <ApiErrorAlert
                error={redemptionsError}
                title="读取兑换码列表失败"
              />

              {loadingRedemptions && !redemptionsPage ? (
                <Skeleton className="h-64 w-full rounded-2xl" />
              ) : !redemptionsPage || redemptionsPage.items.length === 0 ? (
                <div className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-dashed p-10 text-center">
                  <TicketIcon className="size-8 text-muted-foreground" />
                  <p className="text-sm text-muted-foreground">
                    暂无符合条件的兑换码记录。
                  </p>
                </div>
              ) : (
                <>
                  <div className="rounded-2xl border bg-card">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>兑换码</TableHead>
                          <TableHead>批次</TableHead>
                          <TableHead className="text-right">面额</TableHead>
                          <TableHead className="text-right">已兑 / 上限</TableHead>
                          <TableHead>状态</TableHead>
                          <TableHead>过期时间</TableHead>
                          <TableHead className="text-right">操作</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {redemptionsPage.items.map((item) => (
                          <TableRow key={item.id}>
                            <TableCell>
                              <div className="flex items-center gap-1.5">
                                <span className="font-mono text-xs font-medium">
                                  {item.code}
                                </span>
                                <Button
                                  type="button"
                                  variant="ghost"
                                  size="icon-xs"
                                  aria-label={`复制 ${item.code}`}
                                  onClick={() => void handleCopyCode(item.code)}
                                >
                                  <CopyIcon className="size-3" />
                                </Button>
                              </div>
                            </TableCell>
                            <TableCell className="text-xs">
                              {item.batchName}
                            </TableCell>
                            <TableCell className="text-right font-mono font-medium tabular-nums">
                              +{item.amountUnits}
                            </TableCell>
                            <TableCell className="text-right text-xs text-muted-foreground tabular-nums">
                              {item.redeemedCount} / {item.maxUses} (每人限{" "}
                              {item.perUserLimit})
                            </TableCell>
                            <TableCell>
                              <Badge
                                variant={
                                  item.status === "ACTIVE"
                                    ? "default"
                                    : "secondary"
                                }
                              >
                                {REDEMPTION_STATUS_LABEL[item.status]}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-xs text-muted-foreground tabular-nums">
                              {item.expiresAt
                                ? formatDateTime(item.expiresAt)
                                : "永不过期"}
                            </TableCell>
                            <TableCell className="text-right">
                              {item.status === "ACTIVE" ? (
                                <Button
                                  type="button"
                                  variant="ghost"
                                  size="xs"
                                  className="text-destructive hover:text-destructive"
                                  onClick={() => setVoidTarget(item)}
                                >
                                  作废
                                </Button>
                              ) : (
                                <span className="text-xs text-muted-foreground">
                                  —
                                </span>
                              )}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>

                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span className="tabular-nums">
                      第 {redemptionsPage.page} / {totalCdkPages} 页 · 共{" "}
                      {redemptionsPage.total} 条
                    </span>
                    <div className="flex items-center gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={cdkPage <= 1 || loadingRedemptions}
                        onClick={() => setCdkPage((p) => Math.max(1, p - 1))}
                      >
                        <ChevronLeftIcon />
                        <span>上一页</span>
                      </Button>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={
                          cdkPage >= totalCdkPages || loadingRedemptions
                        }
                        onClick={() =>
                          setCdkPage((p) => Math.min(totalCdkPages, p + 1))
                        }
                      >
                        <span>下一页</span>
                        <ChevronRightIcon />
                      </Button>
                    </div>
                  </div>
                </>
              )}
            </TabsContent>

            <TabsContent value="ledger" className="mt-4 space-y-4">
              <div className="flex flex-wrap items-center gap-2">
                <Input
                  value={userIdInput}
                  onChange={(e) => setUserIdInput(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      applyLedgerUserFilter();
                    }
                  }}
                  placeholder="按用户 UUID 过滤..."
                  className="h-9 max-w-xs font-mono text-xs"
                />
                <Select
                  value={entryTypeFilter}
                  items={{
                    ALL: "全部类型",
                    ...CREDIT_ENTRY_LABEL,
                  }}
                  onValueChange={(v) => {
                    if (v) {
                      setLedgerPage(1);
                      setEntryTypeFilter(v as CreditLedgerEntryType | "ALL");
                    }
                  }}
                >
                  <SelectTrigger className="w-36">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ALL">全部类型</SelectItem>
                    {(
                      Object.keys(
                        CREDIT_ENTRY_LABEL
                      ) as CreditLedgerEntryType[]
                    ).map((key) => (
                      <SelectItem key={key} value={key}>
                        {CREDIT_ENTRY_LABEL[key]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <Button
                  type="button"
                  variant="secondary"
                  size="sm"
                  onClick={applyLedgerUserFilter}
                >
                  筛选
                </Button>
              </div>

              <ApiErrorAlert
                error={ledgerError}
                title="读取全站额度流水失败"
              />

              {loadingLedger && !ledgerPageData ? (
                <Skeleton className="h-64 w-full rounded-2xl" />
              ) : !ledgerPageData || ledgerPageData.items.length === 0 ? (
                <div className="rounded-2xl border border-dashed p-10 text-center text-sm text-muted-foreground">
                  暂无符合条件的额度流水。
                </div>
              ) : (
                <>
                  <div className="rounded-2xl border bg-card">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>时间</TableHead>
                          <TableHead>用户 ID</TableHead>
                          <TableHead>类型</TableHead>
                          <TableHead className="text-right">额度变更</TableHead>
                          <TableHead>业务幂等键</TableHead>
                          <TableHead>备注</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {ledgerPageData.items.map((row) => (
                          <TableRow key={row.id}>
                            <TableCell className="text-xs text-muted-foreground tabular-nums">
                              {formatDateTime(row.createdAt)}
                            </TableCell>
                            <TableCell className="max-w-44 truncate font-mono text-[11px]">
                              {row.userId}
                            </TableCell>
                            <TableCell>
                              <Badge variant="outline">
                                {CREDIT_ENTRY_LABEL[row.entryType]}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-right font-mono font-medium tabular-nums">
                              {row.amountUnits}
                            </TableCell>
                            <TableCell className="max-w-xs truncate font-mono text-[11px] text-muted-foreground">
                              {row.businessKey ?? "—"}
                            </TableCell>
                            <TableCell className="max-w-xs truncate text-xs text-muted-foreground">
                              {row.remark ?? "—"}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>

                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span className="tabular-nums">
                      第 {ledgerPageData.page} / {totalLedgerPages} 页 · 共{" "}
                      {ledgerPageData.total} 条
                    </span>
                    <div className="flex items-center gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={ledgerPage <= 1 || loadingLedger}
                        onClick={() =>
                          setLedgerPage((p) => Math.max(1, p - 1))
                        }
                      >
                        <ChevronLeftIcon />
                        <span>上一页</span>
                      </Button>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={
                          ledgerPage >= totalLedgerPages || loadingLedger
                        }
                        onClick={() =>
                          setLedgerPage((p) =>
                            Math.min(totalLedgerPages, p + 1)
                          )
                        }
                      >
                        <span>下一页</span>
                        <ChevronRightIcon />
                      </Button>
                    </div>
                  </div>
                </>
              )}
            </TabsContent>
          </Tabs>
        </div>
      </div>

      {/* Create Batch Dialog */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>批量生成 CDK 兑换码</DialogTitle>
            <DialogDescription>
              单次可发行 1-100 枚额度兑换码，支持设置总可用次数与单人限兑次数。
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3 py-1">
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="cdk-batch-name">批次名称</Label>
                <Input
                  id="cdk-batch-name"
                  maxLength={100}
                  value={batchName}
                  onChange={(e) => setBatchName(e.target.value)}
                  placeholder="例如：2026-Q4 内测赠送"
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="cdk-prefix">自定义前缀（选填，最多12位）</Label>
                <Input
                  id="cdk-prefix"
                  maxLength={12}
                  value={customPrefix}
                  onChange={(e) => setCustomPrefix(e.target.value)}
                  placeholder="默认 VF"
                />
              </div>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="cdk-count">生成数量（1-100）</Label>
                <Input
                  id="cdk-count"
                  type="number"
                  min={1}
                  max={100}
                  value={count}
                  onChange={(e) => setCount(e.target.value)}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="cdk-amount">单码额度面额</Label>
                <Input
                  id="cdk-amount"
                  type="number"
                  min={1}
                  value={amountUnits}
                  onChange={(e) => setAmountUnits(e.target.value)}
                />
              </div>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="cdk-max-uses">单码总可兑次数</Label>
                <Input
                  id="cdk-max-uses"
                  type="number"
                  min={1}
                  value={maxUsesPerCode}
                  onChange={(e) => setMaxUsesPerCode(e.target.value)}
                />
              </div>
              <div className="space-y-1.5">
                <Label htmlFor="cdk-per-user">每用户限兑次数</Label>
                <Input
                  id="cdk-per-user"
                  type="number"
                  min={1}
                  value={perUserLimit}
                  onChange={(e) => setPerUserLimit(e.target.value)}
                />
              </div>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="cdk-expires">过期时间（留空表示永不过期）</Label>
              <Input
                id="cdk-expires"
                type="datetime-local"
                value={expiresAtLocal}
                onChange={(e) => setExpiresAtLocal(e.target.value)}
              />
            </div>
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={creatingBatch}
              onClick={() => setCreateOpen(false)}
            >
              取消
            </Button>
            <Button
              type="button"
              disabled={creatingBatch}
              onClick={() => void handleCreateBatch()}
            >
              {creatingBatch ? "生成中..." : "生成兑换码"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Generated Result Dialog */}
      <Dialog
        open={generatedCodes.length > 0}
        onOpenChange={(open) => {
          if (!open) {
            setGeneratedCodes([]);
          }
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>已生成 {generatedCodes.length} 个兑换码</DialogTitle>
            <DialogDescription>
              面额 +{generatedCodes[0]?.amountUnits ?? 0} 额度（批次：
              {generatedCodes[0]?.batchName}），请复制保存分发。
            </DialogDescription>
          </DialogHeader>
          <Textarea
            readOnly
            rows={Math.min(12, Math.max(4, generatedCodes.length))}
            value={generatedCodes.map((c) => c.code).join("\n")}
            className="font-mono text-xs"
          />
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setGeneratedCodes([])}
            >
              关闭
            </Button>
            <Button type="button" onClick={() => void handleCopyAllGenerated()}>
              <CopyIcon />
              <span>一键复制全部</span>
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Void Confirm */}
      <AlertDialog
        open={voidTarget !== null}
        onOpenChange={(open) => {
          if (!open && !voiding) {
            setVoidTarget(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>作废兑换码</AlertDialogTitle>
            <AlertDialogDescription>
              确定作废兑换码{" "}
              <span className="font-mono font-semibold">{voidTarget?.code}</span>
              ？作废后任何用户均无法再使用该码兑换额度（不影响此前已兑换入账的记录）。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={voiding}>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                event.preventDefault();
                void handleConfirmVoid();
              }}
              disabled={voiding}
            >
              {voiding ? "处理中..." : "确认作废"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
