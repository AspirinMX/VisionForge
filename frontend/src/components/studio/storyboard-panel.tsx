"use client";

import { useMemo, useState } from "react";
import {
  AlertTriangleIcon,
  ArrowDownIcon,
  ArrowUpIcon,
  CheckCircle2Icon,
  ChevronDownIcon,
  ClapperboardIcon,
  Code2Icon,
  FilmIcon,
  ImageIcon,
  LayoutGridIcon,
  LockIcon,
  MessageSquarePlusIcon,
  PlusIcon,
  SaveIcon,
  Trash2Icon,
  UnlockIcon,
  VideoIcon,
} from "lucide-react";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { AssetMediaPreview } from "@/components/common/media-preview";
import { ShotCandidatesSheet } from "@/components/studio/shot-candidates-sheet";
import { useStudio } from "@/components/studio/studio-context";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
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
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { Textarea } from "@/components/ui/textarea";
import { useAsyncData } from "@/hooks/use-async-data";
import { compositionApi, filesApi } from "@/lib/api/endpoints";
import { normalizeUnknownError } from "@/lib/api/errors";
import {
  notifyApiError,
  notifyError,
  notifySuccess,
} from "@/lib/api/feedback";
import type {
  CompositionSpec,
  CompositionTaskResponse,
  ConversationFileResponse,
  ConversationFileVersionResponse,
  GenerationInputRequest,
  StoryboardCamera,
  StoryboardDocument,
  StoryboardRange,
  StoryboardShot,
  StoryboardShotSize,
  StoryboardVideoSelection,
  UUID,
} from "@/lib/api/types";
import { COMPOSITION_STATUS_LABEL, formatDateTime } from "@/lib/format";
import { placeholderStore } from "@/lib/storage/placeholder-store";
import {
  CAMERA_LABEL,
  createBlankStoryboard,
  DEFAULT_COMPOSITION_SPEC,
  parseStoryboardJson,
  SHOT_SIZE_LABEL,
  SHOT_STATUS_LABEL,
  sortShotsByOrder,
  STORYBOARD_CAMERAS,
  STORYBOARD_SHOT_SIZES,
  validateStoryboardDocument,
} from "@/lib/studio/storyboard";
import { cn } from "@/lib/utils";

interface LocalDraftState {
  baseVersion: number;
  jsonText: string;
}

interface ConflictDialogState {
  fileId: UUID;
  localJson: string;
  serverFile: ConversationFileResponse;
  serverVersion: ConversationFileVersionResponse;
}

const COMPOSITION_STAGES = [
  "PENDING",
  "VALIDATING",
  "COMPOSING",
  "UPLOADING",
  "PUBLISHING",
  "SUCCEEDED",
] as const;

export function StoryboardPanel() {
  const {
    conversationId,
    userId,
    files,
    compositionTasks,
    selectedFileId,
    setSelectedFileId,
    openQuickGenerate,
    sendToChatComposer,
    refreshSilent,
    trackCompositionTask,
  } = useStudio();

  const storyboardFiles = useMemo(
    () => files.filter((file) => file.kind === "STORYBOARD"),
    [files]
  );

  const activeFile = useMemo(() => {
    if (storyboardFiles.length === 0) {
      return null;
    }
    if (selectedFileId) {
      const matched = storyboardFiles.find((f) => f.id === selectedFileId);
      if (matched) {
        return matched;
      }
    }
    return storyboardFiles[0];
  }, [selectedFileId, storyboardFiles]);

  const versionQueryKey = activeFile
    ? `storyboard-ver:${conversationId}:${activeFile.id}:v${activeFile.currentVersion}`
    : "storyboard-ver:none";

  const {
    data: serverVersionData,
    loading: loadingVersion,
    error: versionError,
    reload: reloadVersion,
  } = useAsyncData(
    () => {
      if (!activeFile) {
        return Promise.reject(new Error("No storyboard file"));
      }
      return filesApi.version(
        conversationId,
        activeFile.id,
        activeFile.currentVersion
      );
    },
    versionQueryKey,
    {
      enabled: Boolean(activeFile),
      errorMessage: "加载分镜内容失败",
    }
  );

  const [draftsByFileId, setDraftsByFileId] = useState<
    Record<UUID, LocalDraftState>
  >({});
  const [viewMode, setViewMode] = useState<"cards" | "json">("cards");
  const [saving, setSaving] = useState(false);
  const [creatingFile, setCreatingFile] = useState(false);
  const [newTitleDialogOpen, setNewTitleDialogOpen] = useState(false);
  const [newStoryboardTitle, setNewStoryboardTitle] = useState("主分镜方案");

  const [conflictState, setConflictState] =
    useState<ConflictDialogState | null>(null);
  const [candidateShotId, setCandidateShotId] = useState<string | null>(null);
  const [expandedDetailsByShot, setExpandedDetailsByShot] = useState<
    Record<string, boolean>
  >({});

  // Composition drawer state
  const [compSheetOpen, setCompSheetOpen] = useState(false);
  const [compSpec, setCompSpec] = useState<CompositionSpec>(
    DEFAULT_COMPOSITION_SPEC
  );
  const [submittingComp, setSubmittingComp] = useState(false);
  const [manualCompTaskId, setManualCompTaskId] = useState("");
  const [actingCompId, setActingCompId] = useState<UUID | null>(null);

  const activeDraft = activeFile ? draftsByFileId[activeFile.id] : undefined;
  const isDirty = Boolean(activeDraft);

  const effectiveJsonText =
    activeDraft?.jsonText ?? serverVersionData?.content ?? "";

  const parsedStoryboard = useMemo(
    () => parseStoryboardJson(effectiveJsonText),
    [effectiveJsonText]
  );

  const doc = parsedStoryboard.document;
  const sortedShots = useMemo(
    () => (doc ? sortShotsByOrder(doc.shots) : []),
    [doc]
  );

  const validationErrors = useMemo(
    () => (doc ? validateStoryboardDocument(doc) : []),
    [doc]
  );

  const versionDrifted =
    Boolean(activeFile) &&
    Boolean(activeDraft) &&
    activeFile!.currentVersion > activeDraft!.baseVersion;

  const updateLocalJson = (nextJson: string) => {
    if (!activeFile) {
      return;
    }
    setDraftsByFileId((prev) => ({
      ...prev,
      [activeFile.id]: {
        baseVersion:
          prev[activeFile.id]?.baseVersion ?? activeFile.currentVersion,
        jsonText: nextJson,
      },
    }));
  };

  const updateDocument = (updater: (current: StoryboardDocument) => StoryboardDocument) => {
    if (!doc) {
      return;
    }
    const nextDoc = updater(doc);
    updateLocalJson(JSON.stringify(nextDoc, null, 2));
  };

  const updateShot = (
    shotId: string,
    updater: (shot: StoryboardShot) => StoryboardShot
  ) => {
    updateDocument((current) => ({
      ...current,
      shots: current.shots.map((s) => (s.id === shotId ? updater(s) : s)),
    }));
  };

  const discardDraft = (fileId: UUID) => {
    setDraftsByFileId((prev) => {
      const copy = { ...prev };
      delete copy[fileId];
      return copy;
    });
  };

  const handleCreateBlankStoryboard = async () => {
    const title = newStoryboardTitle.trim() || "主分镜方案";
    setCreatingFile(true);
    try {
      const blank = createBlankStoryboard(title);
      const created = await filesApi.create(conversationId, {
        kind: "STORYBOARD",
        title,
        content: JSON.stringify(blank, null, 2),
      });
      await refreshSilent();
      setSelectedFileId(created.id);
      setNewTitleDialogOpen(false);
      notifySuccess("已创建空白分镜方案");
    } catch (err) {
      notifyApiError(err, "创建分镜方案失败");
    } finally {
      setCreatingFile(false);
    }
  };

  const saveContentToServer = async (
    file: ConversationFileResponse,
    baseVersion: number,
    jsonContent: string
  ): Promise<boolean> => {
    const parsed = parseStoryboardJson(jsonContent);
    if (!parsed.document) {
      notifyError("分镜 JSON 无效", parsed.error ?? undefined);
      return false;
    }
    const errs = validateStoryboardDocument(parsed.document);
    if (errs.length > 0) {
      notifyError("分镜校验未通过", errs[0]);
      return false;
    }

    setSaving(true);
    try {
      await filesApi.update(conversationId, file.id, {
        baseVersion,
        content: JSON.stringify(parsed.document, null, 2),
        title: parsed.document.title || file.title,
      });
      discardDraft(file.id);
      await refreshSilent();
      notifySuccess("已保存分镜新版本");
      return true;
    } catch (err) {
      const apiError = normalizeUnknownError(err, "保存分镜失败");
      if (
        apiError.status === 409 ||
        apiError.code === "ASSET_FILE_VERSION_CONFLICT"
      ) {
        try {
          const latestFile = await filesApi.get(conversationId, file.id);
          const latestVersion = await filesApi.version(
            conversationId,
            file.id,
            latestFile.currentVersion
          );
          setConflictState({
            fileId: file.id,
            localJson: jsonContent,
            serverFile: latestFile,
            serverVersion: latestVersion,
          });
        } catch {
          notifyApiError(err, "分镜版本冲突，请刷新后重试");
        }
      } else {
        notifyApiError(err, "保存分镜失败");
      }
      return false;
    } finally {
      setSaving(false);
    }
  };

  const handleSave = async () => {
    if (!activeFile) {
      return;
    }
    const baseVersion = activeDraft?.baseVersion ?? activeFile.currentVersion;
    await saveContentToServer(activeFile, baseVersion, effectiveJsonText);
  };

  const handleAddShot = () => {
    if (!doc) {
      return;
    }
    const maxOrder = doc.shots.reduce((max, s) => Math.max(max, s.order), 0);
    let idx = doc.shots.length + 1;
    const existingIds = new Set(doc.shots.map((s) => s.id));
    while (existingIds.has(`shot-${idx}`)) {
      idx += 1;
    }
    const newShot: StoryboardShot = {
      id: `shot-${idx}`,
      order: maxOrder + 1,
      sceneId: "scene-1",
      durationSeconds: 5,
      visual: "",
      firstFramePrompt: "第一帧画面构图与光线提示词",
      videoPrompt: "镜头动作、主体位移与运镜轨迹提示词",
      shotSize: "MEDIUM",
      camera: "STATIC",
      dialogue: "",
      voiceOver: "",
      sound: "",
      references: [],
      firstFrame: null,
      videoCandidates: [],
      selectedVideo: null,
      usedRange: null,
      status: "DRAFT",
    };
    updateDocument((current) => ({
      ...current,
      shots: [...current.shots, newShot],
    }));
  };

  const handleMoveShot = (shotId: string, direction: -1 | 1) => {
    if (!doc) {
      return;
    }
    const ordered = sortShotsByOrder(doc.shots);
    const index = ordered.findIndex((s) => s.id === shotId);
    const targetIndex = index + direction;
    if (index < 0 || targetIndex < 0 || targetIndex >= ordered.length) {
      return;
    }
    const swapped = [...ordered];
    const [moved] = swapped.splice(index, 1);
    swapped.splice(targetIndex, 0, moved);
    updateDocument((current) => ({
      ...current,
      shots: swapped.map((s, idx) => ({ ...s, order: idx + 1 })),
    }));
  };

  const handleRemoveShot = (shotId: string) => {
    if (!doc || doc.shots.length <= 1) {
      notifyError("分镜至少保留一个镜头");
      return;
    }
    updateDocument((current) => {
      const remaining = sortShotsByOrder(
        current.shots.filter((s) => s.id !== shotId)
      );
      return {
        ...current,
        shots: remaining.map((s, idx) => ({ ...s, order: idx + 1 })),
      };
    });
  };

  const triggerFirstFrameGen = (shot: StoryboardShot) => {
    if (!activeFile) {
      return;
    }
    const inputs: GenerationInputRequest[] = (shot.references ?? [])
      .slice(0, 8)
      .map((ref) => ({
        assetId: ref.assetId,
        role: "REFERENCE",
      }));
    openQuickGenerate({
      type: "IMAGE",
      prompt: shot.firstFramePrompt,
      shotId: shot.id,
      storyboardFileId: activeFile.id,
      storyboardVersion: activeFile.currentVersion,
      inputs,
    });
  };

  const triggerVideoGen = (shot: StoryboardShot) => {
    if (!activeFile) {
      return;
    }
    const inputs: GenerationInputRequest[] = [];
    if (shot.firstFrame?.assetId) {
      inputs.push({
        assetId: shot.firstFrame.assetId,
        role: "FIRST_FRAME",
      });
    }
    for (const ref of shot.references ?? []) {
      if (inputs.length >= 8) {
        break;
      }
      if (!inputs.some((item) => item.assetId === ref.assetId)) {
        inputs.push({
          assetId: ref.assetId,
          role: "REFERENCE",
        });
      }
    }
    openQuickGenerate({
      type: "VIDEO",
      prompt: shot.videoPrompt,
      parameters: {
        durationSeconds: Math.min(Math.max(shot.durationSeconds, 2), 12),
      },
      shotId: shot.id,
      storyboardFileId: activeFile.id,
      storyboardVersion: activeFile.currentVersion,
      inputs,
    });
  };

  const handleApplyCandidateSelection = async (
    shotId: string,
    selectedVideo: StoryboardVideoSelection | null,
    usedRange: StoryboardRange | null
  ) => {
    if (!activeFile || !doc) {
      return;
    }
    const updatedDoc: StoryboardDocument = {
      ...doc,
      shots: doc.shots.map((shot) =>
        shot.id === shotId
          ? {
              ...shot,
              selectedVideo,
              usedRange,
              status: selectedVideo ? "APPROVED" : shot.status,
            }
          : shot
      ),
    };
    const nextJson = JSON.stringify(updatedDoc, null, 2);
    const baseVersion = activeDraft?.baseVersion ?? activeFile.currentVersion;
    const saved = await saveContentToServer(activeFile, baseVersion, nextJson);
    if (saved) {
      setCandidateShotId(null);
    }
  };

  const candidateShot = useMemo(
    () =>
      candidateShotId
        ? sortedShots.find((s) => s.id === candidateShotId) ?? null
        : null,
    [candidateShotId, sortedShots]
  );

  // Composition pre-flight check
  const missingVideoShots = useMemo(
    () => sortedShots.filter((shot) => !shot.selectedVideo?.assetId),
    [sortedShots]
  );

  const handleSubmitComposition = async () => {
    if (!activeFile || !userId) {
      return;
    }
    if (isDirty) {
      notifyError("请先保存当前分镜修改后再发起成片合成");
      return;
    }
    if (missingVideoShots.length > 0) {
      notifyError(
        `尚有 ${missingVideoShots.length} 个镜头未选定成片视频 (selectedVideo)`
      );
      return;
    }

    setSubmittingComp(true);
    try {
      const task = await compositionApi.create({
        conversationId,
        storyboardFileId: activeFile.id,
        storyboardVersion: activeFile.currentVersion,
        outputSpec: compSpec,
      });
      placeholderStore.addCompositionTaskId(userId, conversationId, task.taskId);
      await refreshSilent();
      notifySuccess("已提交 FFmpeg 成片合成任务");
    } catch (err) {
      notifyApiError(err, "提交成片合成失败");
    } finally {
      setSubmittingComp(false);
    }
  };

  const handleTrackManualComp = async () => {
    const trimmed = manualCompTaskId.trim();
    if (!trimmed) {
      return;
    }
    try {
      await trackCompositionTask(trimmed);
      setManualCompTaskId("");
      notifySuccess("已追踪合成任务");
    } catch (err) {
      notifyApiError(err, "查询合成任务失败");
    }
  };

  const handleCompAction = async (
    task: CompositionTaskResponse,
    action: "cancel" | "retry"
  ) => {
    setActingCompId(task.taskId);
    try {
      if (action === "cancel") {
        await compositionApi.cancel(task.taskId);
        notifySuccess("已请求取消合成");
      } else {
        await compositionApi.retry(task.taskId);
        notifySuccess("已重试合成任务");
      }
      await refreshSilent();
    } catch (err) {
      notifyApiError(err, action === "cancel" ? "取消合成失败" : "重试合成失败");
    } finally {
      setActingCompId(null);
    }
  };

  if (storyboardFiles.length === 0) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 p-6 text-center">
        <div className="flex size-12 items-center justify-center rounded-2xl border bg-muted/40">
          <FilmIcon className="size-5 text-muted-foreground" />
        </div>
        <div className="max-w-sm space-y-1.5">
          <p className="text-sm font-medium">当前会话暂无分镜方案</p>
          <p className="text-xs text-muted-foreground">
            可以让左侧导演 Agent 为你生成完整分镜 JSON，或直接创建空白分镜草案开始编排镜头。
          </p>
        </div>
        <div className="flex flex-wrap items-center justify-center gap-2">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() =>
              sendToChatComposer({
                promptAppend:
                  "请结合我们上面的讨论，创建一份可执行的分镜方案（STORYBOARD JSON）。",
              })
            }
          >
            <MessageSquarePlusIcon />
            <span>让 Agent 生成分镜</span>
          </Button>
          <Button
            type="button"
            size="sm"
            onClick={() => setNewTitleDialogOpen(true)}
          >
            <PlusIcon />
            <span>新建空白分镜</span>
          </Button>
        </div>

        <Dialog open={newTitleDialogOpen} onOpenChange={setNewTitleDialogOpen}>
          <DialogContent className="sm:max-w-md">
            <DialogHeader>
              <DialogTitle>新建分镜方案</DialogTitle>
              <DialogDescription>
                初始化包含 1 个默认镜头的标准分镜 JSON 文件。
              </DialogDescription>
            </DialogHeader>
            <div className="space-y-2 py-2">
              <Label htmlFor="new-sb-title">分镜名称</Label>
              <Input
                id="new-sb-title"
                value={newStoryboardTitle}
                onChange={(e) => setNewStoryboardTitle(e.target.value)}
              />
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="outline"
                onClick={() => setNewTitleDialogOpen(false)}
              >
                取消
              </Button>
              <Button
                type="button"
                disabled={creatingFile}
                onClick={() => void handleCreateBlankStoryboard()}
              >
                {creatingFile ? "创建中..." : "创建分镜"}
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </div>
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* Toolbar */}
      <div className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-2.5">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          {storyboardFiles.length > 1 ? (
            <Select
              value={activeFile?.id ?? null}
              onValueChange={(val) => {
                if (val) {
                  setSelectedFileId(val);
                }
              }}
            >
              <SelectTrigger size="sm" className="max-w-52">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {storyboardFiles.map((file) => (
                  <SelectItem key={file.id} value={file.id}>
                    {file.title} (v{file.currentVersion})
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          ) : (
            <span className="truncate text-sm font-medium">
              {activeFile?.title}
            </span>
          )}

          {activeFile ? (
            <Badge variant="outline" className="font-mono text-[11px] tabular-nums">
              v{activeFile.currentVersion}
            </Badge>
          ) : null}

          {isDirty ? (
            <Badge variant="secondary">未保存修改</Badge>
          ) : null}
        </div>

        <div className="flex flex-wrap items-center gap-1.5">
          <Button
            type="button"
            variant="ghost"
            size="xs"
            onClick={() => setNewTitleDialogOpen(true)}
          >
            <PlusIcon />
            <span>新建</span>
          </Button>

          <Button
            type="button"
            variant="outline"
            size="xs"
            onClick={() =>
              setViewMode((mode) => (mode === "cards" ? "json" : "cards"))
            }
          >
            {viewMode === "cards" ? <Code2Icon /> : <LayoutGridIcon />}
            <span>{viewMode === "cards" ? "JSON" : "卡片"}</span>
          </Button>

          <Button
            type="button"
            variant="outline"
            size="xs"
            disabled={!isDirty || saving}
            onClick={() => void handleSave()}
          >
            <SaveIcon />
            <span>{saving ? "保存中..." : "保存分镜"}</span>
          </Button>

          <Button
            type="button"
            size="xs"
            onClick={() => setCompSheetOpen(true)}
          >
            <ClapperboardIcon />
            <span>合成成片</span>
          </Button>
        </div>
      </div>

      {/* Main Content */}
      <div className="flex-1 space-y-4 overflow-y-auto p-4">
        <ApiErrorAlert error={versionError} title="读取分镜版本失败" />

        {versionDrifted && activeFile ? (
          <Alert>
            <AlertTriangleIcon />
            <AlertTitle>服务端分镜已有更新版本 (v{activeFile.currentVersion})</AlertTitle>
            <AlertDescription className="flex flex-wrap items-center justify-between gap-2">
              <span>
                你当前的本地草稿基于 v{activeDraft?.baseVersion}，后台生成回写或 Agent 修改已生成新版本。
              </span>
              <Button
                type="button"
                variant="outline"
                size="xs"
                onClick={() => {
                  discardDraft(activeFile.id);
                  reloadVersion();
                }}
              >
                放弃草稿并加载最新服务端版本
              </Button>
            </AlertDescription>
          </Alert>
        ) : null}

        {parsedStoryboard.error ? (
          <Alert variant="destructive">
            <AlertTriangleIcon />
            <AlertTitle>JSON 解析失败</AlertTitle>
            <AlertDescription>{parsedStoryboard.error}</AlertDescription>
          </Alert>
        ) : null}

        {validationErrors.length > 0 ? (
          <Alert variant="destructive">
            <AlertTriangleIcon />
            <AlertTitle>分镜校验存在 {validationErrors.length} 项问题</AlertTitle>
            <AlertDescription>
              <ul className="list-disc pl-4 text-xs">
                {validationErrors.slice(0, 4).map((msg, i) => (
                  <li key={i}>{msg}</li>
                ))}
              </ul>
            </AlertDescription>
          </Alert>
        ) : null}

        {loadingVersion && !serverVersionData ? (
          <div className="py-10 text-center text-xs text-muted-foreground">
            正在加载分镜方案内容...
          </div>
        ) : viewMode === "json" ? (
          <div className="space-y-2">
            <div className="flex items-center justify-between text-xs text-muted-foreground">
              <span>编辑标准 Storyboard v1 JSON（保存时执行服务端规则校验）</span>
              {isDirty && activeFile ? (
                <Button
                  type="button"
                  variant="ghost"
                  size="xs"
                  onClick={() => discardDraft(activeFile.id)}
                >
                  撤销未保存修改
                </Button>
              ) : null}
            </div>
            <Textarea
              value={effectiveJsonText}
              onChange={(e) => updateLocalJson(e.target.value)}
              rows={24}
              className="font-mono text-xs leading-relaxed"
            />
          </div>
        ) : doc ? (
          <div className="space-y-4">
            {/* Project metadata header */}
            <div className="grid gap-3 rounded-2xl border bg-card p-3.5 sm:grid-cols-4">
              <div className="space-y-1 sm:col-span-2">
                <Label className="text-xs text-muted-foreground">分镜标题</Label>
                <Input
                  value={doc.title}
                  onChange={(e) =>
                    updateDocument((c) => ({ ...c, title: e.target.value }))
                  }
                />
              </div>
              <div className="space-y-1">
                <Label className="text-xs text-muted-foreground">画幅比例</Label>
                <Input
                  value={doc.aspectRatio ?? "16:9"}
                  onChange={(e) =>
                    updateDocument((c) => ({
                      ...c,
                      aspectRatio: e.target.value,
                    }))
                  }
                />
              </div>
              <div className="space-y-1">
                <Label className="text-xs text-muted-foreground">视觉基调</Label>
                <Input
                  value={doc.style ?? ""}
                  onChange={(e) =>
                    updateDocument((c) => ({ ...c, style: e.target.value }))
                  }
                  placeholder="统一电影质感"
                />
              </div>
            </div>

            {/* Shot List */}
            <div className="space-y-3">
              {sortedShots.map((shot, idx) => {
                const isLocked = shot.status === "LOCKED";
                const detailsOpen = Boolean(expandedDetailsByShot[shot.id]);
                const candidateCount = shot.videoCandidates?.length ?? 0;

                return (
                  <div
                    key={shot.id}
                    className="space-y-3 rounded-2xl border bg-card p-4"
                  >
                    {/* Shot header */}
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge variant="outline" className="tabular-nums">
                          #{shot.order}
                        </Badge>
                        <span className="font-mono text-xs font-medium">
                          {shot.id}
                        </span>
                        {shot.sceneId ? (
                          <Badge variant="secondary" className="text-[11px]">
                            {shot.sceneId}
                          </Badge>
                        ) : null}
                        <Badge
                          variant={
                            shot.status === "FAILED"
                              ? "destructive"
                              : shot.selectedVideo
                                ? "default"
                                : "outline"
                          }
                        >
                          {SHOT_STATUS_LABEL[shot.status]}
                        </Badge>
                      </div>

                      <div className="flex items-center gap-1">
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon-xs"
                          disabled={idx === 0 || isLocked}
                          onClick={() => handleMoveShot(shot.id, -1)}
                          aria-label="上移镜头"
                        >
                          <ArrowUpIcon />
                        </Button>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon-xs"
                          disabled={idx === sortedShots.length - 1 || isLocked}
                          onClick={() => handleMoveShot(shot.id, 1)}
                          aria-label="下移镜头"
                        >
                          <ArrowDownIcon />
                        </Button>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon-xs"
                          onClick={() =>
                            updateShot(shot.id, (s) => ({
                              ...s,
                              status:
                                s.status === "LOCKED"
                                  ? s.selectedVideo
                                    ? "APPROVED"
                                    : "DRAFT"
                                  : "LOCKED",
                            }))
                          }
                          aria-label={isLocked ? "解锁镜头" : "锁定镜头"}
                        >
                          {isLocked ? <LockIcon /> : <UnlockIcon />}
                        </Button>
                        <Button
                          type="button"
                          variant="ghost"
                          size="icon-xs"
                          disabled={isLocked || sortedShots.length <= 1}
                          onClick={() => handleRemoveShot(shot.id)}
                          aria-label="删除镜头"
                        >
                          <Trash2Icon />
                        </Button>
                      </div>
                    </div>

                    {/* Specs row */}
                    <div className="grid grid-cols-3 gap-2">
                      <div className="space-y-1">
                        <Label className="text-[11px] text-muted-foreground">
                          时长（秒）
                        </Label>
                        <Input
                          type="number"
                          min={1}
                          max={60}
                          disabled={isLocked}
                          value={shot.durationSeconds}
                          onChange={(e) =>
                            updateShot(shot.id, (s) => ({
                              ...s,
                              durationSeconds: Math.min(
                                60,
                                Math.max(1, Number(e.target.value) || 1)
                              ),
                            }))
                          }
                          className="h-8 text-xs tabular-nums"
                        />
                      </div>

                      <div className="space-y-1">
                        <Label className="text-[11px] text-muted-foreground">
                          景别
                        </Label>
                        <Select
                          disabled={isLocked}
                          value={shot.shotSize}
                          onValueChange={(val) => {
                            if (val) {
                              updateShot(shot.id, (s) => ({
                                ...s,
                                shotSize: val as StoryboardShotSize,
                              }));
                            }
                          }}
                        >
                          <SelectTrigger size="sm">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {STORYBOARD_SHOT_SIZES.map((size) => (
                              <SelectItem key={size} value={size}>
                                {SHOT_SIZE_LABEL[size]}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>

                      <div className="space-y-1">
                        <Label className="text-[11px] text-muted-foreground">
                          运镜
                        </Label>
                        <Select
                          disabled={isLocked}
                          value={shot.camera}
                          onValueChange={(val) => {
                            if (val) {
                              updateShot(shot.id, (s) => ({
                                ...s,
                                camera: val as StoryboardCamera,
                              }));
                            }
                          }}
                        >
                          <SelectTrigger size="sm">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {STORYBOARD_CAMERAS.map((cam) => (
                              <SelectItem key={cam} value={cam}>
                                {CAMERA_LABEL[cam]}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                      </div>
                    </div>

                    {/* Visual / First frame / Selected video media strip */}
                    <div className="grid gap-3 sm:grid-cols-2">
                      <div className="space-y-1.5">
                        <span className="text-[11px] font-medium text-muted-foreground">
                          首帧图片 (firstFrame)
                        </span>
                        {shot.firstFrame?.assetId ? (
                          <AssetMediaPreview
                            assetId={shot.firstFrame.assetId}
                            kind="IMAGE"
                            alt={`${shot.id} 首帧`}
                            className="aspect-video w-full"
                          />
                        ) : (
                          <div className="flex aspect-video items-center justify-center rounded-xl border border-dashed bg-muted/20 text-xs text-muted-foreground">
                            尚未生成或回写首帧
                          </div>
                        )}
                      </div>

                      <div className="space-y-1.5">
                        <div className="flex items-center justify-between text-[11px] font-medium text-muted-foreground">
                          <span>已选成片片段 (selectedVideo)</span>
                          {shot.usedRange ? (
                            <span className="font-mono tabular-nums">
                              {shot.usedRange.startSeconds}s–
                              {shot.usedRange.endSeconds}s
                            </span>
                          ) : null}
                        </div>
                        {shot.selectedVideo?.assetId ? (
                          <AssetMediaPreview
                            assetId={shot.selectedVideo.assetId}
                            kind="VIDEO"
                            alt={`${shot.id} 已选视频`}
                            className="aspect-video w-full"
                          />
                        ) : (
                          <div className="flex aspect-video items-center justify-center rounded-xl border border-dashed bg-muted/20 text-xs text-muted-foreground">
                            尚未选定入轨视频 ({candidateCount} 个候选)
                          </div>
                        )}
                      </div>
                    </div>

                    {/* Prompts */}
                    <div className="space-y-2.5">
                      <div className="space-y-1">
                        <Label className="text-xs text-muted-foreground">
                          画面与叙事摘要 (visual)
                        </Label>
                        <Input
                          disabled={isLocked}
                          value={shot.visual ?? ""}
                          onChange={(e) =>
                            updateShot(shot.id, (s) => ({
                              ...s,
                              visual: e.target.value,
                            }))
                          }
                          placeholder="一句话描述本镜头在短片中的作用"
                        />
                      </div>

                      <div className="space-y-1">
                        <div className="flex items-center justify-between">
                          <Label className="text-xs font-medium">
                            首帧静态生图提示词 (firstFramePrompt · 仅描起始帧)
                          </Label>
                        </div>
                        <Textarea
                          disabled={isLocked}
                          rows={2}
                          value={shot.firstFramePrompt}
                          onChange={(e) =>
                            updateShot(shot.id, (s) => ({
                              ...s,
                              firstFramePrompt: e.target.value,
                            }))
                          }
                          className="text-xs"
                        />
                      </div>

                      <div className="space-y-1">
                        <Label className="text-xs font-medium">
                          视频运动生成提示词 (videoPrompt · 仅描后续动作与运镜)
                        </Label>
                        <Textarea
                          disabled={isLocked}
                          rows={2}
                          value={shot.videoPrompt}
                          onChange={(e) =>
                            updateShot(shot.id, (s) => ({
                              ...s,
                              videoPrompt: e.target.value,
                            }))
                          }
                          className="text-xs"
                        />
                      </div>
                    </div>

                    {/* Collapsible dialogue & sound */}
                    <Collapsible
                      open={detailsOpen}
                      onOpenChange={(open) =>
                        setExpandedDetailsByShot((prev) => ({
                          ...prev,
                          [shot.id]: open,
                        }))
                      }
                      className="rounded-xl border bg-muted/20"
                    >
                      <CollapsibleTrigger
                        render={
                          <button
                            type="button"
                            className="flex w-full items-center justify-between px-3 py-2 text-xs text-muted-foreground hover:text-foreground"
                          />
                        }
                      >
                        <span>对白 / 旁白 / 声音提示 ({shot.references?.length ?? 0} 个引用参考)</span>
                        <ChevronDownIcon
                          className={cn(
                            "size-3.5 transition-transform",
                            detailsOpen && "rotate-180"
                          )}
                        />
                      </CollapsibleTrigger>
                      <CollapsibleContent className="space-y-2 border-t p-3">
                        <div className="grid gap-2 sm:grid-cols-3">
                          <div className="space-y-1">
                            <Label className="text-[11px] text-muted-foreground">
                              对白 (dialogue)
                            </Label>
                            <Input
                              disabled={isLocked}
                              value={shot.dialogue ?? ""}
                              onChange={(e) =>
                                updateShot(shot.id, (s) => ({
                                  ...s,
                                  dialogue: e.target.value,
                                }))
                              }
                            />
                          </div>
                          <div className="space-y-1">
                            <Label className="text-[11px] text-muted-foreground">
                              旁白 (voiceOver)
                            </Label>
                            <Input
                              disabled={isLocked}
                              value={shot.voiceOver ?? ""}
                              onChange={(e) =>
                                updateShot(shot.id, (s) => ({
                                  ...s,
                                  voiceOver: e.target.value,
                                }))
                              }
                            />
                          </div>
                          <div className="space-y-1">
                            <Label className="text-[11px] text-muted-foreground">
                              声音 (sound)
                            </Label>
                            <Input
                              disabled={isLocked}
                              value={shot.sound ?? ""}
                              onChange={(e) =>
                                updateShot(shot.id, (s) => ({
                                  ...s,
                                  sound: e.target.value,
                                }))
                              }
                            />
                          </div>
                        </div>
                      </CollapsibleContent>
                    </Collapsible>

                    {/* Action footer */}
                    <div className="flex flex-wrap items-center gap-1.5 border-t pt-2.5">
                      <Button
                        type="button"
                        variant="outline"
                        size="xs"
                        disabled={isLocked}
                        onClick={() => triggerFirstFrameGen(shot)}
                      >
                        <ImageIcon />
                        <span>生成首帧</span>
                      </Button>

                      <Button
                        type="button"
                        variant="outline"
                        size="xs"
                        disabled={isLocked}
                        onClick={() => triggerVideoGen(shot)}
                      >
                        <VideoIcon />
                        <span>生成视频候选</span>
                      </Button>

                      <Button
                        type="button"
                        variant="secondary"
                        size="xs"
                        onClick={() => setCandidateShotId(shot.id)}
                      >
                        <FilmIcon />
                        <span className="tabular-nums">
                          候选对比 ({candidateCount})
                        </span>
                      </Button>

                      <Button
                        type="button"
                        variant="ghost"
                        size="xs"
                        onClick={() =>
                          sendToChatComposer({
                            promptAppend: `请调整分镜「${doc.title}」中的镜头 ${shot.id}（#${shot.order}，当前状态 ${SHOT_STATUS_LABEL[shot.status]}）：`,
                            assetIds: shot.firstFrame?.assetId
                              ? [shot.firstFrame.assetId]
                              : undefined,
                          })
                        }
                      >
                        <MessageSquarePlusIcon />
                        <span>让 Agent 调整此镜</span>
                      </Button>
                    </div>
                  </div>
                );
              })}
            </div>

            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={handleAddShot}
              className="w-full border-dashed"
            >
              <PlusIcon />
              <span>追加新镜头</span>
            </Button>
          </div>
        ) : null}
      </div>

      {/* Candidate comparison sheet */}
      {candidateShot ? (
        <ShotCandidatesSheet
          key={`${candidateShot.id}:${candidateShot.selectedVideo?.taskId ?? "none"}:${candidateShot.videoCandidates?.length ?? 0}`}
          shot={candidateShot}
          open={Boolean(candidateShot)}
          onOpenChange={(open) => {
            if (!open) {
              setCandidateShotId(null);
            }
          }}
          saving={saving}
          onApplySelection={handleApplyCandidateSelection}
        />
      ) : null}

      {/* 409 Version Conflict Resolution Dialog */}
      <Dialog
        open={Boolean(conflictState)}
        onOpenChange={(open) => {
          if (!open) {
            setConflictState(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>检测到分镜版本冲突 (409)</DialogTitle>
            <DialogDescription>
              服务端版本已推进至 v{conflictState?.serverFile.currentVersion}，与当前编辑基准不一致。你可以对比后选择覆盖保存或采用服务端版本。
            </DialogDescription>
          </DialogHeader>

          {conflictState ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1.5">
                <Label className="text-xs">
                  服务端最新版本 (v{conflictState.serverFile.currentVersion})
                </Label>
                <Textarea
                  readOnly
                  rows={12}
                  value={conflictState.serverVersion.content ?? ""}
                  className="font-mono text-[11px]"
                />
              </div>
              <div className="space-y-1.5">
                <Label className="text-xs">你的本地修改草稿</Label>
                <Textarea
                  readOnly
                  rows={12}
                  value={conflictState.localJson}
                  className="font-mono text-[11px]"
                />
              </div>
            </div>
          ) : null}

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                if (conflictState) {
                  discardDraft(conflictState.fileId);
                  setConflictState(null);
                  void refreshSilent();
                }
              }}
            >
              加载服务端最新版本
            </Button>
            <Button
              type="button"
              disabled={saving}
              onClick={async () => {
                if (!conflictState) {
                  return;
                }
                const targetFile = conflictState.serverFile;
                const localJson = conflictState.localJson;
                setConflictState(null);
                await saveContentToServer(
                  targetFile,
                  targetFile.currentVersion,
                  localJson
                );
              }}
            >
              基于 v{conflictState?.serverFile.currentVersion} 重新提交
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* New Storyboard Title Dialog */}
      <Dialog open={newTitleDialogOpen} onOpenChange={setNewTitleDialogOpen}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>新建分镜方案</DialogTitle>
            <DialogDescription>
              创建新的 STORYBOARD JSON 文件（可与历史版本并存比较）。
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-2 py-2">
            <Label htmlFor="sb-title-input">分镜名称</Label>
            <Input
              id="sb-title-input"
              value={newStoryboardTitle}
              onChange={(e) => setNewStoryboardTitle(e.target.value)}
            />
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setNewTitleDialogOpen(false)}
            >
              取消
            </Button>
            <Button
              type="button"
              disabled={creatingFile}
              onClick={() => void handleCreateBlankStoryboard()}
            >
              {creatingFile ? "创建中..." : "创建分镜"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* FFmpeg Composition Drawer */}
      <Sheet open={compSheetOpen} onOpenChange={setCompSheetOpen}>
        <SheetContent className="flex w-full flex-col sm:max-w-2xl">
          <SheetHeader>
            <SheetTitle className="flex items-center gap-2">
              <ClapperboardIcon className="size-4" />
              <span>FFmpeg 成片合成与溯源</span>
            </SheetTitle>
            <SheetDescription>
              基于当前分镜版本（{activeFile?.title} · v{activeFile?.currentVersion}）将每个镜头的已选视频拼接转码为最终成片。
            </SheetDescription>
          </SheetHeader>

          <div className="flex-1 space-y-5 overflow-y-auto px-4 py-2">
            {/* Pre-flight Check */}
            {isDirty ? (
              <Alert variant="destructive">
                <AlertTriangleIcon />
                <AlertTitle>分镜存在未保存修改</AlertTitle>
                <AlertDescription>
                  请先关闭抽屉并保存分镜，确保服务端读取到最新的 selectedVideo 与裁剪区间。
                </AlertDescription>
              </Alert>
            ) : missingVideoShots.length > 0 ? (
              <Alert>
                <AlertTriangleIcon />
                <AlertTitle>尚有 {missingVideoShots.length} 个镜头未选定成片片段</AlertTitle>
                <AlertDescription>
                  请先为以下镜头生成视频候选并完成选片：
                  {missingVideoShots.map((s) => s.id).join("、")}
                </AlertDescription>
              </Alert>
            ) : (
              <Alert>
                <CheckCircle2Icon />
                <AlertTitle>全部 {sortedShots.length} 个镜头均已满足合成条件</AlertTitle>
                <AlertDescription>
                  将严格按照分镜 order 顺序与 usedRange 进行服务端 FFmpeg 合成并归档为 VIDEO 文件。
                </AlertDescription>
              </Alert>
            )}

            {/* OutputSpec Form */}
            <div className="space-y-3 rounded-xl border bg-muted/20 p-3.5">
              <p className="text-xs font-medium">输出规格 (CompositionSpec)</p>
              <div className="grid grid-cols-3 gap-2.5">
                <div className="space-y-1">
                  <Label className="text-[11px] text-muted-foreground">宽度</Label>
                  <Input
                    type="number"
                    value={compSpec.width}
                    onChange={(e) =>
                      setCompSpec((s) => ({
                        ...s,
                        width: Number(e.target.value) || 1280,
                      }))
                    }
                    className="h-8 text-xs tabular-nums"
                  />
                </div>
                <div className="space-y-1">
                  <Label className="text-[11px] text-muted-foreground">高度</Label>
                  <Input
                    type="number"
                    value={compSpec.height}
                    onChange={(e) =>
                      setCompSpec((s) => ({
                        ...s,
                        height: Number(e.target.value) || 720,
                      }))
                    }
                    className="h-8 text-xs tabular-nums"
                  />
                </div>
                <div className="space-y-1">
                  <Label className="text-[11px] text-muted-foreground">FPS</Label>
                  <Input
                    type="number"
                    value={compSpec.fps}
                    onChange={(e) =>
                      setCompSpec((s) => ({
                        ...s,
                        fps: Number(e.target.value) || 30,
                      }))
                    }
                    className="h-8 text-xs tabular-nums"
                  />
                </div>
                <div className="space-y-1">
                  <Label className="text-[11px] text-muted-foreground">
                    编码器
                  </Label>
                  <Input
                    value={compSpec.videoCodec}
                    onChange={(e) =>
                      setCompSpec((s) => ({ ...s, videoCodec: e.target.value }))
                    }
                    className="h-8 text-xs font-mono"
                  />
                </div>
                <div className="space-y-1">
                  <Label className="text-[11px] text-muted-foreground">Preset</Label>
                  <Input
                    value={compSpec.preset}
                    onChange={(e) =>
                      setCompSpec((s) => ({ ...s, preset: e.target.value }))
                    }
                    className="h-8 text-xs font-mono"
                  />
                </div>
                <div className="space-y-1">
                  <Label className="text-[11px] text-muted-foreground">CRF</Label>
                  <Input
                    type="number"
                    min={0}
                    max={51}
                    value={compSpec.crf}
                    onChange={(e) =>
                      setCompSpec((s) => ({
                        ...s,
                        crf: Number(e.target.value) || 23,
                      }))
                    }
                    className="h-8 text-xs tabular-nums"
                  />
                </div>
              </div>
              <Button
                type="button"
                disabled={
                  submittingComp || isDirty || missingVideoShots.length > 0
                }
                onClick={() => void handleSubmitComposition()}
                className="w-full"
              >
                <ClapperboardIcon />
                <span>{submittingComp ? "提交合成中..." : "发起 FFmpeg 合成任务"}</span>
              </Button>
            </div>

            {/* Track composition by taskId placeholder helper */}
            <div className="space-y-2 rounded-xl border border-dashed p-3">
              <div className="flex items-center justify-between text-xs">
                <span className="font-medium">
                  合成任务记录（客户端会话追踪缓存）
                </span>
                <span className="text-muted-foreground">
                  已追踪 {compositionTasks.length} 条
                </span>
              </div>
              <div className="flex gap-2">
                <Input
                  value={manualCompTaskId}
                  onChange={(e) => setManualCompTaskId(e.target.value)}
                  placeholder="输入合成任务 UUID 以找回或追踪 Agent 触发的合成"
                  className="h-8 text-xs font-mono"
                />
                <Button
                  type="button"
                  variant="outline"
                  size="sm"
                  disabled={!manualCompTaskId.trim()}
                  onClick={() => void handleTrackManualComp()}
                >
                  导入追踪
                </Button>
              </div>
            </div>

            {/* Tracked composition tasks */}
            <div className="space-y-3">
              {compositionTasks.map((comp) => {
                const isRunning =
                  comp.status === "PENDING" ||
                  comp.status === "VALIDATING" ||
                  comp.status === "COMPOSING" ||
                  comp.status === "UPLOADING" ||
                  comp.status === "PUBLISHING";
                const stageIndex = COMPOSITION_STAGES.indexOf(
                  comp.status as (typeof COMPOSITION_STAGES)[number]
                );

                return (
                  <div
                    key={comp.taskId}
                    className="space-y-3 rounded-2xl border bg-card p-3.5"
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <Badge
                          variant={
                            comp.status === "SUCCEEDED"
                              ? "default"
                              : comp.status === "FAILED"
                                ? "destructive"
                                : "secondary"
                          }
                        >
                          {COMPOSITION_STATUS_LABEL[comp.status]}
                        </Badge>
                        <Badge variant="outline" className="font-mono text-[10px]">
                          v{comp.storyboardVersion} · {comp.taskId.slice(0, 8)}
                        </Badge>
                      </div>
                      <span className="text-[11px] text-muted-foreground tabular-nums">
                        {formatDateTime(comp.createdAt)}
                      </span>
                    </div>

                    {/* Stage pipeline */}
                    <div className="grid grid-cols-6 gap-1">
                      {COMPOSITION_STAGES.map((stage, i) => {
                        const reached =
                          stageIndex >= i &&
                          comp.status !== "FAILED" &&
                          comp.status !== "CANCELLED";
                        return (
                          <div
                            key={stage}
                            className={cn(
                              "h-1.5 rounded-full",
                              reached ? "bg-primary" : "bg-muted"
                            )}
                            title={stage}
                          />
                        );
                      })}
                    </div>

                    {comp.errorMessage ? (
                      <p className="text-xs text-destructive">
                        {comp.errorMessage}
                      </p>
                    ) : null}

                    {comp.outputAssetId ? (
                      <AssetMediaPreview
                        assetId={comp.outputAssetId}
                        kind="VIDEO"
                        alt={`成片 ${comp.taskId}`}
                        className="aspect-video w-full"
                      />
                    ) : null}

                    {comp.inputs.length > 0 ? (
                      <div className="space-y-1 rounded-xl bg-muted/30 p-2.5 text-[11px]">
                        <p className="font-medium text-muted-foreground">
                          入轨片段溯源 ({comp.inputs.length})
                        </p>
                        {comp.inputs.map((inp) => (
                          <div
                            key={`${inp.ordinal}-${inp.shotId}`}
                            className="flex items-center justify-between gap-2 font-mono tabular-nums"
                          >
                            <span>
                              #{inp.ordinal} {inp.shotId}
                            </span>
                            <span className="text-muted-foreground">
                              {inp.startSeconds}s–{inp.endSeconds}s · task{" "}
                              {inp.generationTaskId.slice(0, 8)}
                            </span>
                          </div>
                        ))}
                      </div>
                    ) : null}

                    <div className="flex flex-wrap gap-2">
                      {isRunning ? (
                        <Button
                          type="button"
                          variant="outline"
                          size="xs"
                          disabled={actingCompId === comp.taskId}
                          onClick={() => void handleCompAction(comp, "cancel")}
                        >
                          取消合成
                        </Button>
                      ) : null}
                      {comp.status === "FAILED" ||
                      comp.status === "CANCELLED" ? (
                        <Button
                          type="button"
                          variant="outline"
                          size="xs"
                          disabled={actingCompId === comp.taskId}
                          onClick={() => void handleCompAction(comp, "retry")}
                        >
                          重试合成
                        </Button>
                      ) : null}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>

          <SheetFooter className="border-t pt-3">
            <Button
              type="button"
              variant="outline"
              onClick={() => setCompSheetOpen(false)}
            >
              关闭
            </Button>
          </SheetFooter>
        </SheetContent>
      </Sheet>
    </div>
  );
}
