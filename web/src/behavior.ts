export class ApiError extends Error {
  status: number;
  code: string;
  constructor(status: number, code: string, message: string) {
    super(message);
    this.status = status;
    this.code = code;
  }
}
export function ruleDisplay(
  text: string,
  status: string,
): { text: string; verified: boolean } {
  const clean = text?.trim() || "";
  if (status !== "verified" || !clean)
    return { text: clean ? `${clean}（待核实）` : "尚未核实", verified: false };
  return { text: clean, verified: true };
}
export function preferenceSummary(
  group: {
    members: { username: string }[];
    preferences: { candidateId: string; username: string; choice: string }[];
  },
  candidateId: string,
) {
  const result: {
    want: string[];
    okay: string[];
    avoid: string[];
    missing: string[];
  } = { want: [], okay: [], avoid: [], missing: [] };
  for (const member of group.members) {
    const p = group.preferences.find(
      (p) => p.candidateId === candidateId && p.username === member.username,
    );
    if (p && ["want", "okay", "avoid"].includes(p.choice))
      result[p.choice as "want" | "okay" | "avoid"].push(member.username);
    else result.missing.push(member.username);
  }
  return result;
}
export async function saveDecision<T>(
  write: () => Promise<T>,
  refresh: () => Promise<T>,
): Promise<{ group: T; conflict: boolean }> {
  try {
    return { group: await write(), conflict: false };
  } catch (error) {
    if (error instanceof ApiError && error.status === 409)
      return { group: await refresh(), conflict: true };
    throw error;
  }
}
export async function loadPublication<T>(
  state: { publication: T | null; loading: boolean; error: string },
  read: () => Promise<T>,
): Promise<void> {
  state.publication = null;
  state.error = "";
  state.loading = true;
  try {
    state.publication = await read();
  } catch (error) {
    state.error =
      error instanceof ApiError && error.status === 410
        ? "这个定案链接已被组织者撤销。请向组织者索取新链接。"
        : error instanceof Error
          ? error.message
          : "暂时无法读取定案，请稍后重试。";
  } finally {
    state.loading = false;
  }
}
export function invitationToken(input: string): string {
  const value = input.trim();
  if (!value || value.length > 2000)
    throw new Error("请粘贴朋友发来的邀请链接或邀请码。");
  if (/^[a-z][a-z0-9+.-]*:/i.test(value)) {
    let url: URL;
    try {
      url = new URL(value);
    } catch {
      throw new Error("邀请链接格式不完整，请重新复制。");
    }
    if (
      !["http:", "https:"].includes(url.protocol) ||
      url.pathname !== "/join" ||
      !url.searchParams.get("token")?.trim()
    )
      throw new Error("这不是活动邀请链接，请重新复制朋友发来的邀请。");
    return url.searchParams.get("token")!.trim();
  }
  return value;
}
