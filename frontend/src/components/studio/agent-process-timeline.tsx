"use client";

import { useState } from "react";
import { ShimmeringText } from "@animate-ui/react/primitives/texts/shimmering";
import { motion } from "motion/react";
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
import {
  formatWorkDurationLabel,
  hasVisibleTurnContent,
  isThinkingTurnActive,
} from "@/lib/studio/process-timeline";
import { cn } from "@/lib/utils";

interface AgentProcessTimelineProps {
  process: AgentRunProcessResponse;
  isStreaming?: boolean;
  defaultOpen?: boolean;
  hasAnswerText?: boolean;
  onOpenFile?: (fileId: UUID) => void;
  onOpenStoryboard?: (fileId: UUID) => void;
  onOpenTasks?: () => void;
}

function renderToolIcon(tool: ToolCallProcessResponse) {
  if (tool.status === "RUNNING" || tool.status === "WAITING") {
    return (
      <Loader2Icon className="size-3.5 shrink-0 animate-spin motion-reduce:animate-none text-muted-foreground" />
    );
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

function ThinkingSection({
  reasoning,
  isStreaming,
}: {
  reasoning: string;
  isStreaming: boolean;
}) {
  const [open, setOpen] = useState(true);

  return (
    <Collapsible open={open} onOpenChange={setOpen} className="space-y-1.5">
      <CollapsibleTrigger
        render={
          <button
            type="button"
            className="inline-flex items-center gap-1.5 text-xs text-muted-foreground transition-colors motion-reduce:transition-none hover:text-foreground"
          />
        }
      >
        <AtomIcon
          className={cn(
            "size-3 shrink-0",
            isStreaming && "animate-pulse motion-reduce:animate-none text-foreground"
          )}
        />
        {isStreaming ? (
          <ShimmeringText
            text="思考过程"
            color="var(--muted-foreground)"
            shimmeringColor="var(--foreground)"
            duration={1.4}
          />
        ) : (
          <span>思考过程</span>
        )}
        <ChevronDownIcon
          className={cn(
            "size-3 transition-transform duration-150 motion-reduce:transition-none",
            !open && "-rotate-90"
          )}
        />
      </CollapsibleTrigger>

      <CollapsibleContent>
        <div className="border-l-2 border-border pl-3 text-[13px] leading-relaxed whitespace-pre-wrap text-muted-foreground/90">
          {reasoning}
        </div>
      </CollapsibleContent>
    </Collapsible>
  );
}

export function AgentProcessTimeline({
  process,
  isStreaming = false,
  defaultOpen = false,
  hasAnswerText = false,
  onOpenFile,
  onOpenStoryboard,
  onOpenTasks,
}: AgentProcessTimelineProps) {
  const [open, setOpen] = useState(defaultOpen || isStreaming);

  const visibleTurns = process.turns.filter(hasVisibleTurnContent);

  if (visibleTurns.length === 0) {
    return null;
  }

  const workLabel = isStreaming
    ? "工作过程"
    : formatWorkDurationLabel(process.durationMs) ?? "工作过程";

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
            className="inline-flex items-center gap-1.5 py-0.5 text-xs text-muted-foreground transition-colors motion-reduce:transition-none hover:text-foreground"
          />
        }
      >
        <ClockIcon
          className={cn(
            "size-3.5 shrink-0",
            isStreaming && "animate-pulse motion-reduce:animate-none text-foreground"
          )}
        />
        {isStreaming ? (
          <ShimmeringText
            text={workLabel}
            color="var(--muted-foreground)"
            shimmeringColor="var(--foreground)"
            duration={1.4}
          />
        ) : (
          <span>{workLabel}</span>
        )}
        <ChevronDownIcon
          className={cn(
            "size-3.5 transition-transform duration-150 motion-reduce:transition-none",
            !open && "-rotate-90"
          )}
        />
      </CollapsibleTrigger>

      <CollapsibleContent className="mt-2.5 space-y-3">
        {visibleTurns.map((turn, idx) => {
          const thinkingActive = isThinkingTurnActive({
            turn,
            isStreaming,
            isLastVisibleTurn: idx === visibleTurns.length - 1,
            hasAnswerText,
          });
          return (
            <div key={turn.turnNo} className="space-y-2.5">
              {turn.reasoning?.trim() ? (
                <ThinkingSection
                  reasoning={turn.reasoning.trim()}
                  isStreaming={thinkingActive}
                />
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
                        <motion.button
                          key={tool.callId}
                          type="button"
                          initial={{ opacity: 0, y: 4 }}
                          animate={{ opacity: 1, y: 0 }}
                          transition={{ duration: 0.14, ease: "easeOut" }}
                          onClick={() => handleToolClick(tool)}
                          className={cn(
                            "flex w-full items-center gap-2 rounded-lg bg-muted/70 px-3 py-2 text-left text-xs transition-colors motion-reduce:transition-none hover:bg-muted",
                            tool.status === "FAILED" &&
                              "bg-destructive/8 hover:bg-destructive/12"
                          )}
                        >
                          {chipBody}
                        </motion.button>
                      );
                    }

                    return (
                      <motion.div
                        key={tool.callId}
                        initial={{ opacity: 0, y: 4 }}
                        animate={{ opacity: 1, y: 0 }}
                        transition={{ duration: 0.14, ease: "easeOut" }}
                        className={cn(
                          "flex w-full items-center gap-2 rounded-lg bg-muted/70 px-3 py-2 text-xs",
                          tool.status === "FAILED" && "bg-destructive/8"
                        )}
                      >
                        {chipBody}
                      </motion.div>
                    );
                  })}
                </div>
              ) : null}
            </div>
          );
        })}
      </CollapsibleContent>
    </Collapsible>
  );
}
