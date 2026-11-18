"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

import { useAuth } from "@/components/providers/auth-provider";
import { useConversations } from "@/components/providers/conversations-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import {
  assetsApi,
  compositionApi,
  conversationsApi,
  filesApi,
  generationApi,
} from "@/lib/api/endpoints";
import type { ApiError } from "@/lib/api/errors";
import type {
  AgentRunResponse,
  AssetKind,
  AssetResponse,
  CompositionTaskResponse,
  ConversationFileResponse,
  ConversationResponse,
  GenerationInputRequest,
  GenerationStatus,
  GenerationTaskResponse,
  GenerationType,
  MessageResponse,
  UUID,
} from "@/lib/api/types";
import { placeholderStore } from "@/lib/storage/placeholder-store";

export type StudioCanvasTab = "storyboard" | "files" | "assets" | "tasks";

export interface ConversationAssetItem {
  assetId: UUID;
  kind: AssetKind;
  source: "UPLOAD" | "GENERATED" | "COMPOSED" | "IMPORTED";
  label: string;
  mimeType?: string;
  sizeBytes?: number;
  createdAt: string;
  fileId?: UUID | null;
  taskId?: UUID | null;
}

export interface QuickGenDraft {
  nonce: number;
  type: GenerationType;
  prompt: string;
  parameters?: Record<string, unknown>;
  inputs?: GenerationInputRequest[];
  sourceTaskId?: UUID | null;
  shotId?: string | null;
  storyboardFileId?: UUID | null;
  storyboardVersion?: number | null;
}

export interface ChatComposerPatch {
  nonce: number;
  promptAppend?: string;
  assetIds?: UUID[];
}

interface StudioCoreSnapshot {
  conversation: ConversationResponse;
  messages: MessageResponse[];
  files: ConversationFileResponse[];
  generationTasks: GenerationTaskResponse[];
  compositionTasks: CompositionTaskResponse[];
  activeRun: AgentRunResponse | null;
}

interface StudioContextValue {
  conversationId: UUID;
  userId: UUID | null;
  conversation: ConversationResponse | null;
  messages: MessageResponse[];
  files: ConversationFileResponse[];
  generationTasks: GenerationTaskResponse[];
  compositionTasks: CompositionTaskResponse[];
  activeRun: AgentRunResponse | null;
  localAssets: ConversationAssetItem[];
  fileAssetIdMap: Map<UUID, UUID>;
  activeTaskCount: number;
  initialLoading: boolean;
  refreshing: boolean;
  error: ApiError | null;

  canvasOpen: boolean;
  setCanvasOpen: (open: boolean) => void;
  canvasFullscreen: boolean;
  setCanvasFullscreen: (fullscreen: boolean) => void;
  activeTab: StudioCanvasTab;
  setActiveTab: (tab: StudioCanvasTab) => void;
  selectedFileId: UUID | null;
  setSelectedFileId: (fileId: UUID | null) => void;
  openCanvas: (tab: StudioCanvasTab, fileId?: UUID | null) => void;

  quickGenDraft: QuickGenDraft | null;
  openQuickGenerate: (draft: Omit<QuickGenDraft, "nonce">) => void;
  clearQuickGenDraft: () => void;

  sendToChatComposer: (patch: Omit<ChatComposerPatch, "nonce">) => void;
  subscribeComposerPatch: (
    handler: (patch: ChatComposerPatch) => void
  ) => () => void;

  refreshAll: () => void;
  refreshSilent: () => Promise<StudioCoreSnapshot | null>;
  registerLocalAsset: (asset: AssetResponse) => void;
  importAssetById: (assetId: UUID) => Promise<AssetResponse>;
  trackCompositionTask: (taskId: UUID) => Promise<CompositionTaskResponse>;
  setTrackedActiveRun: (runId: UUID | null) => void;
}

const StudioContext = createContext<StudioContextValue | null>(null);

const ACTIVE_GENERATION_STATUSES = new Set<GenerationStatus>([
  "PENDING",
  "SUBMITTING",
  "RUNNING",
  "ARCHIVING",
  "PROCESSING",
]);

export function isGenerationStatusActive(status: GenerationStatus): boolean {
  return ACTIVE_GENERATION_STATUSES.has(status);
}

const ACTIVE_COMPOSITION_STATUSES = new Set([
  "PENDING",
  "VALIDATING",
  "COMPOSING",
  "UPLOADING",
  "PUBLISHING",
]);

function sortByCreatedDesc<T extends { createdAt: string }>(items: T[]): T[] {
  return [...items].sort(
    (a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt)
  );
}

async function fetchStudioSnapshot(
  conversationId: UUID,
  userId: UUID
): Promise<StudioCoreSnapshot> {
  const cachedCompIds = placeholderStore.getCompositionTaskIds(
    userId,
    conversationId
  );
  const cachedRun = placeholderStore.getActiveRun(userId, conversationId);

  const [conversation, messages, files, generationTasks, compResults, runResult] =
    await Promise.all([
      conversationsApi.get(conversationId),
      conversationsApi.messages(conversationId),
      filesApi.list(conversationId, { limit: 100 }),
      generationApi.list({ conversationId, limit: 100 }),
      Promise.allSettled(cachedCompIds.map((id) => compositionApi.get(id))),
      cachedRun
        ? conversationsApi.getAgentRun(cachedRun.runId).catch(() => null)
        : Promise.resolve(null),
    ]);

  const compositionTasks: CompositionTaskResponse[] = [];
  for (const res of compResults) {
    if (
      res.status === "fulfilled" &&
      res.value.conversationId === conversationId
    ) {
      compositionTasks.push(res.value);
    }
  }

  let activeRun: AgentRunResponse | null = null;
  if (runResult) {
    if (runResult.status === "PENDING" || runResult.status === "RUNNING") {
      activeRun = runResult;
    } else {
      placeholderStore.setActiveRun(userId, conversationId, null);
    }
  } else if (cachedRun) {
    placeholderStore.setActiveRun(userId, conversationId, null);
  }

  return {
    conversation,
    messages: [...messages].sort((a, b) => a.sequenceNo - b.sequenceNo),
    files,
    generationTasks: sortByCreatedDesc(generationTasks),
    compositionTasks: sortByCreatedDesc(compositionTasks),
    activeRun,
  };
}

interface StudioProviderProps {
  conversationId: UUID;
  initialTab?: StudioCanvasTab | null;
  initialFileId?: UUID | null;
  children: ReactNode;
}

export function StudioProvider({
  conversationId,
  initialTab = null,
  initialFileId = null,
  children,
}: StudioProviderProps) {
  const { user } = useAuth();
  const { upsertConversation } = useConversations();
  const userId = user?.id ?? null;

  const [canvasOpen, setCanvasOpen] = useState<boolean>(Boolean(initialTab));
  const [canvasFullscreen, setCanvasFullscreen] = useState(false);
  const [activeTab, setActiveTab] = useState<StudioCanvasTab>(
    initialTab ?? "storyboard"
  );
  const [selectedFileId, setSelectedFileId] = useState<UUID | null>(
    initialFileId ?? null
  );
  const [localStorageTick, setLocalStorageTick] = useState(0);
  const [quickGenDraft, setQuickGenDraft] = useState<QuickGenDraft | null>(null);
  const composerListenersRef = useRef<Set<(patch: ChatComposerPatch) => void>>(
    new Set()
  );
  const pendingComposerPatchesRef = useRef<ChatComposerPatch[]>([]);
  const draftSeqRef = useRef(0);
  const lastSyncedConvUpdatedAtRef = useRef<string | null>(null);

  const { data, error, loading, reload, mutate } = useAsyncData(
    () => {
      if (!userId) {
        return Promise.reject(new Error("Missing authenticated user"));
      }
      return fetchStudioSnapshot(conversationId, userId);
    },
    userId ? `studio:${userId}:${conversationId}` : `studio:anon:${conversationId}`,
    {
      enabled: Boolean(userId && conversationId),
      errorMessage: "加载会话工作区失败",
    }
  );

  const conversation = data?.conversation ?? null;

  useEffect(() => {
    if (!conversation) {
      return;
    }
    const stamp = `${conversation.id}:${conversation.updatedAt}:${conversation.lastMessageAt ?? ""}`;
    if (lastSyncedConvUpdatedAtRef.current !== stamp) {
      lastSyncedConvUpdatedAtRef.current = stamp;
      upsertConversation(conversation);
    }
  }, [conversation, upsertConversation]);

  const refreshSilent = useCallback(async (): Promise<StudioCoreSnapshot | null> => {
    if (!userId || !conversationId) {
      return null;
    }
    try {
      const fresh = await fetchStudioSnapshot(conversationId, userId);
      mutate(() => fresh);
      return fresh;
    } catch {
      // Keep existing UI state during transient background poll errors.
      return null;
    }
  }, [conversationId, mutate, userId]);

  const generationTasks = useMemo(
    () => data?.generationTasks ?? [],
    [data?.generationTasks]
  );
  const compositionTasks = useMemo(
    () => data?.compositionTasks ?? [],
    [data?.compositionTasks]
  );
  const activeRun = data?.activeRun ?? null;

  const activeTaskCount = useMemo(() => {
    const genCount = generationTasks.filter((t) =>
      isGenerationStatusActive(t.status)
    ).length;
    const compCount = compositionTasks.filter((t) =>
      ACTIVE_COMPOSITION_STATUSES.has(t.status)
    ).length;
    return genCount + compCount + (activeRun ? 1 : 0);
  }, [activeRun, compositionTasks, generationTasks]);

  useEffect(() => {
    if (activeTaskCount === 0 || !userId) {
      return;
    }
    const timer = window.setInterval(() => {
      void refreshSilent();
    }, 3500);
    return () => {
      window.clearInterval(timer);
    };
  }, [activeTaskCount, refreshSilent, userId]);

  const openCanvas = useCallback(
    (tab: StudioCanvasTab, fileId?: UUID | null) => {
      setActiveTab(tab);
      if (fileId !== undefined) {
        setSelectedFileId(fileId);
      }
      setCanvasOpen(true);
    },
    []
  );

  const openQuickGenerate = useCallback(
    (draft: Omit<QuickGenDraft, "nonce">) => {
      draftSeqRef.current += 1;
      setQuickGenDraft({ ...draft, nonce: draftSeqRef.current });
      setActiveTab("assets");
      setCanvasOpen(true);
    },
    []
  );

  const clearQuickGenDraft = useCallback(() => {
    setQuickGenDraft(null);
  }, []);

  const sendToChatComposer = useCallback(
    (patch: Omit<ChatComposerPatch, "nonce">) => {
      draftSeqRef.current += 1;
      const fullPatch: ChatComposerPatch = {
        ...patch,
        nonce: draftSeqRef.current,
      };
      if (composerListenersRef.current.size > 0) {
        for (const listener of composerListenersRef.current) {
          listener(fullPatch);
        }
      } else {
        pendingComposerPatchesRef.current.push(fullPatch);
      }
      if (canvasFullscreen) {
        setCanvasFullscreen(false);
      }
    },
    [canvasFullscreen]
  );

  const subscribeComposerPatch = useCallback(
    (handler: (patch: ChatComposerPatch) => void) => {
      composerListenersRef.current.add(handler);
      if (pendingComposerPatchesRef.current.length > 0) {
        const buffered = pendingComposerPatchesRef.current.splice(0);
        queueMicrotask(() => {
          for (const item of buffered) {
            handler(item);
          }
        });
      }
      return () => {
        composerListenersRef.current.delete(handler);
      };
    },
    []
  );

  const registerLocalAsset = useCallback(
    (asset: AssetResponse) => {
      if (!userId) {
        return;
      }
      placeholderStore.upsertAsset(userId, conversationId, asset);
      setLocalStorageTick((n) => n + 1);
    },
    [conversationId, userId]
  );

  const importAssetById = useCallback(
    async (assetId: UUID): Promise<AssetResponse> => {
      const asset = await assetsApi.get(assetId);
      if (asset.conversationId !== conversationId) {
        throw new Error("该资产不属于当前会话，已按会话隔离规则拦截");
      }
      registerLocalAsset(asset);
      return asset;
    },
    [conversationId, registerLocalAsset]
  );

  const trackCompositionTask = useCallback(
    async (taskId: UUID): Promise<CompositionTaskResponse> => {
      const task = await compositionApi.get(taskId);
      if (task.conversationId !== conversationId) {
        throw new Error("该合成任务不属于当前会话");
      }
      if (userId) {
        placeholderStore.addCompositionTaskId(userId, conversationId, task.taskId);
      }
      await refreshSilent();
      return task;
    },
    [conversationId, refreshSilent, userId]
  );

  const setTrackedActiveRun = useCallback(
    (runId: UUID | null) => {
      if (!userId) {
        return;
      }
      placeholderStore.setActiveRun(
        userId,
        conversationId,
        runId ? { runId, createdAt: new Date().toISOString() } : null
      );
      if (!runId) {
        mutate((prev) => (prev ? { ...prev, activeRun: null } : prev));
      }
    },
    [conversationId, mutate, userId]
  );

  const files = useMemo(() => data?.files ?? [], [data?.files]);

  const fileTitleMap = useMemo(() => {
    const map = new Map<UUID, string>();
    for (const file of files) {
      map.set(file.id, file.title);
    }
    return map;
  }, [files]);

  const fileAssetIdMap = useMemo(() => {
    const map = new Map<UUID, UUID>();
    for (const task of generationTasks) {
      for (const out of task.outputs) {
        if (out.fileId && out.assetId) {
          map.set(out.fileId, out.assetId);
        }
      }
    }
    for (const comp of compositionTasks) {
      if (comp.outputFileId && comp.outputAssetId) {
        map.set(comp.outputFileId, comp.outputAssetId);
      }
    }
    return map;
  }, [compositionTasks, generationTasks]);

  const localAssets = useMemo<ConversationAssetItem[]>(() => {
    void localStorageTick;
    const byId = new Map<UUID, ConversationAssetItem>();

    if (userId) {
      for (const asset of placeholderStore.getAssets(userId, conversationId)) {
        byId.set(asset.id, {
          assetId: asset.id,
          kind: asset.kind,
          source: "UPLOAD",
          label: `${asset.kind} 资产 · ${asset.id.slice(0, 8)}`,
          mimeType: asset.mimeType,
          sizeBytes: asset.sizeBytes,
          createdAt: asset.createdAt,
        });
      }
    }

    for (const task of generationTasks) {
      for (const out of task.outputs) {
        if (out.assetId && !byId.has(out.assetId)) {
          const title = out.fileId ? fileTitleMap.get(out.fileId) : undefined;
          byId.set(out.assetId, {
            assetId: out.assetId,
            kind: task.type === "VIDEO" ? "VIDEO" : "IMAGE",
            source: "GENERATED",
            label:
              title ??
              `${task.type === "VIDEO" ? "生成视频" : "生成图片"} · ${task.shotId ?? out.assetId.slice(0, 8)}`,
            createdAt: task.completedAt ?? task.createdAt,
            fileId: out.fileId,
            taskId: task.taskId,
          });
        }
      }
    }

    for (const comp of compositionTasks) {
      if (comp.outputAssetId && !byId.has(comp.outputAssetId)) {
        const title = comp.outputFileId
          ? fileTitleMap.get(comp.outputFileId)
          : undefined;
        byId.set(comp.outputAssetId, {
          assetId: comp.outputAssetId,
          kind: "VIDEO",
          source: "COMPOSED",
          label: title ?? `合成成片 v${comp.storyboardVersion}`,
          createdAt: comp.completedAt ?? comp.createdAt,
          fileId: comp.outputFileId,
          taskId: comp.taskId,
        });
      }
    }

    return sortByCreatedDesc(Array.from(byId.values()));
  }, [
    compositionTasks,
    conversationId,
    fileTitleMap,
    generationTasks,
    localStorageTick,
    userId,
  ]);

  const messages = useMemo(() => data?.messages ?? [], [data?.messages]);

  const value = useMemo<StudioContextValue>(
    () => ({
      conversationId,
      userId,
      conversation,
      messages,
      files,
      generationTasks,
      compositionTasks,
      activeRun,
      localAssets,
      fileAssetIdMap,
      activeTaskCount,
      initialLoading: loading && !data,
      refreshing: loading,
      error,

      canvasOpen,
      setCanvasOpen,
      canvasFullscreen,
      setCanvasFullscreen,
      activeTab,
      setActiveTab,
      selectedFileId,
      setSelectedFileId,
      openCanvas,

      quickGenDraft,
      openQuickGenerate,
      clearQuickGenDraft,

      sendToChatComposer,
      subscribeComposerPatch,

      refreshAll: reload,
      refreshSilent,
      registerLocalAsset,
      importAssetById,
      trackCompositionTask,
      setTrackedActiveRun,
    }),
    [
      activeRun,
      activeTab,
      activeTaskCount,
      canvasFullscreen,
      canvasOpen,
      clearQuickGenDraft,
      compositionTasks,
      conversation,
      conversationId,
      data,
      error,
      fileAssetIdMap,
      files,
      generationTasks,
      importAssetById,
      loading,
      localAssets,
      messages,
      openCanvas,
      openQuickGenerate,
      quickGenDraft,
      refreshSilent,
      registerLocalAsset,
      reload,
      selectedFileId,
      sendToChatComposer,
      setTrackedActiveRun,
      subscribeComposerPatch,
      trackCompositionTask,
      userId,
    ]
  );

  return <StudioContext value={value}>{children}</StudioContext>;
}

export function useStudio(): StudioContextValue {
  const context = useContext(StudioContext);
  if (!context) {
    throw new Error("useStudio must be used inside StudioProvider");
  }
  return context;
}
