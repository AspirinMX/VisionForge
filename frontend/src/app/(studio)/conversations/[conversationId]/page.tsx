"use client";

import { Suspense } from "react";
import { useParams, useSearchParams } from "next/navigation";

import type { StudioCanvasTab } from "@/components/studio/studio-context";
import { StudioWorkspace } from "@/components/studio/studio-workspace";
import { Skeleton } from "@/components/ui/skeleton";

const VALID_TABS = new Set<StudioCanvasTab>([
  "storyboard",
  "files",
  "assets",
  "tasks",
]);

function ConversationWorkspaceContent() {
  const params = useParams<{ conversationId: string }>();
  const searchParams = useSearchParams();

  const panelParam = searchParams.get("panel");
  const initialTab =
    panelParam && VALID_TABS.has(panelParam as StudioCanvasTab)
      ? (panelParam as StudioCanvasTab)
      : null;
  const initialFileId = searchParams.get("file");

  return (
    <StudioWorkspace
      key={params.conversationId}
      conversationId={params.conversationId}
      initialTab={initialTab}
      initialFileId={initialFileId}
    />
  );
}

function ConversationWorkspaceFallback() {
  return (
    <div className="flex flex-1 flex-col gap-4 p-4">
      <Skeleton className="h-12 w-full rounded-surface-sm" />
      <div className="mx-auto w-full max-w-3xl space-y-4 pt-6">
        <Skeleton className="ml-auto h-14 w-2/3 rounded-surface-md" />
        <Skeleton className="h-28 w-4/5 rounded-surface-md" />
      </div>
    </div>
  );
}

export default function ConversationDetailPage() {
  return (
    <Suspense fallback={<ConversationWorkspaceFallback />}>
      <ConversationWorkspaceContent />
    </Suspense>
  );
}
