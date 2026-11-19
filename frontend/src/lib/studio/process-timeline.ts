import type { AgentTurnProcessResponse } from "@/lib/api/types";

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
