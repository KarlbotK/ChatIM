import { demoModeEnabled, type AuthSession } from "./auth";

export type FriendProfile = {
  userId: string;
  nickname: string;
  avatar?: string | null;
  email?: string | null;
  phone?: string | null;
  signature?: string | null;
  gender?: number | null;
  sessionId?: string | null;
  status: number;
};

export type FriendListItem = {
  userId: string;
  nickname: string;
  avatar?: string | null;
  status: number;
  signature?: string | null;
  sessionId?: string | null;
};

export type FriendApplication = {
  userId: string;
  nickname: string;
  avatar?: string | null;
  msg: string;
  status: number;
  time: string;
  isReceiver: number;
};

export type ApplicationDecision = {
  userId: string;
  sessionId: string;
  sessionType: number;
  sessionName: string;
  avatar?: string | null;
};

type ApiResponse<T> = {
  code: number;
  data: T;
  message?: string;
};

type PageResponse<T> = {
  list: T[];
  total: number;
  pageSize: number;
  pageNum: number;
  pages: number;
  hasNext: boolean;
  hasPrevious: boolean;
};

const API_BASE = (import.meta.env.VITE_API_BASE_URL || "http://localhost:10010").replace(/\/$/, "");

const demoSearchUsers: FriendProfile[] = [
  {
    userId: "demo-user-suwan",
    nickname: "苏晚",
    email: "suwan@chatim.cn",
    phone: "18800001111",
    signature: "愿每次出发，都有好天气。",
    gender: 0,
    sessionId: null,
    status: -1,
  },
  {
    userId: "u-qing",
    nickname: "青禾",
    email: "qinghe@chatim.cn",
    phone: "18800002222",
    signature: "在路上，也在生活里。",
    gender: 2,
    sessionId: "c-qing",
    status: 0,
  },
];

export class ContactApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ContactApiError";
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
      throw new ContactApiError(payload?.message || "联系人服务暂时不可用");
    }
    return payload.data;
  } catch (error) {
    if (error instanceof ContactApiError) throw error;
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new ContactApiError("请求超时，请检查网络后重试");
    }
    throw new ContactApiError("暂时连接不上联系人服务");
  } finally {
    window.clearTimeout(timeout);
  }
}

async function demoDelay(duration = 420) {
  await new Promise((resolve) => window.setTimeout(resolve, duration));
}

export async function searchUser(session: AuthSession, keyword: string) {
  if (demoModeEnabled) {
    await demoDelay();
    const normalized = keyword.trim().toLocaleLowerCase();
    if (normalized === session.email.toLocaleLowerCase()) {
      throw new ContactApiError("这是你自己的账号");
    }
    const result = demoSearchUsers.find((item) =>
      item.email?.toLocaleLowerCase() === normalized || item.phone === normalized,
    );
    if (!result) throw new ContactApiError("没有找到这个用户，请检查邮箱或手机号");
    return result;
  }

  return request<FriendProfile>(
    session,
    `/api/contact/${encodeURIComponent(String(session.userId))}/user/search?keyword=${encodeURIComponent(keyword)}`,
  );
}

export async function fetchFriends(session: AuthSession, key = "") {
  if (demoModeEnabled) return [];
  const result = await request<PageResponse<FriendListItem>>(
    session,
    `/api/contact/${encodeURIComponent(String(session.userId))}/friend?pageNum=1&pageSize=100&key=${encodeURIComponent(key)}`,
  );
  return result.list.filter((item) => item.status === 0);
}

export async function fetchFriendDetail(session: AuthSession, friendId: string) {
  if (demoModeEnabled) {
    await demoDelay(260);
    const found = demoSearchUsers.find((item) => item.userId === friendId);
    if (found) return found;
    throw new ContactApiError("暂时无法读取这位好友的资料");
  }
  return request<FriendProfile>(
    session,
    `/api/contact/${encodeURIComponent(String(session.userId))}/friend/${encodeURIComponent(friendId)}`,
  );
}

export async function sendFriendRequest(session: AuthSession, receiverId: string, message: string) {
  if (demoModeEnabled) {
    await demoDelay(560);
    return true;
  }
  return request<boolean>(
    session,
    `/api/contact/${encodeURIComponent(String(session.userId))}/friend/${encodeURIComponent(receiverId)}`,
    { method: "POST", body: JSON.stringify({ msg: message }) },
  );
}

export async function fetchFriendApplications(session: AuthSession) {
  if (demoModeEnabled) return [];
  const result = await request<PageResponse<FriendApplication>>(
    session,
    `/api/contact/${encodeURIComponent(String(session.userId))}/apply?pageNum=1&pageSize=50`,
  );
  return result.list;
}

export async function fetchFriendApplicationCount(session: AuthSession) {
  if (demoModeEnabled) return 0;
  const result = await request<{ count: number }>(
    session,
    `/api/contact/${encodeURIComponent(String(session.userId))}/applyCount`,
  );
  return result.count;
}

export async function updateFriendApplications(
  session: AuthSession,
  status: 1 | 2 | 3,
  senderIds: string[],
) {
  if (demoModeEnabled) {
    await demoDelay(520);
    if (status !== 1) return true;
    const senderId = senderIds[0];
    return {
      userId: senderId,
      sessionId: `c-${senderId}`,
      sessionType: 0,
      sessionName: "新朋友",
      avatar: null,
    } satisfies ApplicationDecision;
  }
  return request<ApplicationDecision | true>(
    session,
    `/api/contact/${encodeURIComponent(String(session.userId))}/application/${status}`,
    { method: "POST", body: JSON.stringify({ receiveuserIds: senderIds }) },
  );
}
