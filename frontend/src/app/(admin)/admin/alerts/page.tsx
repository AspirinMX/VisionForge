"use client";

import { useState } from "react";
import {
  BellRingIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  PencilIcon,
  PlusIcon,
  RefreshCwIcon,
  SendIcon,
  Trash2Icon,
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
import { Checkbox } from "@/components/ui/checkbox";
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
import { Switch } from "@/components/ui/switch";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminAlertsApi } from "@/lib/api/endpoints";
import { notifyApiError, notifyError, notifySuccess } from "@/lib/api/feedback";
import type {
  AlertChannelResponse,
  AlertChannelType,
  AlertDeliveryResponse,
  AlertDeliveryStatus,
  AlertSeverity,
  UUID,
} from "@/lib/api/types";
import {
  ALERT_CHANNEL_TYPE_LABEL,
  ALERT_DELIVERY_STATUS_LABEL,
  ALERT_SEVERITY_LABEL,
  formatDateTime,
} from "@/lib/format";

const ALERT_EVENT_OPTIONS = [
  { id: "WORKFLOW_START_EXHAUSTED", label: "Workflow 启动补偿耗尽" },
  { id: "MODEL_PROVIDER_ERROR", label: "主模型上游异常" },
  { id: "MEDIA_PROVIDER_ERROR", label: "媒体生成上游异常" },
  { id: "GENERATION_ARCHIVE_FAILED", label: "产物归档失败 (ARCHIVE_FAILED)" },
  { id: "BILLING_PENDING_RECONCILIATION", label: "待核账异常 (UNKNOWN)" },
  { id: "COMPOSITION_FAILED", label: "视频合成失败" },
  { id: "ADMIN_SECURITY_CHANGE", label: "管理员安全与配置变更" },
  { id: "SYSTEM_INTERNAL_ERROR", label: "系统未处理 5xx 异常" },
] as const;

const DELIVERY_PAGE_SIZE = 30;

function severityBadgeVariant(
  severity: AlertSeverity
): "default" | "destructive" | "secondary" {
  if (severity === "CRITICAL") {
    return "destructive";
  }
  if (severity === "WARNING") {
    return "default";
  }
  return "secondary";
}

function deliveryStatusVariant(
  status: AlertDeliveryStatus
): "default" | "destructive" | "secondary" {
  if (status === "SENT") {
    return "default";
  }
  if (status === "FAILED") {
    return "destructive";
  }
  return "secondary";
}

interface ChannelFormState {
  name: string;
  channelType: AlertChannelType;
  target: string;
  secret: string;
  allEvents: boolean;
  selectedEvents: string[];
  minSeverity: AlertSeverity;
  enabled: boolean;
}

function initialFormFromChannel(
  channel: AlertChannelResponse | null
): ChannelFormState {
  if (!channel) {
    return {
      name: "",
      channelType: "WEBHOOK",
      target: "https://",
      secret: "",
      allEvents: true,
      selectedEvents: ALERT_EVENT_OPTIONS.map((item) => item.id),
      minSeverity: "WARNING",
      enabled: true,
    };
  }
  const rawEvents = channel.subscribedEvents.trim();
  const isAll = !rawEvents || rawEvents === "*";
  const parsed = isAll
    ? ALERT_EVENT_OPTIONS.map((item) => item.id)
    : rawEvents
        .split(",")
        .map((s) => s.trim())
        .filter(Boolean);
  return {
    name: channel.name,
    channelType: channel.channelType,
    target: channel.target,
    secret: "",
    allEvents: isAll,
    selectedEvents: parsed,
    minSeverity: channel.minSeverity,
    enabled: channel.enabled,
  };
}

export default function AdminAlertsPage() {
  const [activeTab, setActiveTab] = useState<"channels" | "deliveries">(
    "channels"
  );
  const [deliveryPage, setDeliveryPage] = useState(1);
  const [deliveryStatusFilter, setDeliveryStatusFilter] = useState<
    AlertDeliveryStatus | "ALL"
  >("ALL");
  const [deliveryChannelFilter, setDeliveryChannelFilter] = useState<
    UUID | "ALL"
  >("ALL");

  const {
    data: channels,
    loading: loadingChannels,
    error: channelsError,
    reload: reloadChannels,
  } = useAsyncData(
    () => adminAlertsApi.listChannels(),
    "admin-alert-channels",
    { errorMessage: "加载告警渠道列表失败" }
  );

  const {
    data: deliveriesPage,
    loading: loadingDeliveries,
    error: deliveriesError,
    reload: reloadDeliveries,
  } = useAsyncData(
    () =>
      adminAlertsApi.listDeliveries({
        page: deliveryPage,
        size: DELIVERY_PAGE_SIZE,
        channelId:
          deliveryChannelFilter === "ALL" ? undefined : deliveryChannelFilter,
        status:
          deliveryStatusFilter === "ALL" ? undefined : deliveryStatusFilter,
      }),
    `admin-alert-deliveries:${deliveryPage}:${deliveryStatusFilter}:${deliveryChannelFilter}`,
    { errorMessage: "加载告警投递日志失败" }
  );

  const [formOpen, setFormOpen] = useState(false);
  const [editingChannel, setEditingChannel] =
    useState<AlertChannelResponse | null>(null);
  const [form, setForm] = useState<ChannelFormState>(() =>
    initialFormFromChannel(null)
  );
  const [savingChannel, setSavingChannel] = useState(false);
  const [busyChannelId, setBusyChannelId] = useState<UUID | null>(null);
  const [testingChannelId, setTestingChannelId] = useState<UUID | null>(null);
  const [deleteTarget, setDeleteTarget] =
    useState<AlertChannelResponse | null>(null);
  const [deletingChannel, setDeletingChannel] = useState(false);
  const [detailDelivery, setDetailDelivery] =
    useState<AlertDeliveryResponse | null>(null);

  const openCreateDialog = () => {
    setEditingChannel(null);
    setForm(initialFormFromChannel(null));
    setFormOpen(true);
  };

  const openEditDialog = (channel: AlertChannelResponse) => {
    setEditingChannel(channel);
    setForm(initialFormFromChannel(channel));
    setFormOpen(true);
  };

  const handleSaveChannel = async () => {
    const name = form.name.trim();
    const target = form.target.trim();
    if (!name || !target) {
      notifyError("请填写渠道名称和目标地址");
      return;
    }
    if (!form.allEvents && form.selectedEvents.length === 0) {
      notifyError("请至少选择一个订阅事件，或启用全部事件订阅 (*)");
      return;
    }
    const subscribedEvents = form.allEvents
      ? "*"
      : form.selectedEvents.join(",");

    setSavingChannel(true);
    try {
      if (editingChannel) {
        await adminAlertsApi.updateChannel(editingChannel.id, {
          name,
          channelType: form.channelType,
          target,
          secret: form.secret.trim() ? form.secret.trim() : undefined,
          subscribedEvents,
          minSeverity: form.minSeverity,
          enabled: form.enabled,
        });
        toast.success(`已更新告警渠道「${name}」`);
      } else {
        await adminAlertsApi.createChannel({
          name,
          channelType: form.channelType,
          target,
          secret:
            form.channelType === "WEBHOOK" && form.secret.trim()
              ? form.secret.trim()
              : undefined,
          subscribedEvents,
          minSeverity: form.minSeverity,
          enabled: form.enabled,
        });
        toast.success(`已创建告警渠道「${name}」`);
      }
      setFormOpen(false);
      reloadChannels();
    } catch (err) {
      notifyApiError(err, "保存告警渠道失败");
    } finally {
      setSavingChannel(false);
    }
  };

  const handleToggleEnabled = async (
    channel: AlertChannelResponse,
    nextEnabled: boolean
  ) => {
    if (busyChannelId) {
      return;
    }
    setBusyChannelId(channel.id);
    try {
      await adminAlertsApi.updateChannel(channel.id, { enabled: nextEnabled });
      toast.success(
        `渠道「${channel.name}」已${nextEnabled ? "启用" : "停用"}`
      );
      reloadChannels();
    } catch (err) {
      notifyApiError(err, "切换渠道状态失败");
    } finally {
      setBusyChannelId(null);
    }
  };

  const handleTestChannel = async (channel: AlertChannelResponse) => {
    if (testingChannelId) {
      return;
    }
    setTestingChannelId(channel.id);
    try {
      const delivery = await adminAlertsApi.testChannel(channel.id);
      if (delivery.status === "SENT") {
        notifySuccess(
          `测试告警已成功投递至「${channel.name}」`,
          delivery.httpStatus ? `HTTP ${delivery.httpStatus}` : undefined
        );
      } else {
        notifyError(
          `测试告警投递结果：${ALERT_DELIVERY_STATUS_LABEL[delivery.status]}`,
          delivery.errorMessage ?? undefined
        );
      }
      reloadDeliveries();
    } catch (err) {
      notifyApiError(err, "触发测试告警失败");
    } finally {
      setTestingChannelId(null);
    }
  };

  const handleConfirmDelete = async () => {
    if (!deleteTarget || deletingChannel) {
      return;
    }
    setDeletingChannel(true);
    try {
      await adminAlertsApi.deleteChannel(deleteTarget.id);
      toast.success(`已删除告警渠道「${deleteTarget.name}」`);
      setDeleteTarget(null);
      reloadChannels();
    } catch (err) {
      notifyApiError(err, "删除告警渠道失败");
    } finally {
      setDeletingChannel(false);
    }
  };

  const totalDeliveryPages = deliveriesPage
    ? Math.max(1, Math.ceil(deliveriesPage.total / deliveriesPage.size))
    : 1;

  const isRefreshing =
    activeTab === "channels" ? loadingChannels : loadingDeliveries;

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="告警中心"
        actions={
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={() => {
                reloadChannels();
                reloadDeliveries();
              }}
              disabled={isRefreshing}
              aria-label="刷新告警数据"
            >
              <RefreshCwIcon
                className={
                  isRefreshing ? "animate-spin motion-reduce:animate-none" : ""
                }
              />
            </Button>
            <Button type="button" size="sm" onClick={openCreateDialog}>
              <PlusIcon />
              <span>新建渠道</span>
            </Button>
          </div>
        }
      />

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto w-full max-w-7xl space-y-4">
          <Tabs
            value={activeTab}
            onValueChange={(v) => setActiveTab(v as "channels" | "deliveries")}
          >
            <TabsList>
              <TabsTrigger value="channels">
                通知渠道配置 ({channels?.length ?? 0})
              </TabsTrigger>
              <TabsTrigger value="deliveries">
                投递审计记录 ({deliveriesPage?.total ?? 0})
              </TabsTrigger>
            </TabsList>

            <TabsContent value="channels" className="mt-4 space-y-4">
              <ApiErrorAlert error={channelsError} title="读取告警渠道失败" />

              {loadingChannels && !channels ? (
                <Skeleton className="h-64 w-full rounded-surface-md" />
              ) : !channels || channels.length === 0 ? (
                <div className="flex flex-col items-center justify-center gap-3 rounded-surface-md border border-dashed p-10 text-center">
                  <BellRingIcon className="size-8 text-muted-foreground" />
                  <p className="text-sm text-muted-foreground">
                    尚未配置 Email 或 Webhook 告警接收渠道。
                  </p>
                  <Button type="button" size="sm" onClick={openCreateDialog}>
                    <PlusIcon />
                    <span>创建首个告警渠道</span>
                  </Button>
                </div>
              ) : (
                <div className="rounded-surface-md border bg-card">
                  <Table>
                    <TableHeader>
                      <TableRow>
                        <TableHead>渠道名称</TableHead>
                        <TableHead>类型与目标</TableHead>
                        <TableHead>最低级别</TableHead>
                        <TableHead>订阅事件</TableHead>
                        <TableHead>启用</TableHead>
                        <TableHead className="text-right">操作</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {channels.map((ch) => (
                        <TableRow key={ch.id}>
                          <TableCell className="font-medium">
                            <div className="flex items-center gap-2">
                              <span>{ch.name}</span>
                              {ch.channelType === "WEBHOOK" &&
                              ch.secretConfigured ? (
                                <Badge variant="secondary" className="text-[10px]">
                                  HMAC 已签
                                </Badge>
                              ) : null}
                            </div>
                          </TableCell>
                          <TableCell>
                            <div className="flex items-center gap-2">
                              <Badge variant="outline">
                                {ALERT_CHANNEL_TYPE_LABEL[ch.channelType]}
                              </Badge>
                              <span className="max-w-xs truncate font-mono text-xs text-muted-foreground">
                                {ch.target}
                              </span>
                            </div>
                          </TableCell>
                          <TableCell>
                            <Badge variant={severityBadgeVariant(ch.minSeverity)}>
                              {ALERT_SEVERITY_LABEL[ch.minSeverity]}
                            </Badge>
                          </TableCell>
                          <TableCell className="max-w-xs truncate font-mono text-xs text-muted-foreground">
                            {ch.subscribedEvents === "*"
                              ? "全部事件 (*)"
                              : ch.subscribedEvents}
                          </TableCell>
                          <TableCell>
                            <Switch
                              checked={ch.enabled}
                              onCheckedChange={(checked) =>
                                void handleToggleEnabled(ch, checked)
                              }
                              disabled={busyChannelId === ch.id}
                              aria-label={`切换 ${ch.name} 启用状态`}
                            />
                          </TableCell>
                          <TableCell className="text-right">
                            <div className="inline-flex items-center gap-1">
                              <Button
                                type="button"
                                variant="outline"
                                size="xs"
                                disabled={testingChannelId === ch.id}
                                onClick={() => void handleTestChannel(ch)}
                              >
                                <SendIcon />
                                <span>
                                  {testingChannelId === ch.id
                                    ? "发送中..."
                                    : "发送测试"}
                                </span>
                              </Button>
                              <Button
                                type="button"
                                variant="ghost"
                                size="xs"
                                onClick={() => openEditDialog(ch)}
                              >
                                <PencilIcon />
                                <span>编辑</span>
                              </Button>
                              <Button
                                type="button"
                                variant="ghost"
                                size="xs"
                                className="text-destructive hover:text-destructive"
                                onClick={() => setDeleteTarget(ch)}
                              >
                                <Trash2Icon />
                                <span>删除</span>
                              </Button>
                            </div>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              )}
            </TabsContent>

            <TabsContent value="deliveries" className="mt-4 space-y-4">
              <div className="flex flex-wrap items-center gap-2">
                <Select
                  value={deliveryStatusFilter}
                  items={{
                    ALL: "全部状态",
                    SENT: ALERT_DELIVERY_STATUS_LABEL.SENT,
                    FAILED: ALERT_DELIVERY_STATUS_LABEL.FAILED,
                    SKIPPED: ALERT_DELIVERY_STATUS_LABEL.SKIPPED,
                  }}
                  onValueChange={(v) => {
                    if (v) {
                      setDeliveryPage(1);
                      setDeliveryStatusFilter(v as AlertDeliveryStatus | "ALL");
                    }
                  }}
                >
                  <SelectTrigger className="w-32">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ALL">全部状态</SelectItem>
                    <SelectItem value="SENT">
                      {ALERT_DELIVERY_STATUS_LABEL.SENT}
                    </SelectItem>
                    <SelectItem value="FAILED">
                      {ALERT_DELIVERY_STATUS_LABEL.FAILED}
                    </SelectItem>
                    <SelectItem value="SKIPPED">
                      {ALERT_DELIVERY_STATUS_LABEL.SKIPPED}
                    </SelectItem>
                  </SelectContent>
                </Select>

                <Select
                  value={deliveryChannelFilter}
                  items={{
                    ALL: "全部渠道",
                    ...Object.fromEntries(
                      (channels ?? []).map((c) => [c.id, c.name])
                    ),
                  }}
                  onValueChange={(v) => {
                    if (v) {
                      setDeliveryPage(1);
                      setDeliveryChannelFilter(v);
                    }
                  }}
                >
                  <SelectTrigger className="w-48">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="ALL">全部渠道</SelectItem>
                    {(channels ?? []).map((c) => (
                      <SelectItem key={c.id} value={c.id}>
                        {c.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <ApiErrorAlert
                error={deliveriesError}
                title="读取投递记录失败"
              />

              {loadingDeliveries && !deliveriesPage ? (
                <Skeleton className="h-64 w-full rounded-surface-md" />
              ) : !deliveriesPage || deliveriesPage.items.length === 0 ? (
                <div className="rounded-surface-md border border-dashed p-10 text-center text-sm text-muted-foreground">
                  暂无符合条件的告警投递记录。
                </div>
              ) : (
                <>
                  <div className="rounded-surface-md border bg-card">
                    <Table>
                      <TableHeader>
                        <TableRow>
                          <TableHead>时间</TableHead>
                          <TableHead>渠道</TableHead>
                          <TableHead>事件与级别</TableHead>
                          <TableHead>告警标题</TableHead>
                          <TableHead>状态</TableHead>
                          <TableHead className="text-right">重试</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {deliveriesPage.items.map((item) => (
                          <TableRow
                            key={item.id}
                            className="cursor-pointer"
                            onClick={() => setDetailDelivery(item)}
                          >
                            <TableCell className="text-xs text-muted-foreground tabular-nums">
                              {formatDateTime(item.createdAt)}
                            </TableCell>
                            <TableCell className="text-xs">
                              {item.channelName}
                            </TableCell>
                            <TableCell>
                              <div className="flex flex-wrap items-center gap-1.5">
                                <Badge variant="outline" className="font-mono text-[10px]">
                                  {item.eventType}
                                </Badge>
                                <Badge variant={severityBadgeVariant(item.severity)}>
                                  {item.severity}
                                </Badge>
                              </div>
                            </TableCell>
                            <TableCell className="max-w-xs truncate text-xs">
                              {item.title}
                            </TableCell>
                            <TableCell>
                              <div className="flex items-center gap-1.5">
                                <Badge
                                  variant={deliveryStatusVariant(item.status)}
                                >
                                  {ALERT_DELIVERY_STATUS_LABEL[item.status]}
                                </Badge>
                                {item.httpStatus ? (
                                  <span className="font-mono text-[11px] text-muted-foreground">
                                    {item.httpStatus}
                                  </span>
                                ) : null}
                              </div>
                            </TableCell>
                            <TableCell className="text-right font-mono text-xs tabular-nums">
                              {item.attemptCount}
                            </TableCell>
                          </TableRow>
                        ))}
                      </TableBody>
                    </Table>
                  </div>

                  <div className="flex items-center justify-between text-xs text-muted-foreground">
                    <span className="tabular-nums">
                      第 {deliveriesPage.page} / {totalDeliveryPages} 页 · 共{" "}
                      {deliveriesPage.total} 条
                    </span>
                    <div className="flex items-center gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={deliveryPage <= 1 || loadingDeliveries}
                        onClick={() =>
                          setDeliveryPage((p) => Math.max(1, p - 1))
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
                          deliveryPage >= totalDeliveryPages ||
                          loadingDeliveries
                        }
                        onClick={() =>
                          setDeliveryPage((p) =>
                            Math.min(totalDeliveryPages, p + 1)
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

      {/* Create / Edit Channel Dialog */}
      <Dialog open={formOpen} onOpenChange={setFormOpen}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {editingChannel ? "编辑告警渠道" : "新建告警渠道"}
            </DialogTitle>
            <DialogDescription>
              支持公网 HTTPS Webhook（带 HMAC-SHA256 防篡改签名）或 SMTP 邮件列表。
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3 py-1">
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="alert-ch-name">渠道名称</Label>
                <Input
                  id="alert-ch-name"
                  maxLength={100}
                  value={form.name}
                  onChange={(e) =>
                    setForm((prev) => ({ ...prev, name: e.target.value }))
                  }
                  placeholder="例如：值班飞书机器人"
                />
              </div>

              <div className="space-y-1.5">
                <Label>渠道类型</Label>
                <Select
                  value={form.channelType}
                  items={ALERT_CHANNEL_TYPE_LABEL}
                  onValueChange={(v) => {
                    if (v) {
                      const nextType = v as AlertChannelType;
                      setForm((prev) => ({
                        ...prev,
                        channelType: nextType,
                        target:
                          editingChannel || prev.target !== "https://"
                            ? prev.target
                            : nextType === "EMAIL"
                              ? ""
                              : "https://",
                      }));
                    }
                  }}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="WEBHOOK">
                      {ALERT_CHANNEL_TYPE_LABEL.WEBHOOK}
                    </SelectItem>
                    <SelectItem value="EMAIL">
                      {ALERT_CHANNEL_TYPE_LABEL.EMAIL}
                    </SelectItem>
                  </SelectContent>
                </Select>
              </div>
            </div>

            <div className="space-y-1.5">
              <Label htmlFor="alert-ch-target">
                {form.channelType === "WEBHOOK"
                  ? "Webhook URL（须公网 HTTP/HTTPS）"
                  : "收件人邮箱列表（逗号分隔）"}
              </Label>
              <Input
                id="alert-ch-target"
                maxLength={1000}
                value={form.target}
                onChange={(e) =>
                  setForm((prev) => ({ ...prev, target: e.target.value }))
                }
                placeholder={
                  form.channelType === "WEBHOOK"
                    ? "https://open.feishu.cn/open-apis/bot/v2/hook/..."
                    : "oncall@example.com, ops@example.com"
                }
              />
            </div>

            {form.channelType === "WEBHOOK" ? (
              <div className="space-y-1.5">
                <Label htmlFor="alert-ch-secret">
                  Webhook 签名密钥（选填，写入 X-VisionForge-Signature）
                </Label>
                <Input
                  id="alert-ch-secret"
                  type="password"
                  maxLength={500}
                  value={form.secret}
                  onChange={(e) =>
                    setForm((prev) => ({ ...prev, secret: e.target.value }))
                  }
                  placeholder={
                    editingChannel?.secretConfigured
                      ? "已配置密钥（留空保持原密钥不变）"
                      : "可选：输入用于校验请求的 HMAC 密钥"
                  }
                />
              </div>
            ) : null}

            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label>最低触发级别</Label>
                <Select
                  value={form.minSeverity}
                  items={ALERT_SEVERITY_LABEL}
                  onValueChange={(v) => {
                    if (v) {
                      setForm((prev) => ({
                        ...prev,
                        minSeverity: v as AlertSeverity,
                      }));
                    }
                  }}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="INFO">
                      {ALERT_SEVERITY_LABEL.INFO}
                    </SelectItem>
                    <SelectItem value="WARNING">
                      {ALERT_SEVERITY_LABEL.WARNING}
                    </SelectItem>
                    <SelectItem value="CRITICAL">
                      {ALERT_SEVERITY_LABEL.CRITICAL}
                    </SelectItem>
                  </SelectContent>
                </Select>
              </div>

              <div className="flex items-end justify-between rounded-surface-sm bg-muted/20 px-3 py-2">
                <div>
                  <p className="text-xs font-medium">启用该渠道</p>
                  <p className="text-[11px] text-muted-foreground">
                    停用后不会投递任何告警
                  </p>
                </div>
                <Switch
                  checked={form.enabled}
                  onCheckedChange={(checked) =>
                    setForm((prev) => ({ ...prev, enabled: checked }))
                  }
                />
              </div>
            </div>

            <div className="space-y-2 rounded-surface-sm bg-muted/20 p-3">
              <div className="flex items-center justify-between gap-2">
                <span className="text-xs font-medium">订阅告警事件类型</span>
                <label className="flex cursor-pointer items-center gap-1.5 text-xs">
                  <Checkbox
                    checked={form.allEvents}
                    onCheckedChange={(checked) =>
                      setForm((prev) => ({
                        ...prev,
                        allEvents: Boolean(checked),
                      }))
                    }
                  />
                  <span>订阅全部事件 (*)</span>
                </label>
              </div>
              {!form.allEvents ? (
                <div className="grid gap-2 pt-1 sm:grid-cols-2">
                  {ALERT_EVENT_OPTIONS.map((opt) => {
                    const checked = form.selectedEvents.includes(opt.id);
                    return (
                      <label
                        key={opt.id}
                        className="flex cursor-pointer items-center gap-2 text-xs"
                      >
                        <Checkbox
                          checked={checked}
                          onCheckedChange={(next) =>
                            setForm((prev) => ({
                              ...prev,
                              selectedEvents: next
                                ? Array.from(
                                    new Set([...prev.selectedEvents, opt.id])
                                  )
                                : prev.selectedEvents.filter((x) => x !== opt.id),
                            }))
                          }
                        />
                        <span className="truncate">{opt.label}</span>
                      </label>
                    );
                  })}
                </div>
              ) : null}
            </div>
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={savingChannel}
              onClick={() => setFormOpen(false)}
            >
              取消
            </Button>
            <Button
              type="button"
              disabled={savingChannel}
              onClick={() => void handleSaveChannel()}
            >
              {savingChannel ? "保存中..." : "保存渠道"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete Confirm */}
      <AlertDialog
        open={deleteTarget !== null}
        onOpenChange={(open) => {
          if (!open && !deletingChannel) {
            setDeleteTarget(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除告警渠道</AlertDialogTitle>
            <AlertDialogDescription>
              确定永久删除渠道「{deleteTarget?.name}」及其相关配置？
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={deletingChannel}>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                event.preventDefault();
                void handleConfirmDelete();
              }}
              disabled={deletingChannel}
            >
              {deletingChannel ? "删除中..." : "确认删除"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      {/* Delivery Detail Dialog */}
      <Dialog
        open={detailDelivery !== null}
        onOpenChange={(open) => {
          if (!open) {
            setDetailDelivery(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>{detailDelivery?.title}</DialogTitle>
            <DialogDescription className="font-mono text-xs">
              {detailDelivery?.eventType} · {detailDelivery?.channelName} ·{" "}
              {formatDateTime(detailDelivery?.createdAt)}
            </DialogDescription>
          </DialogHeader>
          {detailDelivery ? (
            <div className="space-y-3 text-xs">
              <div className="flex flex-wrap gap-2">
                <Badge variant={deliveryStatusVariant(detailDelivery.status)}>
                  {ALERT_DELIVERY_STATUS_LABEL[detailDelivery.status]}
                </Badge>
                <Badge variant={severityBadgeVariant(detailDelivery.severity)}>
                  {detailDelivery.severity}
                </Badge>
                {detailDelivery.httpStatus ? (
                  <Badge variant="outline">
                    HTTP {detailDelivery.httpStatus}
                  </Badge>
                ) : null}
                <Badge variant="outline">
                  尝试 {detailDelivery.attemptCount} 次
                </Badge>
              </div>

              {detailDelivery.summary ? (
                <div className="space-y-1">
                  <p className="font-medium text-muted-foreground">摘要信息</p>
                  <p className="rounded-surface-sm bg-muted/30 p-3 leading-relaxed">
                    {detailDelivery.summary}
                  </p>
                </div>
              ) : null}

              {detailDelivery.errorMessage ? (
                <div className="space-y-1">
                  <p className="font-medium text-destructive">失败原因</p>
                  <pre className="overflow-x-auto rounded-surface-sm border border-destructive/30 bg-destructive/5 p-3 font-mono text-[11px] whitespace-pre-wrap text-destructive">
                    {detailDelivery.errorMessage}
                  </pre>
                </div>
              ) : null}

              <div className="grid gap-1 font-mono text-[11px] text-muted-foreground">
                <div>bizNo: {detailDelivery.bizNo ?? "—"}</div>
                <div>traceId: {detailDelivery.traceId ?? "—"}</div>
              </div>
            </div>
          ) : null}
        </DialogContent>
      </Dialog>
    </div>
  );
}
