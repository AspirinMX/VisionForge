import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  formatWorkDurationLabel,
  hasVisibleTurnContent,
  isThinkingTurnActive,
} from "./process-timeline.ts";

describe("formatWorkDurationLabel", () => {
  it("formats elapsed run durations as 工作了 labels", () => {
    assert.equal(formatWorkDurationLabel(null), null);
    assert.equal(formatWorkDurationLabel(-1), null);
    assert.equal(formatWorkDurationLabel(400), "工作了 <1s");
    assert.equal(formatWorkDurationLabel(18_000), "工作了 18s");
    assert.equal(formatWorkDurationLabel(120_000), "工作了 2m");
    assert.equal(formatWorkDurationLabel(135_000), "工作了 2m 15s");
  });
});

describe("hasVisibleTurnContent and isThinkingTurnActive", () => {
  it("keeps tool-only turns visible without marking them as thinking", () => {
    const toolOnlyTurn = {
      reasoning: null,
      stepText: null,
      toolCalls: [
        {
          callId: "c1",
          toolName: "read_file",
          action: "读取文件",
          target: "script.md",
          status: "RUNNING",
        },
      ],
    };

    assert.equal(hasVisibleTurnContent(toolOnlyTurn), true);
    assert.equal(
      isThinkingTurnActive({
        turn: toolOnlyTurn,
        isStreaming: true,
        isLastVisibleTurn: true,
        hasAnswerText: false,
      }),
      false
    );
  });

  it("stops thinking active state once tool calls or answer text start", () => {
    const thinkingTurn = {
      reasoning: "分析镜头时长",
      stepText: null,
      toolCalls: [],
    };

    assert.equal(
      isThinkingTurnActive({
        turn: thinkingTurn,
        isStreaming: true,
        isLastVisibleTurn: true,
        hasAnswerText: false,
      }),
      true
    );
    assert.equal(
      isThinkingTurnActive({
        turn: thinkingTurn,
        isStreaming: true,
        isLastVisibleTurn: true,
        hasAnswerText: true,
      }),
      false
    );
    assert.equal(
      isThinkingTurnActive({
        turn: {
          ...thinkingTurn,
          toolCalls: [
            {
              callId: "c2",
              toolName: "save_storyboard",
              action: "保存分镜",
              target: null,
              status: "SUCCEEDED",
            },
          ],
        },
        isStreaming: true,
        isLastVisibleTurn: true,
        hasAnswerText: false,
      }),
      false
    );
  });
});
