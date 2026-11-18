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
import { useOptionalCredit } from "@/components/providers/credit-provider";
import { useAsyncData } from "@/hooks/use-async-data";
import {
  agentRunsApi,
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

export type StudioCanvasTab = "storyboard" | "files" | "assets" | "tasks";

export interface ConversationAssetItem {
  assetId: UUID;
  kind: AssetKind;
  source: "UPLOAD" | "GENERATED" | "COMPOSED" | "DERIVED" | "IMPORTED";
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
  assets: AssetResponse[];
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
  conversationId: UUID
): Promise<StudioCoreSnapshot> {
  const [
    conversation,
    messages,
    files,
    generationTasks,
    compositionTasks,
    activeRuns,
    assets,
  ] = await Promise.all([
    conversationsApi.get(conversationId),
    conversationsApi.messages(conversationId),
    filesApi.list(conversationId, { limit: 100 }),
    generationApi.list({ conversationId, limit: 100 }),
    compositionApi.list({ conversationId, limit: 50 }),
    agentRunsApi.list({
      conversationId,
      status: ["PENDING", "RUNNING"],
      limit: 1,
    }),
    assetsApi.list(conversationId, { limit: 100 }),
  ]);

  const activeRun =
    activeRuns.find(
      (run) => run.status === "PENDING" || run.status === "RUNNING"
    ) ?? null;

  return {
    conversation,
    messages: [...messages].sort((a, b) => a.sequenceNo - b.sequenceNo),
    files,
    generationTasks: sortByCreatedDesc(generationTasks),
    compositionTasks: sortByCreatedDesc(compositionTasks),
    assets: sortByCreatedDesc(assets),
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
  const creditState = useOptionalCredit();
  const userId = user?.id ?? null;

  const [canvasOpen, setCanvasOpen] = useState<boolean>(Boolean(initialTab));
  const [canvasFullscreen, setCanvasFullscreen] = useState(false);
  const [activeTab, setActiveTab] = useState<StudioCanvasTab>(
    initialTab ?? "storyboard"
  );
  const [selectedFileId, setSelectedFileId] = useState<UUID | null>(
    initialFileId ?? null
  );
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
      return fetchStudioSnapshot(conversationId);
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

  const refreshCreditSilent = creditState?.refreshCreditSilent;

  const refreshSilent = useCallback(async (): Promise<StudioCoreSnapshot | null> => {
    if (!userId || !conversationId) {
      return null;
    }
    try {
      const fresh = await fetchStudioSnapshot(conversationId);
      mutate(() => fresh);
      void refreshCreditSilent?.();
      return fresh;
    } catch {
      // Keep existing UI state during transient background poll errors.
      return null;
    }
  }, [conversationId, mutate, refreshCreditSilent, userId]);

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
      mutate((prev) => {
        if (!prev) {
          return prev;
        }
        return {
          ...prev,
          assets: [asset, ...prev.assets.filter((item) => item.id !== asset.id)],
        };
      });
    },
    [mutate]
  );

  const importAssetById = useCallback(
    async (assetId: UUID): Promise<AssetResponse> => {
      const asset = await assetsApi.resolveInConversation(
        conversationId,
        assetId
      );
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
      await refreshSilent();
      return task;
    },
    [conversationId, refreshSilent]
  );

  const setTrackedActiveRun = useCallback(
    (runId: UUID | null) => {
      if (runId) {
        void refreshSilent();
        return;
      }
      mutate((prev) => (prev ? { ...prev, activeRun: null } : prev));
    },
    [mutate, refreshSilent]
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
    const byId = new Map<UUID, ConversationAssetItem>();

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
              `${task.type === "VIDEO" ? "生成视频" : "生成图片"}${task.shotId ? ` · ${task.shotId}` : ""}`,
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

    for (const asset of data?.assets ?? []) {
      if (byId.has(asset.id)) {
        continue;
      }
      const mappedSource =
        asset.source === "UPLOAD"
          ? "UPLOAD"
          : asset.source === "DERIVED"
            ? "DERIVED"
            : "GENERATED";
      byId.set(asset.id, {
        assetId: asset.id,
        kind: asset.kind,
        source: mappedSource,
        label: `${asset.kind === "VIDEO" ? "视频" : "图片"}素材`,
        mimeType: asset.mimeType,
        sizeBytes: asset.sizeBytes,
        createdAt: asset.createdAt,
      });
    }

    return sortByCreatedDesc(Array.from(byId.values()));
  }, [
    compositionTasks,
    data?.assets,
    fileTitleMap,
    generationTasks,
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
