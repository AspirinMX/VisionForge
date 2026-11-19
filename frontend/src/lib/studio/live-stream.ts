import type {
  AgentRunProcessResponse,
  AgentStreamEvent,
  AgentTurnProcessResponse,
  MessageResponse,
  ToolCallProcessResponse,
  UUID,
} from "../api/types";

export interface LiveTurnState {
  turnNo: number;
  reasoning: string;
  text: string;
  tools: ToolCallProcessResponse[];
}

export interface LiveStreamState {
  prompt: string;
  assetIds: UUID[];
  baselineSeq: number | null;
  runId: UUID | null;
  turns: LiveTurnState[];
  activeTurnNo: number;
}

function upsertLiveTurn(
  turns: LiveTurnState[],
  turnNo: number,
  updater: (turn: LiveTurnState) => LiveTurnState
): LiveTurnState[] {
  const index = turns.findIndex((item) => item.turnNo === turnNo);
  if (index === -1) {
    const next = [
      ...turns,
      updater({ turnNo, reasoning: "", text: "", tools: [] }),
    ];
    return next.sort((a, b) => a.turnNo - b.turnNo);
  }
  return turns.map((item, idx) => (idx === index ? updater(item) : item));
}

export function applyStreamEvent(
  prev: LiveStreamState,
  event: AgentStreamEvent
): LiveStreamState {
  switch (event.type) {
    case "accepted":
      return { ...prev, runId: event.runId };
    case "turn_start":
      return {
        ...prev,
        activeTurnNo: event.turnNo,
        turns: upsertLiveTurn(prev.turns, event.turnNo, (turn) => turn),
      };
    case "reasoning":
    case "reasoning_summary":
      return {
        ...prev,
        turns: upsertLiveTurn(prev.turns, prev.activeTurnNo, (turn) => ({
          ...turn,
          reasoning: turn.reasoning + event.content,
        })),
      };
    case "delta":
      return {
        ...prev,
        turns: upsertLiveTurn(prev.turns, prev.activeTurnNo, (turn) => ({
          ...turn,
          text: turn.text + event.content,
        })),
      };
    case "tool_start":
    case "tool_waiting":
    case "tool_done":
      return {
        ...prev,
        turns: upsertLiveTurn(prev.turns, prev.activeTurnNo, (turn) => {
          const nextTool: ToolCallProcessResponse = {
            callId: event.callId,
            toolName: event.toolName,
            action: event.action,
            target: event.target,
            status: event.status,
            fileId: event.fileId ?? null,
            taskId: event.taskId ?? null,
            errorMessage: event.message ?? null,
          };
          const existingIdx = turn.tools.findIndex(
            (t) => t.callId === event.callId
          );
          const nextTools =
            existingIdx === -1
              ? [...turn.tools, nextTool]
              : turn.tools.map((t, idx) => (idx === existingIdx ? nextTool : t));
          return { ...turn, tools: nextTools };
        }),
      };
    default:
      return prev;
  }
}

export function buildLivePresentation(liveStream: LiveStreamState): {
  process: AgentRunProcessResponse | null;
  answerMarkdown: string;
} {
  if (liveStream.turns.length === 0) {
    return { process: null, answerMarkdown: "" };
  }

  const lastIndex = liveStream.turns.length - 1;
  const processTurns: AgentTurnProcessResponse[] = [];
  let answerMarkdown = "";

  liveStream.turns.forEach((turn, idx) => {
    const isLast = idx === lastIndex;
    const isAnsweringTurn = isLast && turn.tools.length === 0;
    const trimmedReasoning = turn.reasoning.trim();
    const trimmedText = turn.text.trim();

    if (isAnsweringTurn) {
      answerMarkdown = turn.text;
      if (trimmedReasoning) {
        processTurns.push({
          turnNo: turn.turnNo,
          reasoning: trimmedReasoning,
          stepText: null,
          toolCalls: [],
        });
      }
      return;
    }

    if (trimmedReasoning || trimmedText || turn.tools.length > 0) {
      processTurns.push({
        turnNo: turn.turnNo,
        reasoning: trimmedReasoning || null,
        stepText: trimmedText || null,
        toolCalls: turn.tools,
      });
    }
  });

  return {
    process:
      processTurns.length > 0
        ? {
            runId: liveStream.runId ?? "live",
            durationMs: null,
            turns: processTurns,
          }
        : null,
    answerMarkdown,
  };
}

export function maxMessageSeq(
  messages: readonly Pick<MessageResponse, "sequenceNo">[]
): number {
  let max = 0;
  for (const message of messages) {
    if (message.sequenceNo > max) {
      max = message.sequenceNo;
    }
  }
  return max;
}

function sameAssetIdList(left: readonly UUID[], right: readonly UUID[]): boolean {
  if (left.length !== right.length) {
    return false;
  }
  return left.every((id, index) => id === right[index]);
}

/**
 * Returns true once the optimistic user turn in {@code liveStream} is already represented in
 * {@code messages}, preventing duplicate user bubbles during SSE streaming.
 */
export function isLiveUserMessagePersisted(
  messages: readonly MessageResponse[],
  liveStream: LiveStreamState | null,
  initialLoading: boolean
): boolean {
  if (!liveStream) {
    return false;
  }
  if (liveStream.baselineSeq !== null) {
    const baselineSeq = liveStream.baselineSeq;
    return messages.some(
      (message) => message.role === "USER" && message.sequenceNo > baselineSeq
    );
  }
  if (liveStream.runId === null || initialLoading || messages.length === 0) {
    return false;
  }
  const last = messages[messages.length - 1];
  return (
    last.role === "USER" &&
    last.content === liveStream.prompt &&
    sameAssetIdList(last.assetIds ?? [], liveStream.assetIds)
  );
}
