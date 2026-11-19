"use client";

import { useState, type FormEvent } from "react";
import { Loader2Icon } from "lucide-react";
import { toast } from "sonner";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { Switch } from "@/components/ui/switch";
import { adminModelsApi } from "@/lib/api/endpoints";
import { normalizeUnknownError, type ApiError } from "@/lib/api/errors";
import type {
  ExecutionMode,
  MediaProtocol,
  ModelCapabilityType,
  ModelResponse,
  ReasoningEffort,
  ReasoningReplayPolicy,
} from "@/lib/api/types";
import {
  EXECUTION_MODE_LABEL,
  MEDIA_PROTOCOL_LABEL,
  MODEL_CAPABILITY_LABEL,
  REASONING_EFFORT_LABEL,
  REASONING_REPLAY_LABEL,
} from "@/lib/format";
import { cn } from "@/lib/utils";

const PROTOCOLS_BY_CAPABILITY: Record<ModelCapabilityType, MediaProtocol[]> = {
  CHAT: ["OPENAI_RESPONSES"],
  IMAGE: [
    "OPENAI_IMAGES",
    "ARK_IMAGES",
    "DASHSCOPE_IMAGE_MESSAGES",
    "DASHSCOPE_T2I",
  ],
  VIDEO: ["ARK_VIDEO_TASKS", "DASHSCOPE_VIDEO"],
};

const REASONING_EFFORTS: ReasoningEffort[] = [
  "NONE",
  "LOW",
  "MEDIUM",
  "HIGH",
  "XHIGH",
  "MAX",
];

const REASONING_EFFORT_SHORT_LABEL: Record<ReasoningEffort, string> = {
  NONE: "none",
  LOW: "low",
  MEDIUM: "medium",
  HIGH: "high",
  XHIGH: "xhigh",
  MAX: "max",
};

const DEFAULT_SUPPORTED_EFFORTS: ReasoningEffort[] = [
  "NONE",
  "LOW",
  "MEDIUM",
  "HIGH",
];

const REASONING_REPLAY_POLICIES: ReasoningReplayPolicy[] = [
  "ENCRYPTED",
  "ALL",
  "NONE",
];

interface ModelFormState {
  capabilityType: ModelCapabilityType;
  displayName: string;
  baseUrl: string;
  modelId: string;
  apiKey: string;
  protocol: MediaProtocol;
  executionMode: ExecutionMode;
  reasoningEffort: ReasoningEffort | "UNSET";
  supportedReasoningEfforts: ReasoningEffort[];
  reasoningReplay: ReasoningReplayPolicy;
  maxInputTokens: string;
  maxOutputTokens: string;
  priceUnits: string;
  supportsImageInput: boolean;
  enabled: boolean;
  defaultModel: boolean;
}

function buildInitialState(model: ModelResponse | null): ModelFormState {
  if (!model) {
    return {
      capabilityType: "CHAT",
      displayName: "",
      baseUrl: "https://",
      modelId: "",
      apiKey: "",
      protocol: "OPENAI_RESPONSES",
      executionMode: "INLINE_RESULT",
      reasoningEffort: "MEDIUM",
      supportedReasoningEfforts: [...DEFAULT_SUPPORTED_EFFORTS],
      reasoningReplay: "ENCRYPTED",
      maxInputTokens: "32000",
      maxOutputTokens: "4096",
      priceUnits: "0",
      supportsImageInput: true,
      enabled: true,
      defaultModel: false,
    };
  }
  const supported = REASONING_EFFORTS.filter((effort) =>
    model.supportedReasoningEfforts.includes(effort)
  );
  const effort =
    model.reasoningEffort && supported.includes(model.reasoningEffort)
      ? model.reasoningEffort
      : "UNSET";
  return {
    capabilityType: model.capabilityType,
    displayName: model.displayName,
    baseUrl: model.baseUrl,
    modelId: model.modelId,
    apiKey: "",
    protocol: model.protocol,
    executionMode: model.executionMode,
    reasoningEffort: effort,
    supportedReasoningEfforts: supported,
    reasoningReplay: model.reasoningReplay,
    maxInputTokens:
      model.maxInputTokens === null ? "" : String(model.maxInputTokens),
    maxOutputTokens:
      model.maxOutputTokens === null ? "" : String(model.maxOutputTokens),
    priceUnits: String(model.priceUnits),
    supportsImageInput: model.supportsImageInput,
    enabled: model.enabled,
    defaultModel: model.defaultModel,
  };
}

interface ModelFormSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  editingModel: ModelResponse | null;
  onSaved: (model: ModelResponse) => void;
}

function defaultExecutionModeForProtocol(protocol: MediaProtocol): ExecutionMode {
  return protocol === "ARK_VIDEO_TASKS" ||
    protocol === "DASHSCOPE_VIDEO" ||
    protocol === "DASHSCOPE_T2I"
    ? "REMOTE_TASK"
    : "INLINE_RESULT";
}

function ModelFormContent({
  editingModel,
  onClose,
  onSaved,
}: {
  editingModel: ModelResponse | null;
  onClose: () => void;
  onSaved: (model: ModelResponse) => void;
}) {
  const isEdit = editingModel !== null;
  const [form, setForm] = useState<ModelFormState>(() =>
    buildInitialState(editingModel)
  );
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const handleCapabilityChange = (nextCapability: ModelCapabilityType) => {
    const allowedProtocols = PROTOCOLS_BY_CAPABILITY[nextCapability];
    const nextProtocol = allowedProtocols.includes(form.protocol)
      ? form.protocol
      : allowedProtocols[0];
    const nextExecutionMode = defaultExecutionModeForProtocol(nextProtocol);

    setForm((prev) => ({
      ...prev,
      capabilityType: nextCapability,
      protocol: nextProtocol,
      executionMode: nextExecutionMode,
      maxInputTokens:
        nextCapability === "CHAT" && !prev.maxInputTokens
          ? "32000"
          : prev.maxInputTokens,
      maxOutputTokens:
        nextCapability === "CHAT" && !prev.maxOutputTokens
          ? "4096"
          : prev.maxOutputTokens,
    }));
  };

  const handleProtocolChange = (nextProtocol: MediaProtocol) => {
    setForm((prev) => ({
      ...prev,
      protocol: nextProtocol,
      executionMode: defaultExecutionModeForProtocol(nextProtocol),
    }));
  };

  const handleToggleSupportedEffort = (
    effort: ReasoningEffort,
    checked: boolean
  ) => {
    setForm((prev) => {
      const nextSupported = REASONING_EFFORTS.filter((item) =>
        item === effort ? checked : prev.supportedReasoningEfforts.includes(item)
      );
      const nextDefault =
        !checked && prev.reasoningEffort === effort
          ? "UNSET"
          : prev.reasoningEffort;
      return {
        ...prev,
        supportedReasoningEfforts: nextSupported,
        reasoningEffort: nextDefault,
      };
    });
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);

    const nextErrors: Record<string, string> = {};
    const displayName = form.displayName.trim();
    const baseUrl = form.baseUrl.trim();
    const modelId = form.modelId.trim();
    const apiKey = form.apiKey.trim();

    if (!displayName) {
      nextErrors.displayName = "显示名称不能为空";
    }
    if (!/^https?:\/\/.+/.test(baseUrl)) {
      nextErrors.baseUrl = "Base URL 必须是 http 或 https 地址";
    }
    if (!modelId) {
      nextErrors.modelId = "模型 ID 不能为空";
    }
    if (!isEdit && !apiKey) {
      nextErrors.apiKey = "API Key 不能为空";
    }

    const priceUnits = Number(form.priceUnits);
    if (!Number.isInteger(priceUnits) || priceUnits < 0) {
      nextErrors.priceUnits = "单任务价格必须为非负整数";
    }

    let maxInputTokens: number | null = null;
    let maxOutputTokens: number | null = null;
    if (form.capabilityType === "CHAT") {
      maxInputTokens = Number(form.maxInputTokens);
      maxOutputTokens = Number(form.maxOutputTokens);
      if (
        !Number.isInteger(maxInputTokens) ||
        maxInputTokens < 1 ||
        maxInputTokens > 2_000_000
      ) {
        nextErrors.maxInputTokens = "最大输入 Token 必须在 1 到 2000000 之间";
      }
      if (
        !Number.isInteger(maxOutputTokens) ||
        maxOutputTokens < 1 ||
        maxOutputTokens > 1_000_000
      ) {
        nextErrors.maxOutputTokens = "最大输出 Token 必须在 1 到 1000000 之间";
      }
    }

    if (Object.keys(nextErrors).length > 0) {
      setFieldErrors(nextErrors);
      return;
    }

    setFieldErrors({});
    setSubmitting(true);

    try {
      const isChat = form.capabilityType === "CHAT";
      const supportedReasoningEfforts = isChat
        ? form.supportedReasoningEfforts
        : undefined;
      const reasoningEffort =
        isChat && form.reasoningEffort !== "UNSET"
          ? form.reasoningEffort
          : undefined;
      const clearReasoningEffort =
        isChat && form.reasoningEffort === "UNSET" ? true : undefined;
      const reasoningReplay = isChat ? form.reasoningReplay : undefined;

      let saved: ModelResponse;
      if (isEdit && editingModel) {
        saved = await adminModelsApi.update(editingModel.id, {
          displayName,
          baseUrl,
          modelId,
          ...(apiKey ? { apiKey } : {}),
          capabilityType: form.capabilityType,
          protocol: form.protocol,
          executionMode:
            form.capabilityType === "CHAT" ? "INLINE_RESULT" : form.executionMode,
          reasoningEffort,
          supportedReasoningEfforts,
          clearReasoningEffort,
          reasoningReplay,
          maxInputTokens,
          maxOutputTokens,
          priceUnits,
          supportsImageInput: form.supportsImageInput,
          enabled: form.enabled,
          defaultModel: form.enabled ? form.defaultModel : false,
        });
        toast.success(`模型「${saved.displayName}」已更新`);
      } else {
        saved = await adminModelsApi.create({
          displayName,
          baseUrl,
          apiKey,
          modelId,
          capabilityType: form.capabilityType,
          protocol: form.protocol,
          executionMode:
            form.capabilityType === "CHAT" ? "INLINE_RESULT" : form.executionMode,
          reasoningEffort,
          supportedReasoningEfforts,
          reasoningReplay,
          maxInputTokens,
          maxOutputTokens,
          priceUnits,
          supportsImageInput: form.supportsImageInput,
          enabled: form.enabled,
          defaultModel: form.enabled ? form.defaultModel : false,
        });
        toast.success(`模型「${saved.displayName}」已创建`);
      }

      onSaved(saved);
      onClose();
    } catch (err) {
      const apiError = normalizeUnknownError(err, "保存模型配置失败");
      if (apiError.isValidationError) {
        setFieldErrors(apiError.fieldErrors);
      } else {
        setError(apiError);
      }
    } finally {
      setSubmitting(false);
    }
  };

  const allowedProtocols = PROTOCOLS_BY_CAPABILITY[form.capabilityType];

  return (
    <>
      <SheetHeader>
        <SheetTitle>
          {isEdit ? `编辑模型 · ${editingModel?.displayName}` : "新增模型配置"}
        </SheetTitle>
        <SheetDescription>
          按能力类型配置模型接口、单次计费与默认路由。
        </SheetDescription>
      </SheetHeader>

      <form
        id="model-config-form"
        onSubmit={handleSubmit}
        className="space-y-4 px-6 pb-4"
      >
        <ApiErrorAlert error={error} title="模型配置校验失败" />

        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label>能力类型</Label>
            <Select
              value={form.capabilityType}
              items={MODEL_CAPABILITY_LABEL}
              onValueChange={(value) => {
                if (value) {
                  handleCapabilityChange(value as ModelCapabilityType);
                }
              }}
              disabled={submitting}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="CHAT">
                  {MODEL_CAPABILITY_LABEL.CHAT}
                </SelectItem>
                <SelectItem value="IMAGE">
                  {MODEL_CAPABILITY_LABEL.IMAGE}
                </SelectItem>
                <SelectItem value="VIDEO">
                  {MODEL_CAPABILITY_LABEL.VIDEO}
                </SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="space-y-1.5">
            <Label>接入协议</Label>
            <Select
              value={form.protocol}
              items={MEDIA_PROTOCOL_LABEL}
              onValueChange={(value) => {
                if (value) {
                  handleProtocolChange(value as MediaProtocol);
                }
              }}
              disabled={submitting || form.capabilityType === "CHAT"}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {allowedProtocols.map((protocol) => (
                  <SelectItem key={protocol} value={protocol}>
                    {MEDIA_PROTOCOL_LABEL[protocol]}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="model-display-name">显示名称</Label>
          <Input
            id="model-display-name"
            value={form.displayName}
            onChange={(event) =>
              setForm((prev) => ({ ...prev, displayName: event.target.value }))
            }
            placeholder="例如：主文本模型 / Seedance 视频"
            aria-invalid={Boolean(fieldErrors.displayName)}
            disabled={submitting}
          />
          {fieldErrors.displayName ? (
            <p className="text-xs text-destructive">
              {fieldErrors.displayName}
            </p>
          ) : null}
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <div className="space-y-1.5">
            <Label htmlFor="model-id">上游模型 ID</Label>
            <Input
              id="model-id"
              value={form.modelId}
              onChange={(event) =>
                setForm((prev) => ({ ...prev, modelId: event.target.value }))
              }
              placeholder="例如：gpt-6-astra"
              aria-invalid={Boolean(fieldErrors.modelId)}
              disabled={submitting}
            />
            {fieldErrors.modelId ? (
              <p className="text-xs text-destructive">{fieldErrors.modelId}</p>
            ) : null}
          </div>

          <div className="space-y-1.5">
            <Label>执行方式</Label>
            <Select
              value={form.executionMode}
              items={EXECUTION_MODE_LABEL}
              onValueChange={(value) => {
                if (value) {
                  setForm((prev) => ({
                    ...prev,
                    executionMode: value as ExecutionMode,
                  }));
                }
              }}
              disabled={submitting || form.capabilityType === "CHAT"}
            >
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="INLINE_RESULT">
                  {EXECUTION_MODE_LABEL.INLINE_RESULT}
                </SelectItem>
                <SelectItem value="REMOTE_TASK">
                  {EXECUTION_MODE_LABEL.REMOTE_TASK}
                </SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="model-base-url">网关 Base URL</Label>
          <Input
            id="model-base-url"
            value={form.baseUrl}
            onChange={(event) =>
              setForm((prev) => ({ ...prev, baseUrl: event.target.value }))
            }
            placeholder="https://gateway.example.com/v1"
            aria-invalid={Boolean(fieldErrors.baseUrl)}
            disabled={submitting}
          />
          {fieldErrors.baseUrl ? (
            <p className="text-xs text-destructive">{fieldErrors.baseUrl}</p>
          ) : null}
        </div>

        <div className="space-y-1.5">
          <Label htmlFor="model-api-key">
            API Key{isEdit ? "（留空则保留原密钥）" : ""}
          </Label>
          <Input
            id="model-api-key"
            type="password"
            autoComplete="off"
            value={form.apiKey}
            onChange={(event) =>
              setForm((prev) => ({ ...prev, apiKey: event.target.value }))
            }
            placeholder={
              isEdit ? "已加密配置；仅需更换时填写新密钥" : "sk-..."
            }
            aria-invalid={Boolean(fieldErrors.apiKey)}
            disabled={submitting}
          />
          {fieldErrors.apiKey ? (
            <p className="text-xs text-destructive">{fieldErrors.apiKey}</p>
          ) : null}
        </div>

        {form.capabilityType === "CHAT" ? (
          <div className="space-y-4 border-t pt-4">
            <p className="text-xs font-medium text-muted-foreground">
              主对话模型上下文与推理策略
            </p>
            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label htmlFor="model-max-in">最大输入 Token</Label>
                <Input
                  id="model-max-in"
                  type="number"
                  min={1}
                  max={2000000}
                  value={form.maxInputTokens}
                  onChange={(event) =>
                    setForm((prev) => ({
                      ...prev,
                      maxInputTokens: event.target.value,
                    }))
                  }
                  aria-invalid={Boolean(fieldErrors.maxInputTokens)}
                  disabled={submitting}
                />
                {fieldErrors.maxInputTokens ? (
                  <p className="text-xs text-destructive">
                    {fieldErrors.maxInputTokens}
                  </p>
                ) : null}
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="model-max-out">最大输出 Token</Label>
                <Input
                  id="model-max-out"
                  type="number"
                  min={1}
                  max={1000000}
                  value={form.maxOutputTokens}
                  onChange={(event) =>
                    setForm((prev) => ({
                      ...prev,
                      maxOutputTokens: event.target.value,
                    }))
                  }
                  aria-invalid={Boolean(fieldErrors.maxOutputTokens)}
                  disabled={submitting}
                />
                {fieldErrors.maxOutputTokens ? (
                  <p className="text-xs text-destructive">
                    {fieldErrors.maxOutputTokens}
                  </p>
                ) : null}
              </div>
            </div>

            <div className="space-y-1.5">
              <div className="flex items-center justify-between gap-2">
                <Label>支持的推理档位</Label>
                <span className="text-[11px] text-muted-foreground">
                  勾选模型支持的 effort 档位
                </span>
              </div>
              <div
                role="group"
                aria-label="支持的推理档位"
                className="grid grid-cols-3 divide-x divide-y overflow-hidden rounded-control-md border bg-muted/20 sm:grid-cols-6 sm:divide-y-0"
              >
                {REASONING_EFFORTS.map((effort) => {
                  const checked =
                    form.supportedReasoningEfforts.includes(effort);
                  return (
                    <label
                      key={effort}
                      className={cn(
                        "relative flex cursor-pointer items-center justify-center gap-1 overflow-hidden px-1.5 py-2 text-xs select-none transition-colors motion-reduce:transition-none",
                        checked
                          ? "bg-primary/10 font-medium text-foreground"
                          : "text-muted-foreground hover:bg-muted/60 hover:text-foreground",
                        submitting && "pointer-events-none opacity-50"
                      )}
                    >
                      <Checkbox
                        checked={checked}
                        disabled={submitting}
                        onCheckedChange={(nextChecked) =>
                          handleToggleSupportedEffort(
                            effort,
                            Boolean(nextChecked)
                          )
                        }
                        className="size-3.5 shrink-0"
                      />
                      <span className="text-[11px]">
                        {REASONING_EFFORT_SHORT_LABEL[effort]}
                      </span>
                    </label>
                  );
                })}
              </div>
              {fieldErrors.supportedReasoningEfforts ? (
                <p className="text-xs text-destructive">
                  {fieldErrors.supportedReasoningEfforts}
                </p>
              ) : null}
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label>默认推理强度</Label>
                <Select
                  value={form.reasoningEffort}
                  items={{
                    UNSET: "不指定",
                    ...Object.fromEntries(
                      form.supportedReasoningEfforts.map((effort) => [
                        effort,
                        REASONING_EFFORT_LABEL[effort],
                      ])
                    ),
                  }}
                  onValueChange={(value) => {
                    if (value) {
                      setForm((prev) => ({
                        ...prev,
                        reasoningEffort: value as ReasoningEffort | "UNSET",
                      }));
                    }
                  }}
                  disabled={submitting}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="UNSET">不指定</SelectItem>
                    {form.supportedReasoningEfforts.map((effort) => (
                      <SelectItem key={effort} value={effort}>
                        {REASONING_EFFORT_LABEL[effort]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                {fieldErrors.reasoningEffort ? (
                  <p className="text-xs text-destructive">
                    {fieldErrors.reasoningEffort}
                  </p>
                ) : null}
              </div>

              <div className="space-y-1.5">
                <Label>推理回放策略</Label>
                <Select
                  value={form.reasoningReplay}
                  items={REASONING_REPLAY_LABEL}
                  onValueChange={(value) => {
                    if (value) {
                      setForm((prev) => ({
                        ...prev,
                        reasoningReplay: value as ReasoningReplayPolicy,
                      }));
                    }
                  }}
                  disabled={submitting}
                >
                  <SelectTrigger className="w-full">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {REASONING_REPLAY_POLICIES.map((policy) => (
                      <SelectItem key={policy} value={policy}>
                        {REASONING_REPLAY_LABEL[policy]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            </div>
          </div>
        ) : null}

        <div className="space-y-1.5">
          <Label htmlFor="model-price">单任务扣费额度</Label>
          <Input
            id="model-price"
            type="number"
            min={0}
            max={1000000000}
            value={form.priceUnits}
            onChange={(event) =>
              setForm((prev) => ({ ...prev, priceUnits: event.target.value }))
            }
            aria-invalid={Boolean(fieldErrors.priceUnits)}
            disabled={submitting}
          />
          {fieldErrors.priceUnits ? (
            <p className="text-xs text-destructive">{fieldErrors.priceUnits}</p>
          ) : null}
        </div>

        <div className="space-y-3 border-t pt-4">
          <div className="flex items-center justify-between gap-4">
            <div>
              <p className="text-sm font-medium">支持图片输入</p>
              <p className="text-xs text-muted-foreground">
                主对话将消息附图解析为 input_image 参与调用
              </p>
            </div>
            <Switch
              checked={form.supportsImageInput}
              onCheckedChange={(checked) =>
                setForm((prev) => ({ ...prev, supportsImageInput: checked }))
              }
              disabled={submitting}
            />
          </div>

          <div className="flex items-center justify-between gap-4">
            <div>
              <p className="text-sm font-medium">启用模型</p>
              <p className="text-xs text-muted-foreground">
                停用后不可被新任务选中，也不能作为默认模型
              </p>
            </div>
            <Switch
              checked={form.enabled}
              onCheckedChange={(checked) =>
                setForm((prev) => ({
                  ...prev,
                  enabled: checked,
                  defaultModel: checked ? prev.defaultModel : false,
                }))
              }
              disabled={submitting}
            />
          </div>

          <div className="flex items-center justify-between gap-4">
            <div>
              <p className="text-sm font-medium">设为该能力默认模型</p>
              <p className="text-xs text-muted-foreground">
                每种能力类型（CHAT / IMAGE / VIDEO）仅有一个默认模型
              </p>
            </div>
            <Switch
              checked={form.defaultModel}
              onCheckedChange={(checked) =>
                setForm((prev) => ({ ...prev, defaultModel: checked }))
              }
              disabled={submitting || !form.enabled}
            />
          </div>
        </div>
      </form>

      <SheetFooter className="border-t pt-4">
        <Button
          type="button"
          variant="outline"
          onClick={onClose}
          disabled={submitting}
        >
          取消
        </Button>
        <Button type="submit" form="model-config-form" disabled={submitting}>
          {submitting ? (
            <>
              <Loader2Icon className="animate-spin motion-reduce:animate-none" />
              <span>保存中...</span>
            </>
          ) : isEdit ? (
            "保存修改"
          ) : (
            "创建模型"
          )}
        </Button>
      </SheetFooter>
    </>
  );
}

export function ModelFormSheet({
  open,
  onOpenChange,
  editingModel,
  onSaved,
}: ModelFormSheetProps) {
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      <SheetContent side="right" className="w-full overflow-y-auto sm:max-w-lg">
        {open ? (
          <ModelFormContent
            key={editingModel?.id ?? "create"}
            editingModel={editingModel}
            onClose={() => onOpenChange(false)}
            onSaved={onSaved}
          />
        ) : null}
      </SheetContent>
    </Sheet>
  );
}
