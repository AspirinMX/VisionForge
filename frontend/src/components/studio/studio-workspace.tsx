"use client";

import { useEffect, useSyncExternalStore } from "react";
import { notFound } from "next/navigation";
import {
  ActivityIcon,
  PanelRightCloseIcon,
  PanelRightOpenIcon,
  RefreshCwIcon,
} from "lucide-react";
import { usePanelRef } from "react-resizable-panels";

import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { PageHeader } from "@/components/layout/page-header";
import { ChatPane } from "@/components/studio/chat-pane";
import { StudioCanvas } from "@/components/studio/studio-canvas";
import {
  StudioProvider,
  useStudio,
  type StudioCanvasTab,
} from "@/components/studio/studio-context";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  ResizableHandle,
  ResizablePanel,
  ResizablePanelGroup,
} from "@/components/ui/resizable";
import { Sheet, SheetContent, SheetTitle } from "@/components/ui/sheet";
import type { UUID } from "@/lib/api/types";
import { cn } from "@/lib/utils";

const COMPACT_BREAKPOINT = 1024;

function subscribeCompact(callback: () => void): () => void {
  if (typeof window === "undefined") {
    return () => {};
  }
  const mql = window.matchMedia(`(max-width: ${COMPACT_BREAKPOINT - 1}px)`);
  mql.addEventListener("change", callback);
  return () => mql.removeEventListener("change", callback);
}

function getCompactSnapshot(): boolean {
  if (typeof window === "undefined") {
    return false;
  }
  return window.innerWidth < COMPACT_BREAKPOINT;
}

function getServerCompactSnapshot(): boolean {
  return false;
}

function WorkspaceShell({
  initialCanvasOpen,
}: {
  initialCanvasOpen: boolean;
}) {
  const {
    conversation,
    activeTaskCount,
    refreshing,
    error,
    canvasOpen,
    setCanvasOpen,
    canvasFullscreen,
    setCanvasFullscreen,
    openCanvas,
    refreshAll,
  } = useStudio();

  const isCompact = useSyncExternalStore(
    subscribeCompact,
    getCompactSnapshot,
    getServerCompactSnapshot
  );

  const chatPanelRef = usePanelRef();
  const canvasPanelRef = usePanelRef();

  useEffect(() => {
    if (isCompact) {
      return;
    }
    if (!canvasOpen) {
      canvasPanelRef.current?.collapse();
      chatPanelRef.current?.resize("100%");
    } else if (canvasFullscreen) {
      chatPanelRef.current?.collapse();
      canvasPanelRef.current?.resize("100%");
    } else {
      chatPanelRef.current?.resize("44%");
      canvasPanelRef.current?.resize("56%");
    }
  }, [canvasFullscreen, canvasOpen, canvasPanelRef, chatPanelRef, isCompact]);

  if (error?.isNotFound) {
    notFound();
  }

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title={conversation?.title ?? "加载会话中..."}
        badge={
          conversation ? (
            <Badge variant="outline" className="font-mono text-[11px]">
              {conversation.id.slice(0, 8)}
            </Badge>
          ) : null
        }
        actions={
          <div className="flex items-center gap-2">
            {activeTaskCount > 0 ? (
              <Button
                type="button"
                variant="secondary"
                size="xs"
                onClick={() => openCanvas("tasks")}
              >
                <ActivityIcon className="animate-pulse" />
                <span className="tabular-nums">
                  {activeTaskCount} 个进行中
                </span>
              </Button>
            ) : null}

            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={refreshAll}
              disabled={refreshing}
              aria-label="刷新会话数据"
            >
              <RefreshCwIcon className={refreshing ? "animate-spin" : ""} />
            </Button>

            <Button
              type="button"
              variant={canvasOpen ? "secondary" : "outline"}
              size="sm"
              onClick={() => {
                if (canvasOpen) {
                  setCanvasFullscreen(false);
                  setCanvasOpen(false);
                } else {
                  setCanvasOpen(true);
                }
              }}
            >
              {canvasOpen ? (
                <PanelRightCloseIcon />
              ) : (
                <PanelRightOpenIcon />
              )}
              <span>{canvasOpen ? "收起工作台" : "Studio Canvas"}</span>
            </Button>
          </div>
        }
      />

      {error ? (
        <div className="mx-auto w-full max-w-3xl px-4 pt-3">
          <ApiErrorAlert error={error} title="会话工作区加载遇到问题" />
        </div>
      ) : null}

      {isCompact ? (
        <div className="min-h-0 flex-1 overflow-hidden">
          <ChatPane />
          <Sheet open={canvasOpen} onOpenChange={setCanvasOpen}>
            <SheetContent
              side="right"
              showCloseButton={false}
              className="flex w-full flex-col gap-0 p-0 sm:max-w-2xl"
            >
              <SheetTitle className="sr-only">Studio Canvas 工作台</SheetTitle>
              {canvasOpen ? <StudioCanvas hideFullscreenToggle /> : null}
            </SheetContent>
          </Sheet>
        </div>
      ) : (
        <div className="min-h-0 flex-1 overflow-hidden">
          <ResizablePanelGroup orientation="horizontal" className="h-full w-full">
            <ResizablePanel
              id="chat-pane"
              panelRef={chatPanelRef}
              defaultSize={initialCanvasOpen ? "44%" : "100%"}
              minSize="28%"
              collapsible
              collapsedSize="0%"
              className={cn(
                "min-w-0",
                canvasOpen && canvasFullscreen && "hidden"
              )}
            >
              <ChatPane />
            </ResizablePanel>

            <ResizableHandle
              withHandle
              className={cn(
                (!canvasOpen || canvasFullscreen) && "hidden"
              )}
            />

            <ResizablePanel
              id="studio-canvas"
              panelRef={canvasPanelRef}
              defaultSize={initialCanvasOpen ? "56%" : "0%"}
              minSize="32%"
              collapsible
              collapsedSize="0%"
              className={cn("min-w-0", !canvasOpen && "hidden")}
            >
              {canvasOpen ? <StudioCanvas /> : null}
            </ResizablePanel>
          </ResizablePanelGroup>
        </div>
      )}
    </div>
  );
}

interface StudioWorkspaceProps {
  conversationId: UUID;
  initialTab?: StudioCanvasTab | null;
  initialFileId?: UUID | null;
}

export function StudioWorkspace({
  conversationId,
  initialTab = null,
  initialFileId = null,
}: StudioWorkspaceProps) {
  return (
    <StudioProvider
      conversationId={conversationId}
      initialTab={initialTab}
      initialFileId={initialFileId}
    >
      <WorkspaceShell initialCanvasOpen={Boolean(initialTab)} />
    </StudioProvider>
  );
}
