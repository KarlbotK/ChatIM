import { demoModeEnabled, type AuthSession } from "./auth";

export type CreateGroupResult = {
  creatorId: string;
  sessionId: string;
  sessionName: string;
  sessionType: number;
  avatar?: string | null;
  failedMemberIds: string[];
};

export type InviteGroupResult = {
  successIds: string[];
  failedIds: string[];
};

type ApiResponse<T> = {
  code: number;
  data: T;
  message?: string;
};

const API_BASE = (import.meta.env.VITE_API_BASE_URL || "http://localhost:10010").replace(/\/$/, "");

export class GroupApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "GroupApiError";
  }
}

async function request<T>(session: AuthSession, path: string, init: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 12_000);

  try {
    const response = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "Access-Token": session.accessToken,
        "Refresh-Token": session.refreshToken,
        ...init.headers,
      },
      signal: controller.signal,
    });

    let payload: ApiResponse<T> | null = null;
    try {
      payload = (await response.json()) as ApiResponse<T>;
    } catch {
      // The gateway can return an empty response while a service is starting.
    }

    if (!response.ok || !payload || payload.code !== 200) {
      throw new GroupApiError(payload?.message || "群聊服务暂时不可用");
    }
    return payload.data;
  } catch (error) {
    if (error instanceof GroupApiError) throw error;
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new GroupApiError("请求超时，请检查网络后重试");
    }
    throw new GroupApiError("暂时连接不上群聊服务");
  } finally {
    window.clearTimeout(timeout);
  }
}

async function demoDelay(duration = 680) {
  await new Promise((resolve) => window.setTimeout(resolve, duration));
}

export async function createGroup(session: AuthSession, memberIds: string[]) {
  if (demoModeEnabled) {
    await demoDelay();
    const failedMemberIds = memberIds.includes("u-anan") ? ["u-anan"] : [];
    const suffix = Date.now().toString().slice(-7);
    return {
      creatorId: String(session.userId),
      sessionId: `g-${suffix}`,
      sessionName: "旅行中的小鹿的群聊",
      sessionType: 1,
      avatar: null,
      failedMemberIds,
    } satisfies CreateGroupResult;
  }

  return request<CreateGroupResult>(session, "/api/group", {
    method: "POST",
    body: JSON.stringify({
      creatorId: session.userId,
      memberIds,
    }),
  });
}

export async function inviteGroupMembers(session: AuthSession, sessionId: string, inviteeIds: string[]) {
  if (demoModeEnabled) {
    await demoDelay(560);
    const failedIds = inviteeIds.includes("u-anan") ? ["u-anan"] : [];
    return {
      successIds: inviteeIds.filter((id) => !failedIds.includes(id)),
      failedIds,
    } satisfies InviteGroupResult;
  }

  return request<InviteGroupResult>(session, "/api/group/invite", {
    method: "POST",
    body: JSON.stringify({
      sessionId,
      inviterId: session.userId,
      inviteeIds,
    }),
  });
}
