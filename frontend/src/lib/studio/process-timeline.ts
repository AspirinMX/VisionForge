import type { AgentTurnProcessResponse } from "@/lib/api/types";

export function formatWorkDurationLabel(
  durationMs: number | null
): string | null {
  if (durationMs === null || durationMs < 0) {
    return null;
  }
  const totalSeconds = Math.round(durationMs / 1000);
  if (totalSeconds < 1) {
    return "工作了 <1s";
  }
  if (totalSeconds < 60) {
    return `工作了 ${totalSeconds}s`;
  }
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return seconds > 0 ? `工作了 ${minutes}m ${seconds}s` : `工作了 ${minutes}m`;
}

export function hasVisibleTurnContent(
  turn: Pick<AgentTurnProcessResponse, "reasoning" | "stepText" | "toolCalls">
): boolean {
  return (
    Boolean(turn.reasoning?.trim()) ||
    Boolean(turn.stepText?.trim()) ||
    turn.toolCalls.length > 0
  );
}

export function isThinkingTurnActive(params: {
  turn: Pick<AgentTurnProcessResponse, "reasoning" | "stepText" | "toolCalls">;
  isStreaming: boolean;
  isLastVisibleTurn: boolean;
  hasAnswerText: boolean;
}): boolean {
  const { turn, isStreaming, isLastVisibleTurn, hasAnswerText } = params;
  return (
    isStreaming &&
    isLastVisibleTurn &&
    Boolean(turn.reasoning?.trim()) &&
    !turn.stepText?.trim() &&
    turn.toolCalls.length === 0 &&
    !hasAnswerText
  );
}
