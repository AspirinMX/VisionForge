"use client";

import { useState } from "react";
import {
  AlertCircleIcon,
  AtomIcon,
  BookOpenIcon,
  ChevronDownIcon,
  ClapperboardIcon,
  ClockIcon,
  FileEditIcon,
  FileSearchIcon,
  FileTextIcon,
  FilmIcon,
  ImageIcon,
  Loader2Icon,
  VideoIcon,
} from "lucide-react";

import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import type {
  AgentRunProcessResponse,
  ToolCallProcessResponse,
  UUID,
} from "@/lib/api/types";
import { cn } from "@/lib/utils";

interface AgentProcessTimelineProps {
  process: AgentRunProcessResponse;
  isStreaming?: boolean;
  defaultOpen?: boolean;
  onOpenFile?: (fileId: UUID) => void;
  onOpenStoryboard?: (fileId: UUID) => void;
  onOpenTasks?: () => void;
}

function formatDurationLabel(durationMs: number | null): string | null {
  if (durationMs === null || durationMs < 0) {
    return null;
  }
  const totalSeconds = Math.round(durationMs / 1000);
  if (totalSeconds < 1) {
    return "思考了 <1s";
  }
  if (totalSeconds < 60) {
    return `思考了 ${totalSeconds}s`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return seconds > 0
    ? `思考了 ${minutes}m ${seconds}s`
    : `思考了 ${minutes}m`;
}

function renderToolIcon(tool: ToolCallProcessResponse) {
  if (tool.status === "RUNNING" || tool.status === "WAITING") {
    return <Loader2Icon className="size-3.5 shrink-0 animate-spin text-muted-foreground" />;
  }
  if (tool.status === "FAILED") {
    return <AlertCircleIcon className="size-3.5 shrink-0 text-destructive" />;
  }

  const cls = "size-3.5 shrink-0 text-muted-foreground";
  switch (tool.toolName) {
    case "load_skill":
      return <BookOpenIcon className={cls} />;
    case "list_files":
    case "search_files":
    case "list_file_versions":
      return <FileSearchIcon className={cls} />;
    case "read_file":
    case "inspect_asset":
      return <FileTextIcon className={cls} />;
    case "create_file":
    case "update_file":
      return <FileEditIcon className={cls} />;
    case "save_storyboard":
    case "validate_storyboard":
      return <ClapperboardIcon className={cls} />;
    case "generate_image":
      return <ImageIcon className={cls} />;
    case "generate_video":
      return <VideoIcon className={cls} />;
    case "await_generation":
    case "get_generation":
    case "cancel_generation":
      return <ClockIcon className={cls} />;
    case "compose_video":
    case "get_composition":
      return <FilmIcon className={cls} />;
    default:
      return <AtomIcon className={cls} />;
  }
}

function isInteractiveTool(tool: ToolCallProcessResponse): boolean {
  return Boolean(tool.fileId || tool.taskId);
}

export function AgentProcessTimeline({
  process,
  isStreaming = false,
  defaultOpen = false,
  onOpenFile,
  onOpenStoryboard,
  onOpenTasks,
}: AgentProcessTimelineProps) {
  const [open, setOpen] = useState(defaultOpen || isStreaming);

  const visibleTurns = process.turns.filter(
    (turn) =>
      Boolean(turn.reasoning?.trim()) ||
      Boolean(turn.stepText?.trim()) ||
      turn.toolCalls.length > 0
  );

  if (visibleTurns.length === 0) {
    return null;
  }

  const durationText = isStreaming
    ? "思考过程"
    : formatDurationLabel(process.durationMs) ?? "思考过程";

  const handleToolClick = (tool: ToolCallProcessResponse) => {
    if (
      tool.fileId &&
      (tool.toolName === "save_storyboard" ||
        tool.toolName === "validate_storyboard" ||
        tool.toolName === "compose_video")
    ) {
      onOpenStoryboard?.(tool.fileId);
      return;
    }
    if (tool.fileId) {
      onOpenFile?.(tool.fileId);
      return;
    }
    if (tool.taskId) {
      onOpenTasks?.();
    }
  };

  return (
    <Collapsible open={open} onOpenChange={setOpen} className="mb-3">
      <CollapsibleTrigger
        render={
          <button
            type="button"
            className="inline-flex items-center gap-1.5 py-0.5 text-xs text-muted-foreground transition-colors hover:text-foreground"
          />
        }
      >
        <AtomIcon
          className={cn(
            "size-3.5 shrink-0",
            isStreaming && "animate-pulse text-foreground"
          )}
        />
        <span>{durationText}</span>
        <ChevronDownIcon
          className={cn(
            "size-3.5 transition-transform duration-150",
            !open && "-rotate-90"
          )}
        />
      </CollapsibleTrigger>

      <CollapsibleContent className="mt-2.5 space-y-3">
        {visibleTurns.map((turn) => (
          <div key={turn.turnNo} className="space-y-2.5">
            {turn.reasoning?.trim() ? (
              <div className="border-l-2 border-border pl-3 text-[13px] leading-relaxed whitespace-pre-wrap text-muted-foreground/90">
                {turn.reasoning.trim()}
              </div>
            ) : null}

            {turn.stepText?.trim() ? (
              <p className="text-[13px] leading-relaxed text-muted-foreground">
                {turn.stepText.trim()}
              </p>
            ) : null}

            {turn.toolCalls.length > 0 ? (
              <div className="space-y-1.5">
                {turn.toolCalls.map((tool) => {
                  const clickable = isInteractiveTool(tool);
                  const chipBody = (
                    <>
                      {renderToolIcon(tool)}
                      <span
                        className={cn(
                          "font-medium",
                          tool.status === "FAILED"
                            ? "text-destructive"
                            : "text-foreground/90"
                        )}
                      >
                        {tool.action}
                      </span>
                      {tool.target ? (
                        <span className="truncate font-mono text-[12px] text-muted-foreground">
                          {tool.target}
                        </span>
                      ) : null}
                      {tool.status === "FAILED" && tool.errorMessage ? (
                        <span className="truncate text-[11px] text-destructive/90">
                          · {tool.errorMessage}
                        </span>
                      ) : null}
                    </>
                  );

                  if (clickable) {
                    return (
                      <button
                        key={tool.callId}
                        type="button"
                        onClick={() => handleToolClick(tool)}
                        className={cn(
                          "flex w-full items-center gap-2 rounded-lg bg-muted/70 px-3 py-2 text-left text-xs transition-colors hover:bg-muted",
                          tool.status === "FAILED" && "bg-destructive/8 hover:bg-destructive/12"
                        )}
                      >
                        {chipBody}
                      </button>
                    );
                  }

                  return (
                    <div
                      key={tool.callId}
                      className={cn(
                        "flex w-full items-center gap-2 rounded-lg bg-muted/70 px-3 py-2 text-xs",
                        tool.status === "FAILED" && "bg-destructive/8"
                      )}
                    >
                      {chipBody}
                    </div>
                  );
                })}
              </div>
            ) : null}
          </div>
        ))}
      </CollapsibleContent>
    </Collapsible>
  );
}
