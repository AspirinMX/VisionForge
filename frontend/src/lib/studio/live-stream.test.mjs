import assert from "node:assert/strict";
import { describe, it } from "node:test";

import {
  applyStreamEvent,
  buildLivePresentation,
  isLiveUserMessagePersisted,
  maxMessageSeq,
} from "./live-stream.ts";

function makeMessage(overrides) {
  return {
    status: "COMPLETED",
    senderUserId: "user-1",
    createdAt: "2026-11-19T14:00:00Z",
    assetIds: [],
    generationTaskIds: [],
    ...overrides,
  };
}

function makeLiveStream(overrides = {}) {
  return {
    prompt: "生成一段短片分镜",
    assetIds: [],
    baselineSeq: 2,
    runId: null,
    turns: [],
    activeTurnNo: 0,
    ...overrides,
  };
}

describe("isLiveUserMessagePersisted", () => {
  it("keeps optimistic bubble before persisted user message is fetched", () => {
    const messages = [
      makeMessage({ id: "m1", role: "USER", content: "hi", sequenceNo: 1 }),
      makeMessage({
        id: "m2",
        role: "ASSISTANT",
        content: "hello",
        sequenceNo: 2,
      }),
    ];

    const live = makeLiveStream({ baselineSeq: maxMessageSeq(messages) });
    assert.equal(isLiveUserMessagePersisted(messages, live, false), false);
  });

  it("hides optimistic bubble once refresh brings in user message after baselineSeq", () => {
    const messages = [
      makeMessage({ id: "m1", role: "USER", content: "hi", sequenceNo: 1 }),
      makeMessage({
        id: "m2",
        role: "ASSISTANT",
        content: "hello",
        sequenceNo: 2,
      }),
      makeMessage({
        id: "m3",
        role: "USER",
        content: "生成一段短片分镜",
        sequenceNo: 3,
      }),
    ];

    const live = makeLiveStream({ baselineSeq: 2, runId: "run-1" });
    assert.equal(isLiveUserMessagePersisted(messages, live, false), true);
  });

  it("distinguishes repeated identical prompts using baselineSeq", () => {
    const messages = [
      makeMessage({
        id: "m1",
        role: "USER",
        content: "继续",
        sequenceNo: 1,
      }),
      makeMessage({
        id: "m2",
        role: "ASSISTANT",
        content: "好的",
        sequenceNo: 2,
      }),
    ];

    const live = makeLiveStream({
      prompt: "继续",
      baselineSeq: 2,
      runId: "run-2",
    });
    assert.equal(isLiveUserMessagePersisted(messages, live, false), false);

    const updated = [
      ...messages,
      makeMessage({
        id: "m3",
        role: "USER",
        content: "继续",
        sequenceNo: 3,
      }),
    ];
    assert.equal(isLiveUserMessagePersisted(updated, live, false), true);
  });

  it("falls back safely when turn starts during initialLoading (draftHandoff)", () => {
    const livePending = makeLiveStream({
      baselineSeq: null,
      runId: null,
      assetIds: ["asset-1"],
    });
    assert.equal(isLiveUserMessagePersisted([], livePending, true), false);

    const liveAccepted = { ...livePending, runId: "run-1" };
    assert.equal(isLiveUserMessagePersisted([], liveAccepted, false), false);

    const persistedMessages = [
      makeMessage({
        id: "m1",
        role: "USER",
        content: "生成一段短片分镜",
        sequenceNo: 1,
        assetIds: ["asset-1"],
      }),
    ];
    assert.equal(
      isLiveUserMessagePersisted(persistedMessages, liveAccepted, false),
      true
    );
  });

  it("hides optimistic bubble if a background poll fetches the new message before accepted arrives", () => {
    const liveBeforeAccepted = makeLiveStream({ baselineSeq: 2, runId: null });
    const updated = [
      makeMessage({ id: "m1", role: "USER", content: "hi", sequenceNo: 1 }),
      makeMessage({
        id: "m2",
        role: "ASSISTANT",
        content: "hello",
        sequenceNo: 2,
      }),
      makeMessage({
        id: "m3",
        role: "USER",
        content: "生成一段短片分镜",
        sequenceNo: 3,
      }),
    ];

    assert.equal(
      isLiveUserMessagePersisted(updated, liveBeforeAccepted, false),
      true
    );
  });
});

describe("applyStreamEvent and buildLivePresentation", () => {
  it("tracks tool turns and final answering markdown across stream events", () => {
    let state = makeLiveStream({ baselineSeq: 0 });
    state = applyStreamEvent(state, { type: "accepted", runId: "run-9" });
    state = applyStreamEvent(state, { type: "turn_start", turnNo: 0 });
    state = applyStreamEvent(state, {
      type: "tool_start",
      turnNo: 0,
      callId: "call-1",
      toolName: "load_skill",
      action: "加载短片指南",
      target: "short-film",
      status: "RUNNING",
    });
    state = applyStreamEvent(state, {
      type: "tool_done",
      turnNo: 0,
      callId: "call-1",
      toolName: "load_skill",
      action: "加载短片指南",
      target: "short-film",
      status: "SUCCEEDED",
    });
    state = applyStreamEvent(state, { type: "turn_start", turnNo: 1 });
    state = applyStreamEvent(state, {
      type: "reasoning_summary",
      content: "构思分镜...",
    });
    state = applyStreamEvent(state, { type: "delta", content: "已完成剧本。" });

    const presentation = buildLivePresentation(state);
    assert.equal(presentation.answerMarkdown, "已完成剧本。");
    assert.equal(presentation.process?.runId, "run-9");
    assert.equal(presentation.process?.turns.length, 2);
    assert.equal(presentation.process?.turns[0].toolCalls[0].status, "SUCCEEDED");
    assert.equal(presentation.process?.turns[1].reasoning, "构思分镜...");
  });
});
