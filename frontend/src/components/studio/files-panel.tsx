"use client";

import { useMemo, useState } from "react";
import {
  Edit3Icon,
  EyeIcon,
  FilePlus2Icon,
  FileTextIcon,
  FilmIcon,
  HistoryIcon,
  RotateCcwIcon,
  SaveIcon,
  SearchIcon,
} from "lucide-react";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { MarkdownView } from "@/components/common/markdown-view";
import { AssetMediaPreview } from "@/components/common/media-preview";
import { useStudio } from "@/components/studio/studio-context";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
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
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import { Textarea } from "@/components/ui/textarea";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { useAsyncData } from "@/hooks/use-async-data";
import { assetsApi, filesApi } from "@/lib/api/endpoints";
import { normalizeUnknownError } from "@/lib/api/errors";
import {
  notifyApiError,
  notifyError,
  notifySuccess,
} from "@/lib/api/feedback";
import type {
  ConversationFileKind,
  ConversationFileResponse,
  ConversationFileSource,
  ConversationFileVersionResponse,
  UUID,
} from "@/lib/api/types";
import { FILE_KIND_LABEL, formatBytes, formatDateTime } from "@/lib/format";
import {
  createBlankStoryboard,
  DEFAULT_SCRIPT_TEMPLATE,
} from "@/lib/studio/storyboard";
import { cn } from "@/lib/utils";

const FILE_SOURCE_LABEL: Record<ConversationFileSource, string> = {
  USER_UPLOAD: "手动保存",
  AGENT: "助手修改",
  TOOL: "工具写入",
  GENERATED: "自动生成",
};

const TEXT_FILE_KINDS: ConversationFileKind[] = [
  "SCRIPT",
  "PROMPT",
  "STORYBOARD",
  "OTHER",
];

const FILE_TITLE_MAX_LENGTH = 200;
const FILE_CONTENT_MAX_BYTES = 1024 * 1024;

function getUtf8ByteLength(text: string): number {
  return new TextEncoder().encode(text).length;
}

interface FileDraft {
  baseVersion: number;
  title: string;
  content: string;
}

interface ConflictInfo {
  file: ConversationFileResponse;
  serverVersion: ConversationFileVersionResponse;
  localTitle: string;
  localContent: string;
}

function getTemplateForKind(kind: ConversationFileKind): string {
  if (kind === "SCRIPT") {
    return DEFAULT_SCRIPT_TEMPLATE;
  }
  if (kind === "STORYBOARD") {
    return JSON.stringify(createBlankStoryboard("新建分镜方案"), null, 2);
  }
  if (kind === "PROMPT") {
    return "# 主视觉与角色一致性提示词\n\n- 主体外观：\n- 场景氛围：\n- 镜头光影：";
  }
  return "";
}

export function FilesPanel() {
  const {
    conversationId,
    files,
    fileAssetIdMap,
    selectedFileId,
    setSelectedFileId,
    openCanvas,
    refreshSilent,
  } = useStudio();

  const [kindFilter, setKindFilter] = useState<ConversationFileKind | "ALL">(
    "ALL"
  );
  const [searchInput, setSearchInput] = useState("");
  const [appliedQuery, setAppliedQuery] = useState("");

  const { data: searchResults, loading: searching } = useAsyncData(
    () =>
      filesApi.search(conversationId, {
        q: appliedQuery.trim(),
        kind: kindFilter === "ALL" ? undefined : kindFilter,
        limit: 100,
      }),
    `files-search:${conversationId}:${kindFilter}:${appliedQuery.trim()}:${files.length}`,
    {
      enabled: Boolean(appliedQuery.trim()),
      errorMessage: "搜索文件失败",
    }
  );

  const visibleFiles = useMemo(() => {
    if (appliedQuery.trim() && searchResults) {
      return searchResults;
    }
    if (kindFilter === "ALL") {
      return files;
    }
    return files.filter((file) => file.kind === kindFilter);
  }, [appliedQuery, files, kindFilter, searchResults]);

  const activeFile = useMemo(() => {
    if (visibleFiles.length === 0) {
      return null;
    }
    if (selectedFileId) {
      const matched = visibleFiles.find((f) => f.id === selectedFileId);
      if (matched) {
        return matched;
      }
      const inAll = files.find((f) => f.id === selectedFileId);
      if (inAll && !appliedQuery.trim() && kindFilter === "ALL") {
        return inAll;
      }
    }
    return visibleFiles[0];
  }, [appliedQuery, files, kindFilter, selectedFileId, visibleFiles]);

  const isTextFile = activeFile?.storageKind === "DB_TEXT";

  const {
    data: activeVersion,
    loading: loadingContent,
    error: contentError,
  } = useAsyncData(
    () => {
      if (!activeFile) {
        return Promise.reject(new Error("No file selected"));
      }
      return filesApi.version(
        conversationId,
        activeFile.id,
        activeFile.currentVersion
      );
    },
    activeFile
      ? `file-ver:${conversationId}:${activeFile.id}:v${activeFile.currentVersion}`
      : "file-ver:none",
    {
      enabled: Boolean(activeFile),
      errorMessage: "加载文件版本失败",
    }
  );

  const [editModeByFile, setEditModeByFile] = useState<Record<UUID, boolean>>(
    {}
  );
  const [draftsByFile, setDraftsByFile] = useState<Record<UUID, FileDraft>>({});
  const [saving, setSaving] = useState(false);
  const [conflict, setConflict] = useState<ConflictInfo | null>(null);
  const [manualAssetIds, setManualAssetIds] = useState<Record<UUID, string>>(
    {}
  );
  const [assetInputValue, setAssetInputValue] = useState("");

  // Create File Dialog
  const [createOpen, setCreateOpen] = useState(false);
  const [createKind, setCreateKind] = useState<ConversationFileKind>("SCRIPT");
  const [createTitle, setCreateTitle] = useState("分场剧本.md");
  const [createContent, setCreateContent] = useState(DEFAULT_SCRIPT_TEMPLATE);
  const [creating, setCreating] = useState(false);
  const [pendingSwitchFileId, setPendingSwitchFileId] = useState<UUID | null>(
    null
  );

  // Version History Sheet
  const [historyOpen, setHistoryOpen] = useState(false);
  const [inspectVersion, setInspectVersion] =
    useState<ConversationFileVersionResponse | null>(null);

  const { data: versionHistory, loading: loadingHistory } = useAsyncData(
    () => {
      if (!activeFile) {
        return Promise.reject(new Error("No file"));
      }
      return filesApi.versions(conversationId, activeFile.id, 30);
    },
    activeFile
      ? `file-history:${conversationId}:${activeFile.id}:v${activeFile.currentVersion}`
      : "file-history:none",
    {
      enabled: Boolean(historyOpen && activeFile),
      errorMessage: "加载文件版本历史失败",
    }
  );

  const activeDraft = activeFile ? draftsByFile[activeFile.id] : undefined;
  const isEditing = activeFile ? Boolean(editModeByFile[activeFile.id]) : false;

  const displayTitle = activeDraft?.title ?? activeFile?.title ?? "";
  const displayContent = activeDraft?.content ?? activeVersion?.content ?? "";
  const isActiveFileDirty = Boolean(
    activeFile &&
      activeDraft &&
      (activeDraft.title !== activeFile.title ||
        activeDraft.content !== (activeVersion?.content ?? ""))
  );
  const editContentBytes = useMemo(
    () => getUtf8ByteLength(displayContent),
    [displayContent]
  );
  const createContentBytes = useMemo(
    () => getUtf8ByteLength(createContent),
    [createContent]
  );
  const isEditInvalid =
    !displayTitle.trim() ||
    displayTitle.trim().length > FILE_TITLE_MAX_LENGTH ||
    !displayContent.trim() ||
    editContentBytes > FILE_CONTENT_MAX_BYTES;
  const isCreateInvalid =
    !createTitle.trim() ||
    createTitle.trim().length > FILE_TITLE_MAX_LENGTH ||
    !createContent.trim() ||
    createContentBytes > FILE_CONTENT_MAX_BYTES;

  const setEditing = (fileId: UUID, next: boolean) => {
    setEditModeByFile((prev) => ({ ...prev, [fileId]: next }));
  };

  const handleSelectFile = (nextFileId: UUID) => {
    if (activeFile && nextFileId === activeFile.id) {
      return;
    }
    if (activeFile && isEditing && isActiveFileDirty) {
      setPendingSwitchFileId(nextFileId);
      return;
    }
    setSelectedFileId(nextFileId);
  };

  const updateDraft = (patch: Partial<FileDraft>) => {
    if (!activeFile) {
      return;
    }
    setDraftsByFile((prev) => {
      const existing = prev[activeFile.id];
      return {
        ...prev,
        [activeFile.id]: {
          baseVersion: existing?.baseVersion ?? activeFile.currentVersion,
          title: patch.title ?? existing?.title ?? activeFile.title,
          content:
            patch.content ??
            existing?.content ??
            activeVersion?.content ??
            "",
        },
      };
    });
  };

  const discardDraft = (fileId: UUID) => {
    setDraftsByFile((prev) => {
      const next = { ...prev };
      delete next[fileId];
      return next;
    });
  };

  const handleSave = async (
    targetFile: ConversationFileResponse,
    baseVersion: number,
    title: string,
    content: string
  ) => {
    const trimmedTitle = title.trim() || targetFile.title;
    if (trimmedTitle.length > FILE_TITLE_MAX_LENGTH) {
      notifyError(`文件标题不能超过 ${FILE_TITLE_MAX_LENGTH} 个字符`);
      return;
    }
    if (!content.trim()) {
      notifyError("文件内容不能为空");
      return;
    }
    if (getUtf8ByteLength(content) > FILE_CONTENT_MAX_BYTES) {
      notifyError("文件内容不能超过 1 MB");
      return;
    }
    setSaving(true);
    try {
      await filesApi.update(conversationId, targetFile.id, {
        baseVersion,
        title: trimmedTitle,
        content,
      });
      discardDraft(targetFile.id);
      setEditing(targetFile.id, false);
      await refreshSilent();
      notifySuccess("文件已保存为新版本");
    } catch (err) {
      const apiErr = normalizeUnknownError(err, "保存文件失败");
      if (
        apiErr.status === 409 ||
        apiErr.code === "ASSET_FILE_VERSION_CONFLICT"
      ) {
        try {
          const latestFile = await filesApi.get(conversationId, targetFile.id);
          const latestVer = await filesApi.version(
            conversationId,
            targetFile.id,
            latestFile.currentVersion
          );
          setConflict({
            file: latestFile,
            serverVersion: latestVer,
            localTitle: title,
            localContent: content,
          });
        } catch {
          notifyApiError(err, "文件版本冲突，请刷新后重试");
        }
      } else {
        notifyApiError(err, "保存文件失败");
      }
    } finally {
      setSaving(false);
    }
  };

  const handleCreateFile = async () => {
    const title = createTitle.trim();
    if (!title || !createContent.trim()) {
      notifyError("请填写文件名称和内容");
      return;
    }
    if (title.length > FILE_TITLE_MAX_LENGTH) {
      notifyError(`文件标题不能超过 ${FILE_TITLE_MAX_LENGTH} 个字符`);
      return;
    }
    if (getUtf8ByteLength(createContent) > FILE_CONTENT_MAX_BYTES) {
      notifyError("文件内容不能超过 1 MB");
      return;
    }
    setCreating(true);
    try {
      const created = await filesApi.create(conversationId, {
        kind: createKind,
        title,
        content: createContent,
      });
      await refreshSilent();
      setSelectedFileId(created.id);
      setCreateOpen(false);
      notifySuccess(`已创建文件「${created.title}」`);
    } catch (err) {
      notifyApiError(err, "创建文件失败");
    } finally {
      setCreating(false);
    }
  };

  const handleRestoreVersion = async (ver: ConversationFileVersionResponse) => {
    if (!activeFile || !ver.content) {
      notifyError("仅支持文本版本内容回滚");
      return;
    }
    await handleSave(
      activeFile,
      activeFile.currentVersion,
      activeFile.title,
      ver.content
    );
    setHistoryOpen(false);
    setInspectVersion(null);
  };

  const resolvedMediaAssetId = activeFile
    ? activeVersion?.assetId ??
      fileAssetIdMap.get(activeFile.id) ??
      manualAssetIds[activeFile.id] ??
      null
    : null;

  const handleResolveManualAsset = async () => {
    const assetId = assetInputValue.trim();
    if (!activeFile || !assetId) {
      return;
    }
    try {
      const asset = await assetsApi.resolveInConversation(
        conversationId,
        assetId
      );
      setManualAssetIds((prev) => ({
        ...prev,
        [activeFile.id]: asset.id,
      }));
      setAssetInputValue("");
    } catch (err) {
      notifyApiError(err, "该资产不属于当前会话或不存在");
    }
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      {/* Top filter & search bar */}
      <div className="flex flex-wrap items-center gap-2 border-b px-4 py-2.5">
        <div className="relative flex min-w-44 flex-1 items-center">
          <SearchIcon className="pointer-events-none absolute left-2.5 size-3.5 text-muted-foreground" />
          <Input
            value={searchInput}
            onChange={(e) => {
              const next = e.target.value;
              setSearchInput(next);
              if (!next.trim()) {
                setAppliedQuery("");
              }
            }}
            onKeyDown={(e) => {
              if (e.key === "Enter") {
                setAppliedQuery(searchInput);
              }
            }}
            placeholder="检索文件标题或内容，Enter 搜索..."
            className="h-8 pl-8 text-xs"
          />
        </div>

        <Select
          value={kindFilter}
          items={{ ALL: "全部类型", ...FILE_KIND_LABEL }}
          onValueChange={(val) => {
            if (val) {
              setKindFilter(val as ConversationFileKind | "ALL");
            }
          }}
        >
          <SelectTrigger size="sm" className="w-28">
            <SelectValue />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部类型</SelectItem>
            {(Object.keys(FILE_KIND_LABEL) as ConversationFileKind[]).map(
              (kind) => (
                <SelectItem key={kind} value={kind}>
                  {FILE_KIND_LABEL[kind]}
                </SelectItem>
              )
            )}
          </SelectContent>
        </Select>

        <Button
          type="button"
          size="xs"
          onClick={() => {
            setCreateKind("SCRIPT");
            setCreateTitle("分场剧本.md");
            setCreateContent(DEFAULT_SCRIPT_TEMPLATE);
            setCreateOpen(true);
          }}
        >
          <FilePlus2Icon />
          <span>新建文件</span>
        </Button>
      </div>

      {/* Split List + Viewer */}
      <div className="flex min-h-0 flex-1 flex-col lg:flex-row">
        {/* File List */}
        <div className="max-h-48 shrink-0 overflow-y-auto border-b p-2 lg:max-h-none lg:w-56 lg:border-r lg:border-b-0">
          {searching ? (
            <p className="p-2 text-xs text-muted-foreground">搜索中...</p>
          ) : visibleFiles.length === 0 ? (
            <p className="p-3 text-center text-xs text-muted-foreground">
              暂无匹配文件
            </p>
          ) : (
            <div className="space-y-1">
              {visibleFiles.map((file) => {
                const selected = activeFile?.id === file.id;
                const hasUnsaved = Boolean(draftsByFile[file.id]);
                return (
                  <Tooltip key={file.id}>
                    <TooltipTrigger
                      render={
                        <button
                          type="button"
                          onClick={() => handleSelectFile(file.id)}
                          title={file.title}
                          className={cn(
                            "flex w-full flex-col gap-1 rounded-control-sm px-2.5 py-2 text-left transition-colors motion-reduce:transition-none",
                            selected
                              ? "bg-accent text-accent-foreground"
                              : "hover:bg-muted/50"
                          )}
                        />
                      }
                    >
                      <div className="flex w-full items-center justify-between gap-1.5">
                        <span className="truncate text-xs font-medium">
                          {file.title}
                        </span>
                        {hasUnsaved ? (
                          <span
                            className="size-1.5 shrink-0 rounded-full bg-primary"
                            aria-label="未保存修改"
                          />
                        ) : null}
                      </div>
                      <div className="flex items-center gap-1.5 text-[10px] text-muted-foreground">
                        <span>{FILE_KIND_LABEL[file.kind]}</span>
                      </div>
                    </TooltipTrigger>
                    <TooltipContent side="right">
                      <span>{file.title}</span>
                    </TooltipContent>
                  </Tooltip>
                );
              })}
            </div>
          )}
        </div>

        {/* File Viewer / Editor */}
        <div className="flex min-h-0 flex-1 flex-col overflow-y-auto p-4">
          {!activeFile ? (
            <div className="m-auto flex flex-col items-center gap-2 text-center text-muted-foreground">
              <FileTextIcon className="size-8" />
              <p className="text-xs">从左侧选择文件，或新建剧本文档</p>
            </div>
          ) : (
            <div className="space-y-4">
              {/* File header */}
              <div className="flex flex-wrap items-start justify-between gap-2 border-b pb-3">
                <div className="min-w-0 space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <h3 className="truncate text-sm font-semibold">
                      {activeFile.title}
                    </h3>
                    <Badge variant="outline">
                      {FILE_KIND_LABEL[activeFile.kind]}
                    </Badge>
                  </div>
                  <div className="flex flex-wrap items-center gap-2 text-[11px] text-muted-foreground tabular-nums">
                    <span>更新：{formatDateTime(activeFile.updatedAt)}</span>
                  </div>
                </div>

                <div className="flex flex-wrap items-center gap-1.5">
                  {activeFile.kind === "STORYBOARD" ? (
                    <Button
                      type="button"
                      variant="outline"
                      size="xs"
                      onClick={() => openCanvas("storyboard", activeFile.id)}
                    >
                      <FilmIcon />
                      <span>在分镜画布中打开</span>
                    </Button>
                  ) : null}

                  <Button
                    type="button"
                    variant="outline"
                    size="xs"
                    onClick={() => {
                      setInspectVersion(null);
                      setHistoryOpen(true);
                    }}
                  >
                    <HistoryIcon />
                    <span>版本历史</span>
                  </Button>

                  {isTextFile ? (
                    isEditing ? (
                      <>
                        <Button
                          type="button"
                          variant="ghost"
                          size="xs"
                          onClick={() => {
                            discardDraft(activeFile.id);
                            setEditing(activeFile.id, false);
                          }}
                        >
                          <EyeIcon />
                          <span>取消编辑</span>
                        </Button>
                        <Button
                          type="button"
                          size="xs"
                          disabled={saving || isEditInvalid}
                          onClick={() =>
                            void handleSave(
                              activeFile,
                              activeDraft?.baseVersion ??
                                activeFile.currentVersion,
                              displayTitle,
                              displayContent
                            )
                          }
                        >
                          <SaveIcon />
                          <span>{saving ? "保存中..." : "保存新版本"}</span>
                        </Button>
                      </>
                    ) : (
                      <Button
                        type="button"
                        variant="outline"
                        size="xs"
                        onClick={() => setEditing(activeFile.id, true)}
                      >
                        <Edit3Icon />
                        <span>编辑</span>
                      </Button>
                    )
                  ) : null}
                </div>
              </div>

              <ApiErrorAlert error={contentError} title="读取文件内容失败" />

              {isTextFile ? (
                loadingContent && !activeVersion ? (
                  <p className="text-xs text-muted-foreground">
                    正在读取版本正文...
                  </p>
                ) : isEditing ? (
                  <div className="space-y-3">
                    <div className="space-y-1">
                      <div className="flex items-center justify-between gap-2">
                        <Label className="text-xs text-muted-foreground">
                          文件标题
                        </Label>
                        <span
                          className={cn(
                            "text-[11px] tabular-nums",
                            displayTitle.trim().length > FILE_TITLE_MAX_LENGTH
                              ? "text-destructive"
                              : "text-muted-foreground"
                          )}
                        >
                          {displayTitle.trim().length}/{FILE_TITLE_MAX_LENGTH}
                        </span>
                      </div>
                      <Input
                        value={displayTitle}
                        onChange={(e) => updateDraft({ title: e.target.value })}
                      />
                    </div>
                    <div className="space-y-1">
                      <div className="flex items-center justify-between gap-2">
                        <Label className="text-xs text-muted-foreground">
                          正文内容
                        </Label>
                        <span
                          className={cn(
                            "text-[11px] tabular-nums",
                            editContentBytes > FILE_CONTENT_MAX_BYTES
                              ? "text-destructive"
                              : "text-muted-foreground"
                          )}
                        >
                          {formatBytes(editContentBytes)} / 1.0 MB
                        </span>
                      </div>
                      <Textarea
                        rows={18}
                        value={displayContent}
                        onChange={(e) =>
                          updateDraft({ content: e.target.value })
                        }
                        className="font-mono text-xs leading-relaxed"
                      />
                    </div>
                  </div>
                ) : activeFile.kind === "STORYBOARD" ||
                  activeFile.mimeType.includes("json") ? (
                  <pre className="overflow-x-auto rounded-surface-sm border bg-muted/30 p-3 font-mono text-xs leading-relaxed">
                    {activeVersion?.content ?? ""}
                  </pre>
                ) : (
                  <div className="rounded-surface-md border bg-card p-4">
                    <MarkdownView content={activeVersion?.content ?? ""} />
                  </div>
                )
              ) : (
                <div className="space-y-4">
                  {resolvedMediaAssetId ? (
                    <AssetMediaPreview
                      assetId={resolvedMediaAssetId}
                      kind={activeFile.kind === "VIDEO" ? "VIDEO" : "IMAGE"}
                      alt={activeFile.title}
                      className="aspect-video max-h-96 w-full"
                    />
                  ) : (
                    <Alert>
                      <FileTextIcon />
                      <AlertTitle>暂无可直接预览的媒体文件</AlertTitle>
                      <AlertDescription className="space-y-2.5 text-xs">
                        <p>
                          可输入同会话媒体编号以关联加载：
                        </p>
                        <div className="flex gap-2">
                          <Input
                            value={assetInputValue}
                            onChange={(e) => setAssetInputValue(e.target.value)}
                            placeholder="输入媒体编号..."
                            className="h-8 text-xs"
                          />
                          <Button
                            type="button"
                            variant="outline"
                            size="sm"
                            disabled={!assetInputValue.trim()}
                            onClick={() => void handleResolveManualAsset()}
                          >
                            解析预览
                          </Button>
                        </div>
                      </AlertDescription>
                    </Alert>
                  )}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Create File Dialog */}
      <Dialog open={createOpen} onOpenChange={setCreateOpen}>
        <DialogContent className="sm:max-w-xl">
          <DialogHeader>
            <DialogTitle>新建会话文件</DialogTitle>
            <DialogDescription>
              创建剧本（Markdown）、提示词或分镜文件，作为后续多版本协作依据。
            </DialogDescription>
          </DialogHeader>

          <div className="space-y-3 py-1">
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <Label>文件类型</Label>
                <Select
                  value={createKind}
                  items={FILE_KIND_LABEL}
                  onValueChange={(val) => {
                    if (!val) {
                      return;
                    }
                    const nextKind = val as ConversationFileKind;
                    setCreateKind(nextKind);
                    setCreateTitle(
                      nextKind === "SCRIPT"
                        ? "分场剧本.md"
                        : nextKind === "STORYBOARD"
                          ? "主分镜方案.json"
                          : nextKind === "PROMPT"
                            ? "提示词设定.md"
                            : "创作备注.txt"
                    );
                    setCreateContent(getTemplateForKind(nextKind));
                  }}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    {TEXT_FILE_KINDS.map((kind) => (
                      <SelectItem key={kind} value={kind}>
                        {FILE_KIND_LABEL[kind]}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
              <div className="space-y-1.5">
                <div className="flex items-center justify-between gap-2">
                  <Label htmlFor="create-file-title">文件标题</Label>
                  <span
                    className={cn(
                      "text-[11px] tabular-nums",
                      createTitle.trim().length > FILE_TITLE_MAX_LENGTH
                        ? "text-destructive"
                        : "text-muted-foreground"
                    )}
                  >
                    {createTitle.trim().length}/{FILE_TITLE_MAX_LENGTH}
                  </span>
                </div>
                <Input
                  id="create-file-title"
                  value={createTitle}
                  onChange={(e) => setCreateTitle(e.target.value)}
                />
              </div>
            </div>

            <div className="space-y-1.5">
              <div className="flex items-center justify-between gap-2">
                <Label htmlFor="create-file-content">文件内容</Label>
                <span
                  className={cn(
                    "text-[11px] tabular-nums",
                    createContentBytes > FILE_CONTENT_MAX_BYTES
                      ? "text-destructive"
                      : "text-muted-foreground"
                  )}
                >
                  {formatBytes(createContentBytes)} / 1.0 MB
                </span>
              </div>
              <Textarea
                id="create-file-content"
                rows={12}
                value={createContent}
                onChange={(e) => setCreateContent(e.target.value)}
                className="font-mono text-xs"
              />
            </div>
          </div>

          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setCreateOpen(false)}
            >
              取消
            </Button>
            <Button
              type="button"
              disabled={creating || isCreateInvalid}
              onClick={() => void handleCreateFile()}
            >
              {creating ? "创建中..." : "确认创建"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Version History Sheet */}
      <Sheet open={historyOpen} onOpenChange={setHistoryOpen}>
        <SheetContent className="flex w-full flex-col sm:max-w-xl">
          <SheetHeader>
            <SheetTitle>版本历史 · {activeFile?.title}</SheetTitle>
            <SheetDescription>
              每次助手修改、人工保存或任务回写都会生成不可变历史版本，可查看或恢复为最新版本。
            </SheetDescription>
          </SheetHeader>

          <div className="flex-1 divide-y overflow-y-auto px-4">
            {loadingHistory && !versionHistory ? (
              <p className="text-xs text-muted-foreground">加载版本列表...</p>
            ) : (versionHistory ?? []).length === 0 ? (
              <p className="text-xs text-muted-foreground">暂无版本记录</p>
            ) : (
              (versionHistory ?? []).map((ver) => {
                const inspected = inspectVersion?.version === ver.version;
                return (
                  <div
                    key={ver.id}
                    className="space-y-2 py-3"
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="flex items-center gap-2">
                        <Badge variant="outline" className="tabular-nums">
                          v{ver.version}
                        </Badge>
                        <Badge variant="secondary">
                          {FILE_SOURCE_LABEL[ver.source]}
                        </Badge>
                        {ver.version === activeFile?.currentVersion ? (
                          <Badge>当前版本</Badge>
                        ) : null}
                      </div>
                      <span className="text-[11px] text-muted-foreground tabular-nums">
                        {formatDateTime(ver.createdAt)} · {formatBytes(ver.sizeBytes)}
                      </span>
                    </div>

                    <div className="flex flex-wrap gap-1.5">
                      {ver.content !== null ? (
                        <Button
                          type="button"
                          variant="outline"
                          size="xs"
                          onClick={() =>
                            setInspectVersion(inspected ? null : ver)
                          }
                        >
                          <EyeIcon />
                          <span>{inspected ? "收起内容" : "查看内容"}</span>
                        </Button>
                      ) : null}

                      {ver.content !== null &&
                      ver.version !== activeFile?.currentVersion ? (
                        <Button
                          type="button"
                          variant="secondary"
                          size="xs"
                          disabled={saving}
                          onClick={() => void handleRestoreVersion(ver)}
                        >
                          <RotateCcwIcon />
                          <span>回滚并保存为最新版本</span>
                        </Button>
                      ) : null}
                    </div>

                    {inspected && ver.content !== null ? (
                      <pre className="max-h-64 overflow-auto rounded-surface-sm bg-muted/40 p-2.5 font-mono text-[11px] leading-relaxed">
                        {ver.content}
                      </pre>
                    ) : null}
                  </div>
                );
              })
            )}
          </div>
        </SheetContent>
      </Sheet>

      {/* 409 Conflict Dialog */}
      <Dialog
        open={Boolean(conflict)}
        onOpenChange={(open) => {
          if (!open) {
            setConflict(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-2xl">
          <DialogHeader>
            <DialogTitle>文件内容已更新</DialogTitle>
            <DialogDescription>
              云端内容已被他人或助手更新，请选择加载最新版本或基于最新版本重新保存。
            </DialogDescription>
          </DialogHeader>
          {conflict ? (
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="space-y-1">
                <Label className="text-xs">
                  云端最新内容
                </Label>
                <Textarea
                  readOnly
                  rows={12}
                  value={conflict.serverVersion.content ?? ""}
                  className="font-mono text-xs"
                />
              </div>
              <div className="space-y-1">
                <Label className="text-xs">当前编辑内容</Label>
                <Textarea
                  readOnly
                  rows={12}
                  value={conflict.localContent}
                  className="font-mono text-xs"
                />
              </div>
            </div>
          ) : null}
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => {
                if (conflict) {
                  discardDraft(conflict.file.id);
                  setEditing(conflict.file.id, false);
                  setConflict(null);
                  void refreshSilent();
                }
              }}
            >
              采用最新版本
            </Button>
              <Button
                type="button"
                disabled={saving}
                onClick={async () => {
                  if (!conflict) {
                    return;
                  }
                  const target = conflict;
                  setConflict(null);
                  await handleSave(
                    target.file,
                    target.file.currentVersion,
                    target.localTitle,
                    target.localContent
                  );
                }}
              >
                基于最新版本覆盖提交
              </Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>

      {/* Unsaved Draft Switch Guard */}
      <Dialog
        open={Boolean(pendingSwitchFileId)}
        onOpenChange={(open) => {
          if (!open) {
            setPendingSwitchFileId(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>有未保存的修改</DialogTitle>
            <DialogDescription>
              当前文件「{activeFile?.title}」存在未保存的编辑内容。是否放弃更改并切换到另一个文件？
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              onClick={() => setPendingSwitchFileId(null)}
            >
              继续编辑
            </Button>
            <Button
              type="button"
              variant="destructive"
              onClick={() => {
                if (activeFile) {
                  discardDraft(activeFile.id);
                  setEditing(activeFile.id, false);
                }
                if (pendingSwitchFileId) {
                  setSelectedFileId(pendingSwitchFileId);
                }
                setPendingSwitchFileId(null);
              }}
            >
              放弃修改并切换
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
