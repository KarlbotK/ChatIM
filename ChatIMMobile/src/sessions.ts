import { demoModeEnabled, type AuthSession } from "./auth";
import type { RealtimeMessage } from "./realtime";

export type SessionSummary = {
  sessionId: string | number;
  sessionType: number;
  name?: string | null;
  avatar?: string | null;
  peerId?: string | number | null;
  lastMessage?: RealtimeMessage | null;
  lastMessageId?: string | number | null;
  lastMessageTime?: number | null;
  unreadCount: number;
  pinned: boolean;
  muted: boolean;
  memberCount?: number | null;
  currentUserRole?: number | null;
  lastReadMessageId?: string | number | null;
  updatedTime?: number | null;
};

export type SessionListResponse = {
  items: SessionSummary[];
  nextCursor?: string | null;
  hasMore: boolean;
  serverTime: number;
};

export type SessionReadResponse = {
  sessionId: string | number;
  lastReadMessageId: string | number;
  unreadCount: number;
};

export type SessionPreferenceResult = {
  sessionId: string | number;
  pinned: boolean;
  muted: boolean;
  hidden: boolean;
  updatedTime: number;
};

type ApiResponse<T> = {
  code: number;
  data: T;
  message?: string;
};

const API_BASE = (import.meta.env.VITE_API_BASE_URL || "http://localhost:10010").replace(/\/$/, "");

export class SessionApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SessionApiError";
  }
}

async function request<T>(session: AuthSession, path: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 12_000);

  try {
    const response = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${session.accessToken}`,
        "Access-Token": session.accessToken,
        "Refresh-Token": session.refreshToken,
        ...(init?.body ? { "Content-Type": "application/json" } : {}),
        ...init?.headers,
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
      throw new SessionApiError(payload?.message || "会话服务暂时不可用");
    }
    return payload.data;
  } catch (error) {
    if (error instanceof SessionApiError) throw error;
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new SessionApiError("请求超时，请检查网络后重试");
    }
    throw new SessionApiError("暂时连接不上会话服务");
  } finally {
    window.clearTimeout(timeout);
  }
}

export async function fetchSessionList(
  session: AuthSession,
  cursor: string | null = null,
  limit = 50,
) {
  if (demoModeEnabled) {
    return {
      items: [],
      nextCursor: cursor,
      hasMore: false,
      serverTime: Date.now(),
    } satisfies SessionListResponse;
  }

  const query = new URLSearchParams({ limit: String(limit) });
  if (cursor) query.set("cursor", cursor);
  return request<SessionListResponse>(session, `/api/session/list?${query.toString()}`);
}

export async function markSessionRead(
  session: AuthSession,
  sessionId: string,
  lastReadMessageId: string,
) {
  if (demoModeEnabled) {
    return {
      sessionId,
      lastReadMessageId,
      unreadCount: 0,
    } satisfies SessionReadResponse;
  }

  return request<SessionReadResponse>(
    session,
    `/api/session/${encodeURIComponent(sessionId)}/read`,
    {
      method: "POST",
      body: JSON.stringify({ lastReadMessageId }),
    },
  );
}

export async function updateSessionPinned(
  session: AuthSession,
  sessionId: string,
  pinned: boolean,
  currentMuted = false,
) {
  if (demoModeEnabled) {
    return demoPreference(sessionId, { pinned, muted: currentMuted });
  }
  return request<SessionPreferenceResult>(
    session,
    `/api/session/${encodeURIComponent(sessionId)}/pin`,
    { method: "POST", body: JSON.stringify({ enabled: pinned }) },
  );
}

export async function updateSessionMuted(
  session: AuthSession,
  sessionId: string,
  muted: boolean,
  currentPinned = false,
) {
  if (demoModeEnabled) {
    return demoPreference(sessionId, { pinned: currentPinned, muted });
  }
  return request<SessionPreferenceResult>(
    session,
    `/api/session/${encodeURIComponent(sessionId)}/mute`,
    { method: "POST", body: JSON.stringify({ enabled: muted }) },
  );
}

export async function hideSession(
  session: AuthSession,
  sessionId: string,
  currentPinned = false,
  currentMuted = false,
) {
  if (demoModeEnabled) {
    return demoPreference(sessionId, { pinned: currentPinned, muted: currentMuted, hidden: true });
  }
  return request<SessionPreferenceResult>(
    session,
    `/api/session/${encodeURIComponent(sessionId)}`,
    { method: "DELETE" },
  );
}

function demoPreference(
  sessionId: string,
  changes: Partial<Pick<SessionPreferenceResult, "pinned" | "muted" | "hidden">>,
) {
  return new Promise<SessionPreferenceResult>((resolve) => {
    window.setTimeout(() => resolve({
      sessionId,
      pinned: changes.pinned ?? false,
      muted: changes.muted ?? false,
      hidden: changes.hidden ?? false,
      updatedTime: Date.now(),
    }), 360);
  });
}
