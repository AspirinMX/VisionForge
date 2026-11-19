"use client";

import {
  useImperativeHandle,
  useRef,
  useState,
  type ChangeEvent,
  type Ref,
} from "react";
import {
  FolderPlusIcon,
  ImagePlusIcon,
  MessageSquarePlusIcon,
  PlusIcon,
  SparklesIcon,
  Trash2Icon,
  UploadIcon,
  Wand2Icon,
  XIcon,
} from "lucide-react";

import { AssetMediaPreview } from "@/components/common/media-preview";
import {
  useStudio,
  type ConversationAssetItem,
  type QuickGenDraft,
} from "@/components/studio/studio-context";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Checkbox } from "@/components/ui/checkbox";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Progress } from "@/components/ui/progress";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { useAsyncData } from "@/hooks/use-async-data";
import { createIdempotencyKey } from "@/lib/api/client";
import { generationApi, modelsApi } from "@/lib/api/endpoints";
import {
  notifyApiError,
  notifyError,
  notifyInfo,
  notifySuccess,
} from "@/lib/api/feedback";
import type {
  GenerationInputRequest,
  GenerationInputRole,
  GenerationType,
  UUID,
} from "@/lib/api/types";
import { formatBytes, formatShortTime } from "@/lib/format";
import { uploadConversationAsset } from "@/lib/studio/asset-cache";

const MAX_INPUTS = 8;

const IMAGE_ROLES: { value: GenerationInputRole; label: string }[] = [
  { value: "REFERENCE", label: "参考图" },
  { value: "INIT_IMAGE", label: "底图编辑" },
  { value: "MASK", label: "局部遮罩" },
];

const VIDEO_ROLES: { value: GenerationInputRole; label: string }[] = [
  { value: "FIRST_FRAME", label: "首帧" },
  { value: "LAST_FRAME", label: "尾帧" },
  { value: "REFERENCE", label: "视觉参考" },
];

interface QuickFormHandle {
  addInputAsset: (assetId: UUID) => void;
}

interface QuickGenerateFormProps {
  ref: Ref<QuickFormHandle>;
  initialDraft: QuickGenDraft | null;
  conversationId: UUID;
  localAssets: ConversationAssetItem[];
  onSubmitted: () => Promise<void>;
  onOpenTasks: () => void;
}

function buildDefaultParams(
  type: GenerationType,
  draft?: QuickGenDraft | null
): Record<string, unknown> {
  if (type === "IMAGE") {
    return {
      size: "1024x1024",
      quality: "medium",
      outputFormat: "png",
      ...(draft?.parameters ?? {}),
    };
  }
  return {
    resolution: "720p",
    ratio: "16:9",
    durationSeconds: 5,
    generateAudio: true,
    watermark: false,
    ...(draft?.parameters ?? {}),
  };
}

function QuickGenerateForm({
  ref,
  initialDraft,
  conversationId,
  localAssets,
  onSubmitted,
  onOpenTasks,
}: QuickGenerateFormProps) {
  const initialType: GenerationType = initialDraft?.type ?? "IMAGE";
  const initialParams = buildDefaultParams(initialType, initialDraft);

  const [genType, setGenType] = useState<GenerationType>(initialType);
  const [prompt, setPrompt] = useState(initialDraft?.prompt ?? "");
  const [modelDefinitionId, setModelDefinitionId] = useState("");
  const [inputs, setInputs] = useState<GenerationInputRequest[]>(
    initialDraft?.inputs ?? []
  );

  // Structured IMAGE fields
  const [imgSize, setImgSize] = useState(
    String(initialParams.size ?? "1024x1024")
  );
  const [imgQuality, setImgQuality] = useState(
    String(initialParams.quality ?? "medium")
  );
  const [imgFormat, setImgFormat] = useState(
    String(initialParams.outputFormat ?? "png")
  );

  // Structured VIDEO fields
  const [vidResolution, setVidResolution] = useState(
    String(initialParams.resolution ?? "720p")
  );
  const [vidRatio, setVidRatio] = useState(
    String(initialParams.ratio ?? "16:9")
  );
  const [vidDuration, setVidDuration] = useState(
    String(initialParams.durationSeconds ?? 5)
  );
  const [vidAudio, setVidAudio] = useState(
    Boolean(initialParams.generateAudio ?? true)
  );
  const [vidWatermark, setVidWatermark] = useState(
    Boolean(initialParams.watermark ?? false)
  );
  const [vidSeed, setVidSeed] = useState(
    initialParams.seed !== undefined && initialParams.seed !== null
      ? String(initialParams.seed)
      : ""
  );

  // Raw JSON override toggle
  const [rawParamsMode, setRawParamsMode] = useState(false);
  const [rawParamsText, setRawParamsText] = useState(
    JSON.stringify(initialParams, null, 2)
  );

  // Storyboard / source linkage
  const [shotId, setShotId] = useState(initialDraft?.shotId ?? "");
  const [storyboardFileId, setStoryboardFileId] = useState(
    initialDraft?.storyboardFileId ?? ""
  );
  const [storyboardVersion] = useState<number | null>(
    initialDraft?.storyboardVersion ?? null
  );
  const [sourceTaskId, setSourceTaskId] = useState(
    initialDraft?.sourceTaskId ?? ""
  );

  const [submitting, setSubmitting] = useState(false);
  const [selectedAssetToAdd, setSelectedAssetToAdd] = useState("");

  const { data: availableModels } = useAsyncData(
    () => modelsApi.list({ capabilityType: genType }),
    `models:${genType}`,
    { errorMessage: "加载可用模型失败" }
  );

  useImperativeHandle(ref, () => ({
    addInputAsset(assetId: UUID) {
      setInputs((prev) => {
        if (prev.length >= MAX_INPUTS) {
          notifyInfo(`生成输入最多 ${MAX_INPUTS} 项`);
          return prev;
        }
        if (prev.some((item) => item.assetId === assetId)) {
          notifyInfo("该资产已在输入列表中");
          return prev;
        }
        const defaultRole: GenerationInputRole =
          genType === "VIDEO" &&
          !prev.some((item) => item.role === "FIRST_FRAME")
            ? "FIRST_FRAME"
            : "REFERENCE";
        return [...prev, { assetId, role: defaultRole }];
      });
    },
  }));

  const availableRoles = genType === "VIDEO" ? VIDEO_ROLES : IMAGE_ROLES;
  const roleItems = Object.fromEntries(
    availableRoles.map((role) => [role.value, role.label])
  );
  const assetSelectItems = Object.fromEntries(
    localAssets.map((asset) => [asset.assetId, asset.label])
  );
  const modelItems: Record<string, string> = {
    __default__: "使用系统默认启用模型（留空）",
  };
  for (const model of availableModels ?? []) {
    modelItems[model.id] =
      `${model.displayName} · ${model.modelId}` +
      (model.defaultModel ? " · 默认" : "") +
      (model.priceUnits > 0 ? ` · ${model.priceUnits} 单位/次` : "");
  }
  if (modelDefinitionId && !(modelDefinitionId in modelItems)) {
    modelItems[modelDefinitionId] = modelDefinitionId;
  }

  const resolvedModel =
    (modelDefinitionId.trim()
      ? (availableModels ?? []).find((m) => m.id === modelDefinitionId.trim())
      : (availableModels ?? []).find((m) => m.defaultModel) ??
        (availableModels ?? [])[0]) ?? null;

  const hasMaskRole =
    genType === "IMAGE" && inputs.some((item) => item.role === "MASK");
  const maskBlockedByOpenAi =
    hasMaskRole && resolvedModel?.protocol === "OPENAI_IMAGES";
  const imageInputUnsupported =
    Boolean(resolvedModel && !resolvedModel.supportsImageInput && inputs.length > 0);

  const handleTypeChange = (nextType: GenerationType) => {
    setGenType(nextType);
    const allowed = new Set(
      (nextType === "VIDEO" ? VIDEO_ROLES : IMAGE_ROLES).map((r) => r.value)
    );
    setInputs((prev) =>
      prev.map((item) => ({
        ...item,
        role:
          item.role && allowed.has(item.role)
            ? item.role
            : nextType === "VIDEO"
              ? "FIRST_FRAME"
              : "REFERENCE",
      }))
    );
  };

  const handleSubmit = async () => {
    const trimmedPrompt = prompt.trim();
    if (!trimmedPrompt) {
      notifyError("请输入生成提示词 (prompt)");
      return;
    }

    let parameters: Record<string, unknown>;
    if (rawParamsMode) {
      try {
        parameters = JSON.parse(rawParamsText) as Record<string, unknown>;
      } catch {
        notifyError("高级参数 JSON 格式错误");
        return;
      }
    } else if (genType === "IMAGE") {
      parameters = {
        size: imgSize,
        quality: imgQuality,
        outputFormat: imgFormat,
      };
    } else {
      parameters = {
        resolution: vidResolution,
        ratio: vidRatio,
        durationSeconds: Math.min(12, Math.max(2, Number(vidDuration) || 5)),
        generateAudio: vidAudio,
        watermark: vidWatermark,
        ...(vidSeed.trim() && Number.isInteger(Number(vidSeed.trim()))
          ? { seed: Number(vidSeed.trim()) }
          : {}),
      };
    }

    setSubmitting(true);
    try {
      await generationApi.create(
        {
          conversationId,
          type: genType,
          modelDefinitionId: modelDefinitionId.trim() || undefined,
          prompt: trimmedPrompt,
          parameters,
          inputs: inputs.length > 0 ? inputs : undefined,
          shotId: shotId.trim() || undefined,
          storyboardFileId: storyboardFileId.trim() || undefined,
          storyboardVersion:
            storyboardFileId.trim() && storyboardVersion
              ? storyboardVersion
              : undefined,
          sourceTaskId: sourceTaskId.trim() || undefined,
        },
        createIdempotencyKey()
      );
      notifySuccess(
        `已提交${genType === "IMAGE" ? "图片" : "视频"}生成任务（可在任务中心追踪）`
      );
      await onSubmitted();
      onOpenTasks();
    } catch (err) {
      notifyApiError(err, "提交生成任务失败");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="space-y-4 rounded-surface-md border bg-card p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Wand2Icon className="size-4 text-primary" />
          <h3 className="text-sm font-semibold">直接创建媒体生成任务</h3>
        </div>
        <div className="flex items-center gap-1.5">
          <Button
            type="button"
            size="xs"
            variant={genType === "IMAGE" ? "default" : "outline"}
            onClick={() => handleTypeChange("IMAGE")}
          >
            图片生成
          </Button>
          <Button
            type="button"
            size="xs"
            variant={genType === "VIDEO" ? "default" : "outline"}
            onClick={() => handleTypeChange("VIDEO")}
          >
            视频生成
          </Button>
        </div>
      </div>

      {/* Linkage pills when coming from Shot or Retry */}
      {shotId || sourceTaskId ? (
        <div className="flex flex-wrap items-center gap-2 rounded-surface-sm bg-muted/40 px-3 py-2 text-xs">
          {shotId ? (
            <Badge variant="secondary" className="gap-1">
              <span>关联镜头：{shotId}</span>
              <button
                type="button"
                onClick={() => {
                  setShotId("");
                  setStoryboardFileId("");
                }}
                aria-label="取消镜头关联"
              >
                <XIcon className="size-3" />
              </button>
            </Badge>
          ) : null}
          {sourceTaskId ? (
            <Badge variant="outline" className="gap-1">
              <span>基于历史任务再生成</span>
              <button
                type="button"
                onClick={() => setSourceTaskId("")}
                aria-label="取消关联原任务"
              >
                <XIcon className="size-3" />
              </button>
            </Badge>
          ) : null}
          {shotId ? (
            <span className="text-muted-foreground">
              完成后将自动回写到对应分镜
            </span>
          ) : null}
        </div>
      ) : null}

      {/* Model selection */}
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="space-y-1">
          <Label className="text-xs text-muted-foreground">模型路由</Label>
          <Select
            value={modelDefinitionId || "__default__"}
            items={modelItems}
            onValueChange={(value) =>
              setModelDefinitionId(
                !value || value === "__default__" ? "" : value
              )
            }
          >
            <SelectTrigger className="h-8 text-xs">
              <SelectValue placeholder="使用系统默认启用模型" />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="__default__">
                使用系统默认启用模型（留空）
              </SelectItem>
              {(availableModels ?? []).map((model) => (
                <SelectItem key={model.id} value={model.id}>
                  {model.displayName} · {model.modelId}
                  {model.defaultModel ? " · 默认" : ""}
                  {model.priceUnits > 0 ? ` · ${model.priceUnits} 额度` : ""}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
          <Input
            value={modelDefinitionId}
            onChange={(e) => setModelDefinitionId(e.target.value)}
            placeholder="或指定模型编号"
            className="h-8 text-xs"
          />
        </div>
        <div className="flex items-end text-[11px] text-muted-foreground">
          {resolvedModel
            ? `已选择 ${resolvedModel.displayName}（每次消耗 ${resolvedModel.priceUnits} 额度）`
            : "留空时自动使用系统默认模型"}
        </div>
      </div>

      <div className="space-y-1.5">
        <Label htmlFor="quick-gen-prompt" className="text-xs font-medium">
          生成提示词 (prompt)
        </Label>
        <Textarea
          id="quick-gen-prompt"
          rows={3}
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          placeholder={
            genType === "IMAGE"
              ? "描述画面主体、构图景别、光线与风格细节..."
              : "描述镜头从首帧开始发生的主体动作、情绪变化与运镜运动..."
          }
          className="text-xs"
        />
      </div>

      {/* Inputs */}
      <div className="space-y-2">
        <div className="flex items-center justify-between gap-2">
          <Label className="text-xs font-medium">
            参考输入资产 ({inputs.length}/{MAX_INPUTS})
          </Label>
          {localAssets.length > 0 ? (
            <div className="flex items-center gap-1.5">
              <Select
                value={selectedAssetToAdd || null}
                items={assetSelectItems}
                onValueChange={(val) => setSelectedAssetToAdd(val ?? "")}
              >
                <SelectTrigger size="sm" className="h-7 w-44 text-xs">
                  <SelectValue placeholder="从会话素材添加..." />
                </SelectTrigger>
                <SelectContent>
                  {localAssets.map((a) => (
                    <SelectItem key={a.assetId} value={a.assetId}>
                      {a.label}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Button
                type="button"
                variant="outline"
                size="xs"
                disabled={!selectedAssetToAdd || inputs.length >= MAX_INPUTS}
                onClick={() => {
                  if (!selectedAssetToAdd) {
                    return;
                  }
                  if (inputs.some((i) => i.assetId === selectedAssetToAdd)) {
                    notifyInfo("该资产已在输入列表中");
                    return;
                  }
                  setInputs((prev) => [
                    ...prev,
                    {
                      assetId: selectedAssetToAdd,
                      role: genType === "VIDEO" ? "FIRST_FRAME" : "REFERENCE",
                    },
                  ]);
                  setSelectedAssetToAdd("");
                }}
              >
                <PlusIcon />
                <span>添加</span>
              </Button>
            </div>
          ) : null}
        </div>

        {imageInputUnsupported ? (
          <Alert variant="destructive">
            <AlertTitle>所选模型不支持图片输入</AlertTitle>
            <AlertDescription className="text-xs">
              「{resolvedModel?.displayName}」未开启图片输入支持，请移除参考资产或更换支持图生图/首帧输入的模型。
            </AlertDescription>
          </Alert>
        ) : null}

        {hasMaskRole ? (
          <Alert variant={maskBlockedByOpenAi ? "destructive" : "default"}>
            <AlertTitle>MASK 角色协议提示</AlertTitle>
            <AlertDescription className="text-xs">
              {maskBlockedByOpenAi
                ? "当前选定模型采用 OpenAI Images 协议，服务端暂不支持 MASK 局部重绘角色，请改用 REFERENCE/INIT_IMAGE 或切换协议。"
                : "部分协议（如 OpenAI Images）暂不支持 MASK 局部重绘角色；请确认目标模型支持后再提交。"}
            </AlertDescription>
          </Alert>
        ) : null}

        {inputs.length > 0 ? (
          <div className="space-y-2">
            {inputs.map((inp, idx) => (
              <div
                key={`${inp.assetId}-${idx}`}
                className="flex flex-wrap items-center justify-between gap-2 rounded-surface-sm bg-muted/20 p-2"
              >
                <div className="flex min-w-0 items-center gap-2">
                  <AssetMediaPreview
                    assetId={inp.assetId}
                    kind="IMAGE"
                    allowLightbox={false}
                    className="size-10 shrink-0 rounded-lg"
                  />
                  <span className="truncate font-mono text-[11px]">
                    {inp.assetId}
                  </span>
                </div>

                <div className="flex items-center gap-1.5">
                  <Select
                    value={inp.role ?? availableRoles[0].value}
                    items={roleItems}
                    onValueChange={(val) => {
                      if (!val) {
                        return;
                      }
                      setInputs((prev) =>
                        prev.map((item, i) =>
                          i === idx
                            ? { ...item, role: val as GenerationInputRole }
                            : item
                        )
                      );
                    }}
                  >
                    <SelectTrigger size="sm" className="h-7 text-xs">
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {availableRoles.map((role) => (
                        <SelectItem key={role.value} value={role.value}>
                          {role.label}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                  <Button
                    type="button"
                    variant="ghost"
                    size="icon-xs"
                    onClick={() =>
                      setInputs((prev) => prev.filter((_, i) => i !== idx))
                    }
                    aria-label="移除输入"
                  >
                    <Trash2Icon />
                  </Button>
                </div>
              </div>
            ))}
          </div>
        ) : null}
      </div>

      {/* Parameters */}
      <div className="space-y-2.5 rounded-surface-sm bg-muted/20 p-3">
        <div className="flex items-center justify-between">
          <span className="text-xs font-medium">生成参数</span>
          <Button
            type="button"
            variant="ghost"
            size="xs"
            onClick={() => {
              if (!rawParamsMode) {
                const currentObj =
                  genType === "IMAGE"
                    ? { size: imgSize, quality: imgQuality, outputFormat: imgFormat }
                    : {
                        resolution: vidResolution,
                        ratio: vidRatio,
                        durationSeconds: Number(vidDuration) || 5,
                        generateAudio: vidAudio,
                        watermark: vidWatermark,
                      };
                setRawParamsText(JSON.stringify(currentObj, null, 2));
              }
              setRawParamsMode((v) => !v);
            }}
          >
            {rawParamsMode ? "返回结构化表单" : "编辑原始 JSON"}
          </Button>
        </div>

        {rawParamsMode ? (
          <Textarea
            rows={5}
            value={rawParamsText}
            onChange={(e) => setRawParamsText(e.target.value)}
            className="font-mono text-xs"
          />
        ) : genType === "IMAGE" ? (
          <div className="grid grid-cols-3 gap-2">
            <div className="space-y-1">
              <Label className="text-[11px] text-muted-foreground">尺寸</Label>
              <Select
                value={imgSize}
                onValueChange={(v) => v && setImgSize(v)}
              >
                <SelectTrigger size="sm">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="1024x1024">1024x1024</SelectItem>
                  <SelectItem value="1536x1024">1536x1024</SelectItem>
                  <SelectItem value="1024x1536">1024x1536</SelectItem>
                  <SelectItem value="auto">auto</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1">
              <Label className="text-[11px] text-muted-foreground">质量</Label>
              <Select
                value={imgQuality}
                onValueChange={(v) => v && setImgQuality(v)}
              >
                <SelectTrigger size="sm">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="high">high</SelectItem>
                  <SelectItem value="medium">medium</SelectItem>
                  <SelectItem value="low">low</SelectItem>
                  <SelectItem value="auto">auto</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="space-y-1">
              <Label className="text-[11px] text-muted-foreground">格式</Label>
              <Select
                value={imgFormat}
                onValueChange={(v) => v && setImgFormat(v)}
              >
                <SelectTrigger size="sm">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="png">png</SelectItem>
                  <SelectItem value="jpeg">jpeg</SelectItem>
                  <SelectItem value="webp">webp</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>
        ) : (
          <div className="space-y-2.5">
            <div className="grid grid-cols-3 gap-2">
              <div className="space-y-1">
                <Label className="text-[11px] text-muted-foreground">
                  分辨率
                </Label>
                <Select
                  value={vidResolution}
                  onValueChange={(v) => v && setVidResolution(v)}
                >
                  <SelectTrigger size="sm">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="480p">480p</SelectItem>
                    <SelectItem value="720p">720p</SelectItem>
                    <SelectItem value="1080p">1080p</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1">
                <Label className="text-[11px] text-muted-foreground">比例</Label>
                <Select
                  value={vidRatio}
                  onValueChange={(v) => v && setVidRatio(v)}
                >
                  <SelectTrigger size="sm">
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="16:9">16:9</SelectItem>
                    <SelectItem value="9:16">9:16</SelectItem>
                    <SelectItem value="1:1">1:1</SelectItem>
                    <SelectItem value="adaptive">adaptive</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1">
                <Label className="text-[11px] text-muted-foreground">
                  时长（2–12s）
                </Label>
                <Input
                  type="number"
                  min={2}
                  max={12}
                  value={vidDuration}
                  onChange={(e) => setVidDuration(e.target.value)}
                  className="h-8 text-xs tabular-nums"
                />
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-4 pt-1 text-xs">
              <label className="flex cursor-pointer items-center gap-1.5">
                <Checkbox
                  checked={vidAudio}
                  onCheckedChange={(checked) => setVidAudio(Boolean(checked))}
                />
                <span>生成音频 (generateAudio)</span>
              </label>
              <label className="flex cursor-pointer items-center gap-1.5">
                <Checkbox
                  checked={vidWatermark}
                  onCheckedChange={(checked) =>
                    setVidWatermark(Boolean(checked))
                  }
                />
                <span>保留水印 (watermark)</span>
              </label>
              <div className="flex items-center gap-1.5">
                <Label
                  htmlFor="vid-seed-input"
                  className="text-[11px] text-muted-foreground"
                >
                  Seed
                </Label>
                <Input
                  id="vid-seed-input"
                  type="number"
                  value={vidSeed}
                  onChange={(e) => setVidSeed(e.target.value)}
                  placeholder="随机"
                  className="h-7 w-28 text-xs tabular-nums"
                />
              </div>
            </div>
          </div>
        )}
      </div>

      <Button
        type="button"
        disabled={
          submitting ||
          !prompt.trim() ||
          imageInputUnsupported ||
          maskBlockedByOpenAi
        }
        onClick={() => void handleSubmit()}
        className="w-full"
      >
        <SparklesIcon />
        <span>
          {submitting
            ? "提交生成任务中..."
            : `提交${genType === "IMAGE" ? "图片" : "视频"}生成任务`}
        </span>
      </Button>
    </div>
  );
}

export function AssetsGeneratePanel() {
  const {
    conversationId,
    userId,
    localAssets,
    quickGenDraft,
    registerLocalAsset,
    importAssetById,
    sendToChatComposer,
    refreshSilent,
    openCanvas,
  } = useStudio();

  const fileInputRef = useRef<HTMLInputElement>(null);
  const quickFormRef = useRef<QuickFormHandle>(null);

  const [uploading, setUploading] = useState(false);
  const [uploadPercent, setUploadPercent] = useState(0);
  const [importIdInput, setImportIdInput] = useState("");
  const [importing, setImporting] = useState(false);

  const handleUploadFiles = async (event: ChangeEvent<HTMLInputElement>) => {
    const picked = Array.from(event.target.files ?? []);
    event.target.value = "";
    if (!picked.length || !userId) {
      return;
    }

    setUploading(true);
    setUploadPercent(0);
    try {
      for (let i = 0; i < picked.length; i += 1) {
        const asset = await uploadConversationAsset({
          conversationId,
          file: picked[i],
          onProgress: (pct) => {
            setUploadPercent(
              Math.round(((i + pct / 100) / picked.length) * 100)
            );
          },
        });
        registerLocalAsset(asset);
      }
      notifySuccess(`已上传 ${picked.length} 项素材`);
    } catch (err) {
      notifyError(
        err instanceof Error ? err.message : "上传素材失败",
        "请检查网络连接后重试"
      );
    } finally {
      setUploading(false);
      setUploadPercent(0);
    }
  };

  const handleImportById = async () => {
    const trimmed = importIdInput.trim();
    if (!trimmed) {
      return;
    }
    setImporting(true);
    try {
      await importAssetById(trimmed);
      setImportIdInput("");
      notifySuccess("已导入当前会话资产");
    } catch (err) {
      notifyApiError(err, "导入资产失败");
    } finally {
      setImporting(false);
    }
  };

  return (
    <div className="h-full space-y-5 overflow-y-auto p-4">
      {/* Quick Generate Form */}
      <QuickGenerateForm
        key={quickGenDraft?.nonce ?? 0}
        ref={quickFormRef}
        initialDraft={quickGenDraft}
        conversationId={conversationId}
        localAssets={localAssets}
        onSubmitted={async () => {
          await refreshSilent();
        }}
        onOpenTasks={() => openCanvas("tasks")}
      />

      {/* Assets Library & RustFS Upload */}
      <div className="space-y-3 rounded-surface-md border bg-card p-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div>
            <h3 className="text-sm font-semibold">会话素材库</h3>
            <p className="text-[11px] text-muted-foreground">
              汇总当前会话已上传、生成与合成归档的媒体资产。
            </p>
          </div>
          <Button
            type="button"
            variant="outline"
            size="xs"
            disabled={uploading}
            onClick={() => fileInputRef.current?.click()}
          >
            <UploadIcon />
            <span>{uploading ? "上传中..." : "上传本地素材"}</span>
          </Button>
          <input
            ref={fileInputRef}
            type="file"
            multiple
            accept="image/*,video/*"
            onChange={(e) => void handleUploadFiles(e)}
            className="hidden"
          />
        </div>

        {uploading ? (
          <div className="space-y-1">
            <div className="flex justify-between text-xs text-muted-foreground">
              <span>正在上传文件...</span>
              <span className="tabular-nums">{uploadPercent}%</span>
            </div>
            <Progress value={uploadPercent} />
          </div>
        ) : null}

        <div className="flex gap-2">
          <Input
            value={importIdInput}
            onChange={(e) => setImportIdInput(e.target.value)}
            placeholder="输入素材编号导入当前会话..."
            className="h-8 text-xs"
          />
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={importing || !importIdInput.trim()}
            onClick={() => void handleImportById()}
          >
            <FolderPlusIcon />
            <span>查询导入</span>
          </Button>
        </div>

        {localAssets.length === 0 ? (
          <div className="rounded-surface-sm border border-dashed p-6 text-center text-xs text-muted-foreground">
            暂无会话素材，点击右上角「上传本地素材」或发起首次生成任务。
          </div>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2">
            {localAssets.map((item) => (
              <div
                key={item.assetId}
                className="flex flex-col gap-2 rounded-surface-sm border p-2.5"
              >
                <AssetMediaPreview
                  assetId={item.assetId}
                  kind={item.kind === "VIDEO" ? "VIDEO" : "IMAGE"}
                  alt={item.label}
                  className="aspect-video w-full"
                />

                <div className="space-y-1">
                  <div className="flex items-center justify-between gap-2">
                    <span className="truncate text-xs font-medium">
                      {item.label}
                    </span>
                    <Badge variant="outline" className="text-[10px]">
                      {item.source === "UPLOAD"
                        ? "上传"
                        : item.source === "COMPOSED"
                          ? "成片"
                          : item.source === "DERIVED"
                            ? "衍生"
                            : "生成"}
                    </Badge>
                  </div>
                  <div className="flex items-center justify-between text-[11px] text-muted-foreground tabular-nums">
                    <span>
                      {item.sizeBytes ? formatBytes(item.sizeBytes) : ""}
                    </span>
                    <span>{formatShortTime(item.createdAt)}</span>
                  </div>
                </div>

                <div className="flex flex-wrap gap-1.5 border-t pt-2">
                  {item.kind === "IMAGE" ? (
                    <Button
                      type="button"
                      variant="outline"
                      size="xs"
                      onClick={() =>
                        sendToChatComposer({ assetIds: [item.assetId] })
                      }
                    >
                      <MessageSquarePlusIcon />
                      <span>带入对话</span>
                    </Button>
                  ) : null}
                  {item.kind !== "VIDEO" ? (
                    <Button
                      type="button"
                      variant="secondary"
                      size="xs"
                      onClick={() =>
                        quickFormRef.current?.addInputAsset(item.assetId)
                      }
                    >
                      <ImagePlusIcon />
                      <span>设为生成输入</span>
                    </Button>
                  ) : null}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
