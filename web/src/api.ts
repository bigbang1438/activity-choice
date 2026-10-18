import { ApiError } from "./behavior";
import type { Session } from "./types";
let session: Session | null = null;
export async function request<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method !== "GET") {
    if (!session) await getSession();
    headers[session!.csrfHeader] = session!.csrfToken;
    if (body !== undefined) headers["Content-Type"] = "application/json";
  }
  let response: Response;
  try {
    response = await fetch(path, {
      method,
      credentials: "same-origin",
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(
      0,
      "network",
      "连接中断，请检查网络后重试。尚未确认操作是否保存，可先刷新活动。",
    );
  }
  const payload = await response.json().catch(() => null);
  if (!response.ok)
    throw new ApiError(
      response.status,
      payload?.code || "request_failed",
      payload?.message || "操作未完成，请重试。",
    );
  return payload as T;
}
export async function getSession(): Promise<Session> {
  session = await request<Session>("/api/session");
  return session;
}
