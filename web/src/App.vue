<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import {
  ApiError,
  loadPublication,
  preferenceSummary,
  saveDecision,
  invitationToken,
} from "./behavior";
import { getSession, request } from "./api";
import CandidateInfo from "./CandidateInfo.vue";
import type {
  Candidate,
  CandidateInput,
  Choice,
  Decision,
  Group,
  GroupSummary,
  Invitation,
  Publication,
  Session,
} from "./types";

const appOrigin = location.origin;
const shareId = location.pathname.match(/^\/share\/([^/]+)\/?$/)?.[1];
const joinToken = ref(new URLSearchParams(location.search).get("token") || "");
const publicState = reactive<{
  publication: Publication | null;
  loading: boolean;
  error: string;
}>({ publication: null, loading: false, error: "" });
const session = ref<Session | null>(null),
  ready = ref(false),
  busy = ref(false);
const error = ref(""),
  notice = ref(""),
  conflict = ref(false);
const authMode = ref<"login" | "register">("login");
const auth = reactive({ username: "", password: "" });
const groups = ref<GroupSummary[]>([]),
  group = ref<Group | null>(null);
const createOpen = ref(false),
  create = reactive({ name: "", eventDate: "" });
const candidateOpen = ref(false),
  editing = ref<Candidate | null>(null);
const emptyCandidate = (): CandidateInput => ({
  title: "",
  address: "",
  timeWindow: "",
  sourceUrl: "",
  verifiedAt: "",
  openingText: "",
  openingStatus: "unknown",
  feeText: "",
  feeStatus: "unknown",
  reservationText: "",
  reservationStatus: "unknown",
  internalNote: "",
});
const candidate = reactive(emptyCandidate());
const decision = reactive<Decision>({
  mainId: "",
  backupId: "",
  meetingTime: "",
  meetingPlace: "",
  publicNote: "",
});
const invitationMinutes = ref(60),
  invitations = reactive<Record<string, Invitation[]>>({});
const owner = computed(() => group.value?.role === "owner");
const rules = [
  {
    label: "开放安排",
    text: "openingText",
    status: "openingStatus",
    placeholder: "例如周二至周日 9:00—17:00",
  },
  {
    label: "费用",
    text: "feeText",
    status: "feeStatus",
    placeholder: "例如常设展免费；特展另计",
  },
  {
    label: "预约",
    text: "reservationText",
    status: "reservationStatus",
    placeholder: "例如需提前在官方渠道预约",
  },
] as const;
const choices = [
  { value: "want", label: "想去" },
  { value: "okay", label: "可接受" },
  { value: "avoid", label: "不想去" },
] as const;

function resetDecision(value: Group) {
  Object.assign(
    decision,
    value.decision || {
      mainId: "",
      backupId: "",
      meetingTime: "",
      meetingPlace: "",
      publicNote: "",
    },
  );
  conflict.value = false;
}
function adopt(value: Group, reset = false) {
  group.value = value;
  const i = groups.value.findIndex((g) => g.id === value.id);
  const brief = {
    id: value.id,
    name: value.name,
    eventDate: value.eventDate,
    version: value.version,
    role: value.role,
  };
  if (i < 0) groups.value.unshift(brief);
  else groups.value[i] = brief;
  if (reset) resetDecision(value);
}
async function refreshGroups() {
  groups.value = await request<GroupSummary[]>("/api/groups");
}
async function openGroup(id: string) {
  await act(async () => {
    adopt(await request<Group>(`/api/groups/${encodeURIComponent(id)}`), true);
    candidateOpen.value = false;
  });
}
async function act(work: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await work();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "操作未完成，请重试。";
    if (e instanceof ApiError && e.status === 401) {
      session.value = await getSession().catch(() => null);
      group.value = null;
      groups.value = [];
    }
  } finally {
    busy.value = false;
  }
}
async function authenticate() {
  await act(async () => {
    await request(`/api/${authMode.value}`, "POST", auth);
    auth.password = "";
    session.value = await getSession();
    await refreshGroups();
    notice.value =
      authMode.value === "register"
        ? "账号已创建。现在可以创建或加入活动。"
        : "已登录。";
  });
}
async function logout() {
  await act(async () => {
    await request("/api/logout", "POST");
    session.value = await getSession();
    group.value = null;
    groups.value = [];
    Object.keys(invitations).forEach((k) => delete invitations[k]);
  });
}
async function createGroup() {
  await act(async () => {
    adopt(await request<Group>("/api/groups", "POST", create), true);
    createOpen.value = false;
    create.name = "";
    create.eventDate = "";
    notice.value = "活动已创建，添加两个候选，再邀请朋友一起选。";
  });
}
async function claim() {
  await act(async () => {
    adopt(
      await request<Group>("/api/invitations/claim", "POST", {
        token: invitationToken(joinToken.value),
      }),
      true,
    );
    joinToken.value = "";
    history.replaceState(null, "", "/");
    notice.value = "已加入活动，可以查看候选并表达偏好。";
  });
}
function startCandidate(existing?: Candidate) {
  editing.value = existing || null;
  Object.assign(candidate, existing ? { ...existing } : emptyCandidate());
  candidateOpen.value = true;
}
async function submitCandidate() {
  await act(async () => {
    const id = group.value!.id;
    const body = Object.fromEntries(
      Object.keys(emptyCandidate()).map((k) => [
        k,
        candidate[k as keyof CandidateInput],
      ]),
    );
    try {
      await request(
        `/api/groups/${id}/candidates${editing.value ? `/${editing.value.id}` : ""}`,
        editing.value ? "PUT" : "POST",
        editing.value
          ? { ...body, expectedVersion: editing.value.version }
          : body,
      );
    } catch (e) {
      if (e instanceof ApiError && e.status === 409) {
        adopt(await request<Group>(`/api/groups/${id}`));
        throw new Error(
          "这项候选已被修改。已刷新活动，请关闭编辑并查看新内容后再修改。",
        );
      }
      throw e;
    }
    adopt(await request<Group>(`/api/groups/${id}`));
    candidateOpen.value = false;
    notice.value = "候选已保存。";
  });
}
function myChoice(id: string) {
  return group.value?.preferences.find(
    (p) => p.candidateId === id && p.username === session.value?.user?.username,
  )?.choice;
}
async function vote(id: string, choice?: Choice) {
  await act(async () => {
    const path = `/api/groups/${group.value!.id}/candidates/${id}/preference`;
    adopt(
      await request<Group>(
        path,
        choice ? "PUT" : "DELETE",
        choice ? { choice } : undefined,
      ),
    );
    notice.value = choice
      ? "偏好已更新。你可以改选或撤回。"
      : "已撤回这项偏好。";
  });
}
async function submitDecision() {
  await act(async () => {
    const id = group.value!.id;
    const expectedVersion = group.value!.version;
    const result = await saveDecision(
      () =>
        request<Group>(`/api/groups/${id}/decision`, "PUT", {
          ...decision,
          expectedVersion,
        }),
      () => request<Group>(`/api/groups/${id}`),
    );
    adopt(result.group);
    conflict.value = result.conflict;
    if (result.conflict)
      error.value =
        "活动刚有新的候选或回应，已刷新最新内容。请重新查看后，再点击保存定案。";
    else {
      notice.value = "定案已保存。确认集合信息后，可生成只读链接。";
      resetDecision(result.group);
    }
  });
}
async function invite() {
  await act(async () => {
    const id = group.value!.id;
    const value = await request<Invitation>(
      `/api/groups/${id}/invitations`,
      "POST",
      { expiresInMinutes: Number(invitationMinutes.value) },
    );
    (invitations[id] ||= []).unshift(value);
    notice.value = "邀请已生成，仅能领取一次。把链接发给一位朋友。";
  });
}
function invitationUrl(value: Invitation) {
  return `${location.origin}/join?token=${encodeURIComponent(value.token)}`;
}
async function copy(value: string) {
  try {
    await navigator.clipboard.writeText(value);
    notice.value = "链接已复制。";
  } catch {
    error.value = "浏览器未允许复制，请选中链接手动复制。";
  }
}
async function revokeInvitation(value: Invitation) {
  await act(async () => {
    await request(
      `/api/groups/${group.value!.id}/invitations/${value.id}`,
      "DELETE",
    );
    value.revoked = true;
    notice.value = "邀请已撤销，原链接不能再加入。";
  });
}
async function publish() {
  await act(async () => {
    const id = group.value!.id;
    await request(`/api/groups/${id}/publications`, "POST");
    adopt(await request<Group>(`/api/groups/${id}`));
    notice.value = "只读定案已发布。之后修改活动不会改写这个版本。";
  });
}
async function revokePublication(id: string) {
  if (!confirm("撤销后，这个公开链接将无法读取。确认撤销？")) return;
  await act(async () => {
    await request(
      `/api/groups/${group.value!.id}/publications/${id}`,
      "DELETE",
    );
    adopt(await request<Group>(`/api/groups/${group.value!.id}`));
    notice.value = "公开链接已撤销。";
  });
}
function dateText(value: string) {
  return value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "";
}
async function readPublic() {
  await loadPublication(publicState, () =>
    request<Publication>(`/api/publications/${encodeURIComponent(shareId!)}`),
  );
}
onMounted(async () => {
  if (shareId) {
    await readPublic();
    ready.value = true;
    return;
  }
  try {
    session.value = await getSession();
    if (session.value.user) await refreshGroups();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "暂时无法连接。";
  } finally {
    ready.value = true;
  }
});
</script>

<template>
  <header class="topbar">
    <a href="/" class="brand"
      ><span class="brand-mark" aria-hidden="true">✓</span>一起定活动</a
    >
    <div class="account" v-if="!shareId && session?.user">
      <span>{{ session.user.username }}</span
      ><button class="text-button" @click="logout" :disabled="busy">
        退出
      </button>
    </div>
    <span class="public-label" v-if="shareId">活动定案</span>
  </header>
  <main v-if="shareId" class="share-shell">
    <p v-if="publicState.loading" role="status">正在读取定案…</p>
    <section v-else-if="publicState.error" class="empty-state">
      <h1>暂时无法查看</h1>
      <p role="alert">{{ publicState.error }}</p>
      <button @click="readPublic">重新读取</button>
    </section>
    <article v-else-if="publicState.publication" class="public-plan">
      <div
        class="old-banner"
        v-if="!publicState.publication.isLatest"
        role="status"
      >
        这是旧版定案。组织者已发布新版，请向组织者确认最新安排。
      </div>
      <p class="date-tag">{{ publicState.publication.content.eventDate }}</p>
      <h1>{{ publicState.publication.content.groupName }}</h1>
      <section class="meeting-block">
        <h2>先在这里集合</h2>
        <p class="meeting-time">
          {{ publicState.publication.content.meetingTime }}
        </p>
        <p class="meeting-place">
          {{ publicState.publication.content.meetingPlace }}
        </p>
        <p v-if="publicState.publication.content.publicNote" class="preserve">
          {{ publicState.publication.content.publicNote }}
        </p>
      </section>
      <section class="public-candidate">
        <span class="plan-badge">主要安排</span>
        <h2>{{ publicState.publication.content.main.title }}</h2>
        <CandidateInfo :candidate="publicState.publication.content.main" />
      </section>
      <section class="public-candidate backup">
        <span class="plan-badge">备用安排</span>
        <h2>{{ publicState.publication.content.backup.title }}</h2>
        <CandidateInfo :candidate="publicState.publication.content.backup" />
      </section>
      <footer class="publication-footer">
        <p>
          第 {{ publicState.publication.version }} 版 ·
          {{ dateText(publicState.publication.publishedAt) }} 发布
        </p>
        <p>
          此页只展示已发布的安排。费用、开放和预约规则请以来源最新说明为准。
        </p>
        <button class="text-button" @click="readPublic">检查链接状态</button>
      </footer>
    </article>
  </main>
  <main v-else>
    <div class="message-wrap">
      <p v-if="error" class="message error" role="alert">{{ error }}</p>
      <p v-if="notice" class="message success" role="status">{{ notice }}</p>
    </div>
    <p v-if="!ready" class="loading" role="status">正在打开活动…</p>
    <section v-else-if="!session?.user" class="auth-layout">
      <div class="welcome">
        <div class="welcome-symbol" aria-hidden="true">一起去</div>
        <h1>想法各有不同，<br />出发前一起定。</h1>
        <p>
          把候选的地点、费用和预约要求放在一起，让朋友各自回应，再分享一份清楚的集合安排。
        </p>
        <p v-if="joinToken" class="invitation-hint">
          你正在领取一份活动邀请。请先登录，或创建自己的账号。
        </p>
      </div>
      <form class="auth-form panel" @submit.prevent="authenticate">
        <h2>{{ authMode === "login" ? "登录，继续商量" : "创建你的账号" }}</h2>
        <label
          >用户名<input
            v-model="auth.username"
            required
            minlength="2"
            maxlength="32"
            autocomplete="username"
            placeholder="用于让朋友认出你" /></label
        ><label
          >密码<input
            v-model="auth.password"
            type="password"
            required
            minlength="8"
            maxlength="128"
            :autocomplete="
              authMode === 'login' ? 'current-password' : 'new-password'
            "
            placeholder="至少 8 个字符" /></label
        ><button class="primary" :disabled="busy">
          {{
            busy ? "正在处理…" : authMode === "login" ? "登录" : "注册并登录"
          }}</button
        ><button
          type="button"
          class="text-button"
          @click="
            authMode = authMode === 'login' ? 'register' : 'login';
            error = '';
          "
        >
          {{
            authMode === "login" ? "还没有账号？创建账号" : "已有账号？去登录"
          }}
        </button>
      </form>
    </section>
    <div v-else class="workspace">
      <aside class="group-sidebar">
        <div class="sidebar-title">
          <h2>我的活动</h2>
          <button
            class="small-button"
            @click="createOpen = !createOpen"
            :aria-expanded="createOpen"
          >
            新建
          </button>
        </div>
        <form
          v-if="createOpen"
          class="compact-form"
          @submit.prevent="createGroup"
        >
          <label
            >活动名称<input
              v-model="create.name"
              required
              maxlength="100"
              placeholder="例如周日一起看展" /></label
          ><label
            >活动日期<input
              v-model="create.eventDate"
              type="date"
              required /></label
          ><button class="primary" :disabled="busy">创建活动</button>
        </form>
        <nav class="group-nav" aria-label="活动列表">
          <button
            v-for="item in groups"
            :key="item.id"
            :class="{ active: group?.id === item.id }"
            @click="openGroup(item.id)"
            :disabled="busy"
          >
            <strong>{{ item.name }}</strong
            ><span
              >{{ item.eventDate
              }}<small v-if="item.role === 'owner'">我组织</small></span
            >
          </button>
        </nav>
        <p v-if="!groups.length" class="muted empty-mini">
          还没有活动。先创建一个，或领取朋友发来的邀请。
        </p>
        <details class="join-details" :open="!!joinToken">
          <summary>领取活动邀请</summary>
          <form class="compact-form" @submit.prevent="claim">
            <label
              >邀请链接或邀请码<input
                v-model="joinToken"
                required
                placeholder="粘贴朋友发来的完整邀请链接" /></label
            ><button :disabled="busy">加入活动</button>
          </form>
        </details>
      </aside>
      <section v-if="!group" class="workspace-empty">
        <span class="big-check" aria-hidden="true">✓</span>
        <h1>下次见面，去哪里？</h1>
        <p>
          创建一个活动，把候选放在一起。<br />朋友表达偏好，你来确定主选和备选。
        </p>
        <button class="primary" @click="createOpen = true">
          创建第一个活动
        </button>
      </section>
      <section v-else class="activity-content">
        <div class="activity-heading">
          <div>
            <p class="date-tag">{{ group.eventDate }}</p>
            <h1>{{ group.name }}</h1>
            <p class="muted">
              {{ group.members.length }} 人参与 ·
              {{ owner ? "你是组织者" : "一起商量中" }}
            </p>
          </div>
          <button
            class="small-button"
            @click="openGroup(group.id)"
            :disabled="busy"
          >
            刷新回应
          </button>
        </div>
        <div class="activity-columns">
          <div class="candidates-column">
            <section class="candidate-section">
              <div class="section-title">
                <div>
                  <h2>把想去的地方放一起</h2>
                  <p>偏好只是参考，组织者会确认最后安排。</p>
                </div>
                <button
                  class="primary"
                  @click="startCandidate()"
                  :disabled="busy"
                >
                  添加候选
                </button>
              </div>
              <form
                v-if="candidateOpen"
                class="candidate-form panel"
                @submit.prevent="submitCandidate"
              >
                <div class="section-title">
                  <h3>{{ editing ? "修改候选" : "添加一个候选" }}</h3>
                  <button
                    type="button"
                    class="text-button"
                    @click="candidateOpen = false"
                  >
                    关闭
                  </button>
                </div>
                <label
                  >候选名称<input
                    v-model="candidate.title"
                    required
                    maxlength="100"
                    placeholder="例如云南省博物馆常设展"
                /></label>
                <div class="form-row">
                  <label
                    >地址<input
                      v-model="candidate.address"
                      maxlength="300"
                      placeholder="填写具体地址" /></label
                  ><label
                    >活动时段<input
                      v-model="candidate.timeWindow"
                      maxlength="200"
                      placeholder="例如 14:00—16:00"
                  /></label>
                </div>
                <div class="form-row">
                  <label
                    >信息来源<input
                      v-model="candidate.sourceUrl"
                      type="url"
                      maxlength="1500"
                      placeholder="https:// 官方说明链接" /></label
                  ><label
                    >核实日期<input v-model="candidate.verifiedAt" type="date"
                  /></label>
                </div>
                <fieldset
                  v-for="rule in rules"
                  :key="rule.text"
                  class="rule-form"
                >
                  <legend>{{ rule.label }}</legend>
                  <label class="sr-only" :for="rule.text"
                    >{{ rule.label }}说明</label
                  ><input
                    :id="rule.text"
                    v-model="candidate[rule.text]"
                    maxlength="500"
                    :placeholder="rule.placeholder"
                  /><label class="status-select"
                    >核实状态<select
                      v-model="candidate[rule.status]"
                      :aria-label="`${rule.label}核实状态`"
                    >
                      <option value="unknown">尚未核实</option>
                      <option value="verified">已按来源核实</option>
                    </select></label
                  >
                </fieldset>
                <p class="form-help">
                  标记“已核实”需填写来源和日期。不了解的费用或预约规则保留“尚未核实”。
                </p>
                <label
                  >成员备注<textarea
                    v-model="candidate.internalNote"
                    maxlength="1000"
                    rows="2"
                    placeholder="仅活动成员可见，不会出现在公开定案中"
                  ></textarea></label
                ><button class="primary" :disabled="busy">
                  {{ busy ? "保存中…" : "保存候选" }}
                </button>
              </form>
              <div
                v-if="!group.candidates.length && !candidateOpen"
                class="empty-candidates"
              >
                <h3>先放两个候选，朋友才好比较。</h3>
                <p>把开放、费用、预约要求写清楚，未知的部分也留个提醒。</p>
              </div>
              <article
                v-for="item in group.candidates"
                :key="item.id"
                class="candidate-card"
              >
                <div class="candidate-title">
                  <h3>{{ item.title }}</h3>
                  <button
                    v-if="owner || item.createdBy === session.user.username"
                    class="text-button"
                    @click="startCandidate(item)"
                    :disabled="busy"
                  >
                    修改
                  </button>
                </div>
                <CandidateInfo :candidate="item" />
                <p v-if="item.internalNote" class="private-note">
                  <span>成员备注</span>{{ item.internalNote }}
                </p>
                <div
                  class="preference-actions"
                  :aria-label="`${item.title}的偏好`"
                >
                  <button
                    v-for="choice in choices"
                    :key="choice.value"
                    :class="[
                      'preference',
                      choice.value,
                      { selected: myChoice(item.id) === choice.value },
                    ]"
                    :aria-pressed="myChoice(item.id) === choice.value"
                    @click="vote(item.id, choice.value)"
                    :disabled="busy"
                  >
                    {{ choice.label
                    }}<b>{{
                      preferenceSummary(group, item.id)[choice.value].length
                    }}</b></button
                  ><button
                    v-if="myChoice(item.id)"
                    class="text-button"
                    @click="vote(item.id)"
                    :disabled="busy"
                  >
                    撤回
                  </button>
                </div>
                <div class="response-details">
                  <p
                    v-for="choice in choices"
                    :key="choice.value"
                    v-show="
                      preferenceSummary(group, item.id)[choice.value].length
                    "
                  >
                    <span>{{ choice.label }}</span
                    >{{
                      preferenceSummary(group, item.id)[choice.value].join("、")
                    }}
                  </p>
                  <p class="missing">
                    <span>未回应</span
                    >{{
                      preferenceSummary(group, item.id).missing.join("、") ||
                      "大家都已回应"
                    }}
                  </p>
                </div>
              </article>
            </section>
          </div>
          <aside class="decision-column">
            <section class="decision-panel panel">
              <h2>这次就这样安排</h2>
              <p v-if="!owner" class="muted">
                由组织者确认主选、备选与集合信息。
              </p>
              <form v-if="owner" @submit.prevent="submitDecision">
                <label
                  >主要安排<select
                    v-model="decision.mainId"
                    aria-label="主要安排"
                    required
                  >
                    <option value="" disabled>选择主要候选</option>
                    <option
                      v-for="item in group.candidates"
                      :key="item.id"
                      :value="item.id"
                    >
                      {{ item.title }}
                    </option>
                  </select></label
                ><label
                  >备用安排<select
                    v-model="decision.backupId"
                    aria-label="备用安排"
                    required
                  >
                    <option value="" disabled>选择不同的备用候选</option>
                    <option
                      v-for="item in group.candidates"
                      :key="item.id"
                      :value="item.id"
                      :disabled="item.id === decision.mainId"
                    >
                      {{ item.title }}
                    </option>
                  </select></label
                ><label
                  >集合时间<input
                    v-model="decision.meetingTime"
                    required
                    maxlength="200"
                    placeholder="例如 10月25日 13:30" /></label
                ><label
                  >集合地点<input
                    v-model="decision.meetingPlace"
                    required
                    maxlength="300"
                    placeholder="写清入口、地铁口或地标" /></label
                ><label
                  >公开提醒<textarea
                    v-model="decision.publicNote"
                    maxlength="1000"
                    rows="3"
                    placeholder="例如下雨改去备选；每人自行预约"
                  ></textarea>
                </label>
                <p v-if="conflict" class="inline-warning">
                  已刷新新回应，请重新确认安排。
                </p>
                <button
                  class="primary full"
                  :disabled="busy || group.candidates.length < 2"
                >
                  {{ conflict ? "重新确认并保存定案" : "保存定案" }}
                </button>
                <p v-if="group.candidates.length < 2" class="form-help">
                  至少需要两个不同候选，才能确定主备安排。
                </p>
              </form>
              <div v-else-if="group.decision" class="readonly-decision">
                <p>
                  <span>主选</span
                  >{{
                    group.candidates.find(
                      (c) => c.id === group!.decision!.mainId,
                    )?.title
                  }}
                </p>
                <p>
                  <span>备选</span
                  >{{
                    group.candidates.find(
                      (c) => c.id === group!.decision!.backupId,
                    )?.title
                  }}
                </p>
                <p>
                  {{ group.decision.meetingTime }}<br />{{
                    group.decision.meetingPlace
                  }}
                </p>
                <p class="preserve">{{ group.decision.publicNote }}</p>
              </div>
              <p v-else class="muted">还没有定案，先看看朋友的偏好。</p>
            </section>
            <section class="publication-panel panel">
              <h2>发给朋友一份定案</h2>
              <p class="muted">
                只读链接只含主备安排和公开提醒，不含成员、偏好或成员备注。
              </p>
              <button
                v-if="owner"
                class="primary full"
                @click="publish"
                :disabled="busy || !group.decision"
              >
                发布当前定案
              </button>
              <p v-if="owner && !group.decision" class="form-help">
                请先保存定案。
              </p>
              <div
                v-for="item in group.publications"
                :key="item.id"
                class="publication-item"
              >
                <div>
                  <strong>第 {{ item.version }} 版</strong
                  ><span :class="{ unknown: !item.isLatest }">{{
                    item.revoked
                      ? "已撤销"
                      : item.isLatest
                        ? "最新发布"
                        : "旧版"
                  }}</span>
                </div>
                <p>{{ dateText(item.publishedAt) }}</p>
                <template v-if="!item.revoked"
                  ><a :href="`/share/${item.id}`" target="_blank" rel="noopener"
                    >查看定案</a
                  ><button
                    class="text-button"
                    @click="copy(`${appOrigin}/share/${item.id}`)"
                  >
                    复制链接</button
                  ><button
                    v-if="owner"
                    class="text-button danger"
                    @click="revokePublication(item.id)"
                    :disabled="busy"
                  >
                    撤销
                  </button></template
                >
              </div>
              <p v-if="!group.publications.length" class="form-help">
                尚未发布。修改草稿不会改变已发布的内容。
              </p>
            </section>
            <section class="members-panel panel">
              <h2>一起商量的人</h2>
              <ul class="member-list">
                <li v-for="member in group.members" :key="member.username">
                  <span class="avatar">{{ member.username.slice(0, 1) }}</span
                  ><span>{{ member.username }}</span
                  ><small v-if="member.role === 'owner'">组织者</small>
                </li>
              </ul>
              <details v-if="owner">
                <summary>邀请一位朋友</summary>
                <form class="compact-form" @submit.prevent="invite">
                  <label
                    >邀请有效分钟数<input
                      v-model="invitationMinutes"
                      type="number"
                      min="1"
                      max="10080"
                      required /></label
                  ><button :disabled="busy">生成一次性邀请</button>
                </form>
                <p class="form-help">
                  每条邀请仅供一人领取。朋友需要登录自己的账号。
                </p>
                <div
                  v-for="item in invitations[group.id] || []"
                  :key="item.id"
                  class="invitation-item"
                >
                  <p>
                    {{
                      item.revoked
                        ? "已撤销"
                        : `有效至 ${dateText(item.expiresAt)}`
                    }}
                  </p>
                  <template v-if="!item.revoked"
                    ><input
                      :value="invitationUrl(item)"
                      readonly
                      aria-label="邀请链接"
                      @focus="($event.target as HTMLInputElement).select()"
                    />
                    <div>
                      <button
                        class="text-button"
                        @click="copy(invitationUrl(item))"
                      >
                        复制邀请</button
                      ><button
                        class="text-button danger"
                        @click="revokeInvitation(item)"
                        :disabled="busy"
                      >
                        撤销邀请
                      </button>
                    </div></template
                  >
                </div>
              </details>
            </section>
          </aside>
        </div>
      </section>
    </div>
  </main>
</template>
