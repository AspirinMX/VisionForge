"use client";

import { useMemo, useState, type ReactNode } from "react";
import { RefreshCwIcon, RotateCcwIcon, SaveIcon } from "lucide-react";
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
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminConfigsApi } from "@/lib/api/endpoints";
import { notifyApiError, notifyError } from "@/lib/api/feedback";
import type {
  ConfigCenterOverviewResponse,
  ConfigEntryResponse,
} from "@/lib/api/types";

const DATA_ID = {
  runtime: "visionforge-runtime.json",
  security: "visionforge-security.json",
  billing: "visionforge-billing.json",
  alert: "visionforge-alert.json",
} as const;

type SettingsGroup = "registration" | "rateLimit" | "context" | "alert";

interface SettingsForm {
  registrationEnabled: boolean;
  initialCreditUnits: string;
  rateLimitEnabled: boolean;
  rateLimitAuthPerMinute: string;
  rateLimitRedeemPerMinute: string;
  rateLimitGenerationPerMinute: string;
  rateLimitUploadPerMinute: string;
  contextCompactThresholdPercent: string;
  contextCompactKeepRatioPercent: string;
  contextSummaryMaxOutputTokens: string;
  streamTimeoutSeconds: string;
  alertsEnabled: boolean;
  smtpHost: string;
  smtpPort: string;
  smtpUsername: string;
  smtpFrom: string;
  smtpSslEnabled: boolean;
  webhookAllowPrivateNetwork: boolean;
}

type RawValues = Record<string, unknown>;

const DEFAULT_FORM: SettingsForm = {
  registrationEnabled: true,
  initialCreditUnits: "10000",
  rateLimitEnabled: true,
  rateLimitAuthPerMinute: "20",
  rateLimitRedeemPerMinute: "10",
  rateLimitGenerationPerMinute: "30",
  rateLimitUploadPerMinute: "30",
  contextCompactThresholdPercent: "80",
  contextCompactKeepRatioPercent: "25",
  contextSummaryMaxOutputTokens: "1024",
  streamTimeoutSeconds: "300",
  alertsEnabled: true,
  smtpHost: "",
  smtpPort: "587",
  smtpUsername: "",
  smtpFrom: "",
  smtpSslEnabled: true,
  webhookAllowPrivateNetwork: false,
};

function parseEntry(entry: ConfigEntryResponse | undefined): RawValues {
  if (!entry) {
    return {};
  }
  try {
    const parsed: unknown = JSON.parse(entry.content);
    return parsed && typeof parsed === "object"
      ? (parsed as RawValues)
      : {};
  } catch {
    return {};
  }
}

function asNumber(value: unknown, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value)
    ? value
    : fallback;
}

function asBoolean(value: unknown, fallback: boolean): boolean {
  return typeof value === "boolean" ? value : fallback;
}

function asText(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function buildForm(entries: RawValues[]): SettingsForm {
  const [runtime, security, billing, alert] = entries;
  return {
    registrationEnabled: asBoolean(security.registrationEnabled, true),
    initialCreditUnits: String(
      asNumber(billing.initialCreditUnits, 10000)
    ),
    rateLimitEnabled: asBoolean(security.rateLimitEnabled, true),
    rateLimitAuthPerMinute: String(
      asNumber(security.rateLimitAuthPerMinute, 20)
    ),
    rateLimitRedeemPerMinute: String(
      asNumber(security.rateLimitRedeemPerMinute, 10)
    ),
    rateLimitGenerationPerMinute: String(
      asNumber(security.rateLimitGenerationPerMinute, 30)
    ),
    rateLimitUploadPerMinute: String(
      asNumber(security.rateLimitUploadPerMinute, 30)
    ),
    contextCompactThresholdPercent: String(
      Math.round(asNumber(runtime.contextCompactThreshold, 0.8) * 100)
    ),
    contextCompactKeepRatioPercent: String(
      Math.round(asNumber(runtime.contextCompactKeepRatio, 0.25) * 100)
    ),
    contextSummaryMaxOutputTokens: String(
      asNumber(runtime.contextSummaryMaxOutputTokens, 1024)
    ),
    streamTimeoutSeconds: String(
      Math.round(asNumber(runtime.streamTimeoutMs, 300000) / 1000)
    ),
    alertsEnabled: asBoolean(alert.alertsEnabled, true),
    smtpHost: asText(alert.smtpHost),
    smtpPort: String(asNumber(alert.smtpPort, 587)),
    smtpUsername: asText(alert.smtpUsername),
    smtpFrom: asText(alert.smtpFrom),
    smtpSslEnabled: asBoolean(alert.smtpSslEnabled, true),
    webhookAllowPrivateNetwork: asBoolean(
      alert.webhookAllowPrivateNetwork,
      false
    ),
  };
}

const GROUP_FIELDS: Record<SettingsGroup, (keyof SettingsForm)[]> = {
  registration: ["registrationEnabled", "initialCreditUnits"],
  rateLimit: [
    "rateLimitEnabled",
    "rateLimitAuthPerMinute",
    "rateLimitRedeemPerMinute",
    "rateLimitGenerationPerMinute",
    "rateLimitUploadPerMinute",
  ],
  context: [
    "contextCompactThresholdPercent",
    "contextCompactKeepRatioPercent",
    "contextSummaryMaxOutputTokens",
    "streamTimeoutSeconds",
  ],
  alert: [
    "alertsEnabled",
    "smtpHost",
    "smtpPort",
    "smtpUsername",
    "smtpFrom",
    "smtpSslEnabled",
    "webhookAllowPrivateNetwork",
  ],
};

const GROUP_SAVED_MESSAGE: Record<SettingsGroup, string> = {
  registration: "注册与新用户配额已保存",
  rateLimit: "安全防护与接口限流已保存",
  context: "对话与上下文策略已保存",
  alert: "告警与邮件设置已保存",
};

function toInteger(value: string): number | null {
  if (!/^\d+$/.test(value.trim())) {
    return null;
  }
  return Number(value);
}

function validateGroup(
  group: SettingsGroup,
  form: SettingsForm
): string | null {
  if (group === "registration") {
    const credit = toInteger(form.initialCreditUnits);
    if (credit === null) {
      return "新用户初始赠送额度必须为非负整数";
    }
  }

  if (group === "rateLimit") {
    for (const key of GROUP_FIELDS.rateLimit.slice(1)) {
      const value = toInteger(String(form[key]));
      if (value === null || value <= 0) {
        return "限流频率必须为正整数";
      }
    }
  }

  if (group === "context") {
    const threshold = toInteger(form.contextCompactThresholdPercent);
    if (threshold === null || threshold < 10 || threshold > 95) {
      return "压缩触发阈值需在 10%–95% 之间";
    }
    const keep = toInteger(form.contextCompactKeepRatioPercent);
    if (keep === null || keep < 5 || keep > 60) {
      return "压缩保留比例需在 5%–60% 之间";
    }
    const tokens = toInteger(form.contextSummaryMaxOutputTokens);
    if (tokens === null || tokens <= 0) {
      return "历史摘要最大输出长度必须为正整数";
    }
    const timeout = toInteger(form.streamTimeoutSeconds);
    if (timeout === null || timeout <= 0) {
      return "流式响应超时必须为正整数秒";
    }
  }

  if (group === "alert") {
    const port = toInteger(form.smtpPort);
    if (port === null || port < 1 || port > 65535) {
      return "SMTP 端口需在 1–65535 之间";
    }
    if (
      form.smtpFrom.trim() &&
      !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(form.smtpFrom.trim())
    ) {
      return "发件人地址格式不正确";
    }
  }

  return null;
}

function isGroupDirty(
  group: SettingsGroup,
  form: SettingsForm,
  initial: SettingsForm
): boolean {
  return GROUP_FIELDS[group].some(
    (key) => String(form[key]) !== String(initial[key])
  );
}

interface GroupCardProps {
  title: string;
  description: string;
  dirty: boolean;
  saving: boolean;
  onSave: () => void;
  onReset: () => void;
  children: ReactNode;
}

function GroupCard({
  title,
  description,
  dirty,
  saving,
  onSave,
  onReset,
  children,
}: GroupCardProps) {
  return (
    <Card>
      <CardHeader>
        <div className="flex flex-wrap items-start justify-between gap-2">
          <div className="space-y-1">
            <CardTitle className="text-base">{title}</CardTitle>
            <CardDescription>{description}</CardDescription>
          </div>
          {dirty ? (
            <span className="text-xs text-muted-foreground">
              有未保存修改
            </span>
          ) : null}
        </div>
      </CardHeader>
      <CardContent className="space-y-5">{children}</CardContent>
      <CardFooter className="justify-end gap-2 border-t pt-4">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={saving}
          onClick={onReset}
        >
          <RotateCcwIcon />
          <span>恢复推荐默认值</span>
        </Button>
        <Button
          type="button"
          size="sm"
          disabled={!dirty || saving}
          onClick={onSave}
        >
          <SaveIcon />
          <span>{saving ? "保存中..." : "保存设置"}</span>
        </Button>
      </CardFooter>
    </Card>
  );
}

export default function AdminSettingsPage() {
  const { data, loading, error, reload, mutate } = useAsyncData(
    () => adminConfigsApi.list(),
    "admin-system-settings",
    { errorMessage: "读取系统参数失败" }
  );

  const bases = useMemo(() => {
    const result: Record<string, RawValues> = {};
    for (const entry of data?.entries ?? []) {
      result[entry.dataId] = parseEntry(entry);
    }
    return result;
  }, [data]);

  const initialForm = useMemo(() => {
    if (!data) {
      return DEFAULT_FORM;
    }
    return buildForm([
      bases[DATA_ID.runtime] ?? {},
      bases[DATA_ID.security] ?? {},
      bases[DATA_ID.billing] ?? {},
      bases[DATA_ID.alert] ?? {},
    ]);
  }, [bases, data]);

  const [draft, setDraft] = useState<SettingsForm | null>(null);
  const [savingGroup, setSavingGroup] = useState<SettingsGroup | null>(null);
  const [resetTarget, setResetTarget] = useState<SettingsGroup | null>(null);
  const [webhookConfirmOpen, setWebhookConfirmOpen] = useState(false);

  const form = draft ?? initialForm;
  const saving = savingGroup !== null;

  const update = <K extends keyof SettingsForm>(
    key: K,
    value: SettingsForm[K]
  ) => {
    setDraft((prev) => ({ ...(prev ?? initialForm), [key]: value }));
  };

  const entryUpdate = (updated: ConfigEntryResponse) => {
    mutate((prev: ConfigCenterOverviewResponse | null) =>
      prev
        ? {
            ...prev,
            entries: prev.entries.map((item) =>
              item.dataId === updated.dataId ? updated : item
            ),
          }
        : prev
    );
  };

  const publishEntry = async (
    dataId: string,
    payload: RawValues
  ): Promise<ConfigEntryResponse> =>
    adminConfigsApi.publish(dataId, {
      content: JSON.stringify(payload, null, 2),
    });

  const handleSave = async (group: SettingsGroup) => {
    if (saving) {
      return;
    }
    const validationError = validateGroup(group, form);
    if (validationError) {
      notifyError(validationError);
      return;
    }
    setSavingGroup(group);
    try {
      const securityBase = bases[DATA_ID.security] ?? {};
      const billingBase = bases[DATA_ID.billing] ?? {};
      const runtimeBase = bases[DATA_ID.runtime] ?? {};
      const alertBase = bases[DATA_ID.alert] ?? {};

      const requests: Promise<ConfigEntryResponse>[] = [];
      if (group === "registration") {
        requests.push(
          publishEntry(DATA_ID.security, {
            ...securityBase,
            registrationEnabled: form.registrationEnabled,
          }),
          publishEntry(DATA_ID.billing, {
            ...billingBase,
            initialCreditUnits: Number(form.initialCreditUnits),
          })
        );
      } else if (group === "rateLimit") {
        requests.push(
          publishEntry(DATA_ID.security, {
            ...securityBase,
            rateLimitEnabled: form.rateLimitEnabled,
            rateLimitAuthPerMinute: Number(form.rateLimitAuthPerMinute),
            rateLimitRedeemPerMinute: Number(form.rateLimitRedeemPerMinute),
            rateLimitGenerationPerMinute: Number(
              form.rateLimitGenerationPerMinute
            ),
            rateLimitUploadPerMinute: Number(form.rateLimitUploadPerMinute),
          })
        );
      } else if (group === "context") {
        requests.push(
          publishEntry(DATA_ID.runtime, {
            ...runtimeBase,
            contextCompactThreshold:
              Number(form.contextCompactThresholdPercent) / 100,
            contextCompactKeepRatio:
              Number(form.contextCompactKeepRatioPercent) / 100,
            contextSummaryMaxOutputTokens: Number(
              form.contextSummaryMaxOutputTokens
            ),
            streamTimeoutMs: Number(form.streamTimeoutSeconds) * 1000,
          })
        );
      } else {
        requests.push(
          publishEntry(DATA_ID.alert, {
            ...alertBase,
            alertsEnabled: form.alertsEnabled,
            smtpHost: form.smtpHost.trim(),
            smtpPort: Number(form.smtpPort),
            smtpUsername: form.smtpUsername.trim(),
            smtpFrom: form.smtpFrom.trim(),
            smtpSslEnabled: form.smtpSslEnabled,
            webhookAllowPrivateNetwork: form.webhookAllowPrivateNetwork,
          })
        );
      }

      const updatedEntries = await Promise.all(requests);
      updatedEntries.forEach(entryUpdate);
      setDraft(null);
      toast.success(GROUP_SAVED_MESSAGE[group]);
    } catch (err) {
      notifyApiError(err, "保存系统参数失败");
    } finally {
      setSavingGroup(null);
    }
  };

  const handleReset = async () => {
    if (!resetTarget || saving) {
      return;
    }
    const target = resetTarget;
    setSavingGroup(target);
    try {
      const securityBase = bases[DATA_ID.security] ?? {};
      const billingBase = bases[DATA_ID.billing] ?? {};
      const runtimeBase = bases[DATA_ID.runtime] ?? {};
      const alertBase = bases[DATA_ID.alert] ?? {};

      const requests: Promise<ConfigEntryResponse>[] = [];
      if (target === "registration") {
        requests.push(
          publishEntry(DATA_ID.security, {
            ...securityBase,
            registrationEnabled: DEFAULT_FORM.registrationEnabled,
          }),
          publishEntry(DATA_ID.billing, {
            ...billingBase,
            initialCreditUnits: Number(DEFAULT_FORM.initialCreditUnits),
          })
        );
      } else if (target === "rateLimit") {
        requests.push(
          publishEntry(DATA_ID.security, {
            ...securityBase,
            rateLimitEnabled: DEFAULT_FORM.rateLimitEnabled,
            rateLimitAuthPerMinute: Number(
              DEFAULT_FORM.rateLimitAuthPerMinute
            ),
            rateLimitRedeemPerMinute: Number(
              DEFAULT_FORM.rateLimitRedeemPerMinute
            ),
            rateLimitGenerationPerMinute: Number(
              DEFAULT_FORM.rateLimitGenerationPerMinute
            ),
            rateLimitUploadPerMinute: Number(
              DEFAULT_FORM.rateLimitUploadPerMinute
            ),
          })
        );
      } else if (target === "context") {
        requests.push(
          publishEntry(DATA_ID.runtime, {
            ...runtimeBase,
            contextCompactThreshold:
              Number(DEFAULT_FORM.contextCompactThresholdPercent) / 100,
            contextCompactKeepRatio:
              Number(DEFAULT_FORM.contextCompactKeepRatioPercent) / 100,
            contextSummaryMaxOutputTokens: Number(
              DEFAULT_FORM.contextSummaryMaxOutputTokens
            ),
            streamTimeoutMs: Number(DEFAULT_FORM.streamTimeoutSeconds) * 1000,
          })
        );
      } else {
        requests.push(
          publishEntry(DATA_ID.alert, {
            ...alertBase,
            alertsEnabled: DEFAULT_FORM.alertsEnabled,
            smtpHost: DEFAULT_FORM.smtpHost,
            smtpPort: Number(DEFAULT_FORM.smtpPort),
            smtpUsername: DEFAULT_FORM.smtpUsername,
            smtpFrom: DEFAULT_FORM.smtpFrom,
            smtpSslEnabled: DEFAULT_FORM.smtpSslEnabled,
            webhookAllowPrivateNetwork:
              DEFAULT_FORM.webhookAllowPrivateNetwork,
          })
        );
      }

      const updatedEntries = await Promise.all(requests);
      updatedEntries.forEach(entryUpdate);
      setDraft(null);
      setResetTarget(null);
      toast.success("已恢复推荐默认值");
    } catch (err) {
      notifyApiError(err, "恢复默认值失败");
    } finally {
      setSavingGroup(null);
    }
  };

  const offline = Boolean(data && (!data.enabled || !data.connected));

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="系统参数配置"
        actions={
          <Button
            type="button"
            variant="ghost"
            size="icon-sm"
            onClick={reload}
            disabled={loading}
            aria-label="刷新系统参数"
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
        <div className="mx-auto w-full max-w-4xl space-y-4">
          <ApiErrorAlert error={error} title="读取系统参数失败" />

          {offline ? (
            <p className="rounded-surface-sm bg-muted/30 px-3 py-2 text-xs text-muted-foreground">
              当前处于本地运行配置模式，修改将在服务恢复同步后由后端接管。
            </p>
          ) : null}

          {loading && !data ? (
            <div className="space-y-4">
              <Skeleton className="h-56 w-full rounded-surface-md" />
              <Skeleton className="h-56 w-full rounded-surface-md" />
            </div>
          ) : (
            <>
              <GroupCard
                title="注册与新用户配额"
                description="控制自助注册入口和新账号获得的基础额度。"
                dirty={isGroupDirty("registration", form, initialForm)}
                saving={savingGroup === "registration"}
                onSave={() => void handleSave("registration")}
                onReset={() => setResetTarget("registration")}
              >
                <div className="flex items-center justify-between gap-4">
                  <div className="space-y-1">
                    <p className="text-sm font-medium">开放新用户自助注册</p>
                    <p className="text-xs text-muted-foreground">
                      关闭后仅管理员可以创建账号，不影响已有用户登录。
                    </p>
                  </div>
                  <Switch
                    checked={form.registrationEnabled}
                    onCheckedChange={(checked) =>
                      update("registrationEnabled", checked)
                    }
                    disabled={saving}
                  />
                </div>

                <div className="space-y-1.5">
                  <Label htmlFor="setting-initial-credit">
                    新用户初始赠送额度
                  </Label>
                  <Input
                    id="setting-initial-credit"
                    type="number"
                    min={0}
                    step={1}
                    value={form.initialCreditUnits}
                    onChange={(event) =>
                      update("initialCreditUnits", event.target.value)
                    }
                    disabled={saving}
                  />
                  <p className="text-[11px] text-muted-foreground">
                    单位：额度，允许为 0。
                  </p>
                </div>
              </GroupCard>

              <GroupCard
                title="安全防护与接口限流"
                description="限制登录、兑换、生成提交与素材上传的请求频率。"
                dirty={isGroupDirty("rateLimit", form, initialForm)}
                saving={savingGroup === "rateLimit"}
                onSave={() => void handleSave("rateLimit")}
                onReset={() => setResetTarget("rateLimit")}
              >
                <div className="flex items-center justify-between gap-4">
                  <div className="space-y-1">
                    <p className="text-sm font-medium">启用全站接口限流</p>
                    <p className="text-xs text-muted-foreground">
                      关闭后下方频率设置将暂停生效。
                    </p>
                  </div>
                  <Switch
                    checked={form.rateLimitEnabled}
                    onCheckedChange={(checked) =>
                      update("rateLimitEnabled", checked)
                    }
                    disabled={saving}
                  />
                </div>

                <div className="grid gap-4 sm:grid-cols-2">
                  {(
                    [
                      ["rateLimitAuthPerMinute", "登录与注册频率上限"],
                      ["rateLimitRedeemPerMinute", "兑换码核销频率上限"],
                      [
                        "rateLimitGenerationPerMinute",
                        "媒体生成提交频率上限",
                      ],
                      ["rateLimitUploadPerMinute", "素材上传频率上限"],
                    ] as const
                  ).map(([key, label]) => (
                    <div key={key} className="space-y-1.5">
                      <Label htmlFor={`setting-${key}`}>{label}</Label>
                      <Input
                        id={`setting-${key}`}
                        type="number"
                        min={1}
                        step={1}
                        value={String(form[key])}
                        onChange={(event) => update(key, event.target.value)}
                        disabled={saving || !form.rateLimitEnabled}
                      />
                      <p className="text-[11px] text-muted-foreground">
                        次 / 分钟
                      </p>
                    </div>
                  ))}
                </div>
              </GroupCard>

              <GroupCard
                title="AI 对话与上下文策略"
                description="控制长对话的压缩时机、保留比例和单轮流式超时。"
                dirty={isGroupDirty("context", form, initialForm)}
                saving={savingGroup === "context"}
                onSave={() => void handleSave("context")}
                onReset={() => setResetTarget("context")}
              >
                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <Label htmlFor="setting-context-threshold">
                      上下文自动压缩触发阈值
                    </Label>
                    <Input
                      id="setting-context-threshold"
                      type="number"
                      min={10}
                      max={95}
                      step={1}
                      value={form.contextCompactThresholdPercent}
                      onChange={(event) =>
                        update(
                          "contextCompactThresholdPercent",
                          event.target.value
                        )
                      }
                      disabled={saving}
                    />
                    <p className="text-[11px] text-muted-foreground">
                      百分比，推荐 80%。
                    </p>
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="setting-context-keep">
                      压缩后保留近期对话比例
                    </Label>
                    <Input
                      id="setting-context-keep"
                      type="number"
                      min={5}
                      max={60}
                      step={1}
                      value={form.contextCompactKeepRatioPercent}
                      onChange={(event) =>
                        update(
                          "contextCompactKeepRatioPercent",
                          event.target.value
                        )
                      }
                      disabled={saving}
                    />
                    <p className="text-[11px] text-muted-foreground">
                      百分比，推荐 25%。
                    </p>
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="setting-summary-tokens">
                      历史摘要最大输出长度
                    </Label>
                    <Input
                      id="setting-summary-tokens"
                      type="number"
                      min={1}
                      step={1}
                      value={form.contextSummaryMaxOutputTokens}
                      onChange={(event) =>
                        update(
                          "contextSummaryMaxOutputTokens",
                          event.target.value
                        )
                      }
                      disabled={saving}
                    />
                    <p className="text-[11px] text-muted-foreground">
                      单位：Tokens。
                    </p>
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="setting-stream-timeout">
                      单轮对话流式响应超时
                    </Label>
                    <Input
                      id="setting-stream-timeout"
                      type="number"
                      min={1}
                      step={1}
                      value={form.streamTimeoutSeconds}
                      onChange={(event) =>
                        update("streamTimeoutSeconds", event.target.value)
                      }
                      disabled={saving}
                    />
                    <p className="text-[11px] text-muted-foreground">
                      单位：秒，推荐 300 秒。
                    </p>
                  </div>
                </div>
              </GroupCard>

              <GroupCard
                title="全局告警与邮件服务器"
                description="配置异常告警的邮件投递方式与 Webhook 网络安全边界。"
                dirty={isGroupDirty("alert", form, initialForm)}
                saving={savingGroup === "alert"}
                onSave={() => void handleSave("alert")}
                onReset={() => setResetTarget("alert")}
              >
                <div className="flex items-center justify-between gap-4">
                  <div className="space-y-1">
                    <p className="text-sm font-medium">启用全站异常告警投递</p>
                    <p className="text-xs text-muted-foreground">
                      关闭后仍会记录告警事件，但不会发送邮件。
                    </p>
                  </div>
                  <Switch
                    checked={form.alertsEnabled}
                    onCheckedChange={(checked) =>
                      update("alertsEnabled", checked)
                    }
                    disabled={saving}
                  />
                </div>

                <div className="grid gap-4 sm:grid-cols-2">
                  <div className="space-y-1.5">
                    <Label htmlFor="setting-smtp-host">SMTP 服务器地址</Label>
                    <Input
                      id="setting-smtp-host"
                      value={form.smtpHost}
                      onChange={(event) =>
                        update("smtpHost", event.target.value)
                      }
                      placeholder="smtp.example.com"
                      disabled={saving || !form.alertsEnabled}
                    />
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="setting-smtp-port">SMTP 端口</Label>
                    <Input
                      id="setting-smtp-port"
                      type="number"
                      min={1}
                      max={65535}
                      value={form.smtpPort}
                      onChange={(event) =>
                        update("smtpPort", event.target.value)
                      }
                      disabled={saving || !form.alertsEnabled}
                    />
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="setting-smtp-username">
                      发信认证账号
                    </Label>
                    <Input
                      id="setting-smtp-username"
                      value={form.smtpUsername}
                      onChange={(event) =>
                        update("smtpUsername", event.target.value)
                      }
                      placeholder="oncall@example.com"
                      disabled={saving || !form.alertsEnabled}
                    />
                  </div>

                  <div className="space-y-1.5">
                    <Label htmlFor="setting-smtp-from">发件人展示地址</Label>
                    <Input
                      id="setting-smtp-from"
                      type="email"
                      value={form.smtpFrom}
                      onChange={(event) =>
                        update("smtpFrom", event.target.value)
                      }
                      placeholder="noreply@example.com"
                      disabled={saving || !form.alertsEnabled}
                    />
                  </div>
                </div>

                <div className="flex items-center justify-between gap-4">
                  <div className="space-y-1">
                    <p className="text-sm font-medium">
                      启用 SSL/TLS 加密连接
                    </p>
                    <p className="text-xs text-muted-foreground">
                      建议保持开启，使用加密方式连接邮件服务器。
                    </p>
                  </div>
                  <Switch
                    checked={form.smtpSslEnabled}
                    onCheckedChange={(checked) =>
                      update("smtpSslEnabled", checked)
                    }
                    disabled={saving || !form.alertsEnabled}
                  />
                </div>

                <div className="flex items-center justify-between gap-4">
                  <div className="space-y-1">
                    <p className="text-sm font-medium">
                      允许 Webhook 投递至内网地址
                    </p>
                    <p className="text-xs text-muted-foreground">
                      高危选项：开启后可能访问内网服务，请确认风险后再启用。
                    </p>
                  </div>
                  <Switch
                    checked={form.webhookAllowPrivateNetwork}
                    onCheckedChange={(checked) => {
                      if (checked) {
                        setWebhookConfirmOpen(true);
                        return;
                      }
                      update("webhookAllowPrivateNetwork", false);
                    }}
                    disabled={saving}
                  />
                </div>
              </GroupCard>
            </>
          )}
        </div>
      </div>

      <AlertDialog
        open={resetTarget !== null}
        onOpenChange={(open) => {
          if (!open && !saving) {
            setResetTarget(null);
          }
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>恢复推荐默认值</AlertDialogTitle>
            <AlertDialogDescription>
              确定将本组参数恢复为推荐默认值？当前未保存的修改将被丢弃。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={saving}>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={(event) => {
                event.preventDefault();
                void handleReset();
              }}
              disabled={saving}
            >
              {saving ? "恢复中..." : "确认恢复"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog
        open={webhookConfirmOpen}
        onOpenChange={setWebhookConfirmOpen}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>允许 Webhook 访问内网？</AlertDialogTitle>
            <AlertDialogDescription>
              开启后，告警 Webhook 可能请求内网地址，存在服务端请求伪造风险。请仅在你明确需要并已评估风险时启用。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                update("webhookAllowPrivateNetwork", true);
                setWebhookConfirmOpen(false);
              }}
            >
              确认启用
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
