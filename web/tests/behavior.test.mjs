import { test } from "node:test";
import assert from "node:assert/strict";
import {
  ApiError,
  ruleDisplay,
  preferenceSummary,
  saveDecision,
  loadPublication,
  invitationToken,
} from "../src/behavior.ts";

// If unknown is treated as free/no reservation, a recipient could make a bad trip.
test("未核实规则永远不能被空值或陈旧文字渲染成免费/免预约", () => {
  assert.deepEqual(ruleDisplay("", "unknown"), {
    text: "尚未核实",
    verified: false,
  });
  assert.deepEqual(ruleDisplay("免费", "unknown"), {
    text: "免费（待核实）",
    verified: false,
  });
  assert.deepEqual(ruleDisplay("需提前预约", "verified"), {
    text: "需提前预约",
    verified: true,
  });
});

// A response for another candidate must never hide a missing response on this candidate.
test("每个候选分别列出未回应者，撤回后重新计为未回应", () => {
  const group = {
    members: [{ username: "小陈" }, { username: "小李" }, { username: "小王" }],
    preferences: [
      { candidateId: "a", username: "小陈", choice: "want" },
      { candidateId: "a", username: "小李", choice: "avoid" },
      { candidateId: "b", username: "小王", choice: "okay" },
    ],
  };
  assert.deepEqual(preferenceSummary(group, "a"), {
    want: ["小陈"],
    okay: [],
    avoid: ["小李"],
    missing: ["小王"],
  });
  group.preferences = group.preferences.filter((p) => p.username !== "小李");
  assert.deepEqual(preferenceSummary(group, "a").missing, ["小李", "小王"]);
});

// A stale owner must see new preferences, not silently overwrite them by retrying.
test("定案遇409只刷新一次并要求重新确认，不自动重提旧定案", async () => {
  const events = [];
  const result = await saveDecision(
    async () => {
      events.push("write");
      throw new ApiError(409, "conflict", "已更新");
    },
    async () => {
      events.push("refresh");
      return { version: 9 };
    },
  );
  assert.deepEqual(events, ["write", "refresh"]);
  assert.equal(result.conflict, true);
  assert.deepEqual(result.group, { version: 9 });
});

// Revocation/network failure must not leave previously read public content visible.
test("公开链接410清除已读正文，不能在错误下面保留旧活动", async () => {
  const state = {
    publication: { content: { groupName: "旧活动" } },
    loading: false,
    error: "",
  };
  await loadPublication(state, async () => {
    throw new ApiError(410, "gone", "已撤销");
  });
  assert.equal(state.publication, null);
  assert.equal(state.loading, false);
  assert.match(state.error, /撤销/);
});

test("定案普通错误不会刷新或假报保存成功", async () => {
  let refreshes = 0;
  await assert.rejects(
    () =>
      saveDecision(
        async () => {
          throw new ApiError(403, "forbidden", "仅组织者可操作");
        },
        async () => {
          refreshes++;
          return {};
        },
      ),
    /组织者/,
  );
  assert.equal(refreshes, 0);
});

// Pasting a normal invitation link must not send a full URL as a token.
test("邀请可粘贴完整链接或邀请码，其他网页和执行协议不能当成邀请", () => {
  assert.equal(invitationToken("  abc_DEF-123  "), "abc_DEF-123");
  assert.equal(
    invitationToken("http://127.0.0.1:8098/join?token=abc_DEF-123"),
    "abc_DEF-123",
  );
  assert.throws(
    () => invitationToken("https://example.com/other?token=abc"),
    /邀请/,
  );
  assert.throws(() => invitationToken("javascript:alert(1)"), /邀请/);
  assert.throws(() => invitationToken("http://127.0.0.1:8098/join"), /邀请/);
});
