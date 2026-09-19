export type AuthSession = {
  userId: string | number;
  email: string;
  nickname: string;
  avatar?: string | null;
  gender?: number | null;
  description?: string | null;
  accessToken: string;
  refreshToken: string;
  nettyUri?: string | null;
  offlineTime?: string | number | null;
};

type ApiResponse<T> = {
  code: number;
  data: T;
  message?: string;
};

type PasswordLoginPayload = {
  email: string;
  password: string;
};

type CodeLoginPayload = {
  email: string;
  code: string;
};

type RegisterPayload = PasswordLoginPayload & {
  confirmPassword: string;
  code: string;
  nickname: string;
};

type TokenPair = Pick<AuthSession, "accessToken" | "refreshToken">;

const STORAGE_KEY = "chatim.auth.session.v1";
const API_BASE = (import.meta.env.VITE_API_BASE_URL || "http://localhost:10010").replace(/\/$/, "");
const DEMO_PASSWORD = "123456";

export class AuthApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AuthApiError";
  }
}

function isDemoMode() {
  const params = new URLSearchParams(window.location.search);
  return params.get("demo") === "1" || import.meta.env.VITE_DEMO_MODE === "true";
}

function makeDemoSession(email: string, nickname = "旅行中的小鹿"): AuthSession {
  return {
    userId: "demo-user-001",
    email,
    nickname,
    avatar: null,
    gender: null,
    description: "在 ChatIM 发现熟悉的人和新的故事。",
    accessToken: "demo-access-token",
    refreshToken: "demo-refresh-token",
    nettyUri: "ws://localhost:10086/ws",
    offlineTime: null,
  };
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 12_000);

  try {
    const response = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
        ...(init?.body ? { "Content-Type": "application/json" } : {}),
        ...init?.headers,
      },
      signal: controller.signal,
    });

    let payload: ApiResponse<T> | null = null;
    try {
      payload = (await response.json()) as ApiResponse<T>;
    } catch {
      // The gateway can return an empty body while it is starting up.
    }

    if (!response.ok || !payload || payload.code !== 200) {
      throw new AuthApiError(payload?.message || "服务暂时不可用，请稍后再试");
    }

    return payload.data;
  } catch (error) {
    if (error instanceof AuthApiError) throw error;
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new AuthApiError("请求超时，请检查网络后重试");
    }
    throw new AuthApiError("暂时连接不上服务，请检查网关是否已启动");
  } finally {
    window.clearTimeout(timeout);
  }
}

export async function sendCaptcha(email: string) {
  if (isDemoMode()) {
    await new Promise((resolve) => window.setTimeout(resolve, 460));
    return true;
  }

  await request<unknown>(`/api/user/sendCaptcha?targetEmail=${encodeURIComponent(email)}`);
  return true;
}

export async function loginWithPassword(payload: PasswordLoginPayload) {
  if (isDemoMode()) {
    await new Promise((resolve) => window.setTimeout(resolve, 620));
    if (payload.password !== DEMO_PASSWORD) {
      throw new AuthApiError("演示密码为 123456");
    }
    return makeDemoSession(payload.email);
  }

  return request<AuthSession>("/api/user/login/password", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export async function loginWithCode(payload: CodeLoginPayload) {
  if (isDemoMode()) {
    await new Promise((resolve) => window.setTimeout(resolve, 620));
    if (payload.code !== DEMO_PASSWORD) {
      throw new AuthApiError("演示验证码为 123456");
    }
    return makeDemoSession(payload.email);
  }

  return request<AuthSession>("/api/user/login/code", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export async function registerAccount(payload: RegisterPayload) {
  if (isDemoMode()) {
    await new Promise((resolve) => window.setTimeout(resolve, 760));
    if (payload.code !== DEMO_PASSWORD) {
      throw new AuthApiError("演示验证码为 123456");
    }
    return makeDemoSession(payload.email, payload.nickname);
  }

  return request<AuthSession>("/api/user/register", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export async function refreshSession(refreshToken: string) {
  if (isDemoMode()) {
    return {
      accessToken: "demo-access-token",
      refreshToken: "demo-refresh-token",
    } satisfies TokenPair;
  }

  return request<TokenPair>("/api/user/refresh", {
    method: "POST",
    headers: { "Refresh-Token": refreshToken },
  });
}

export async function logout(accessToken: string) {
  if (isDemoMode()) return;

  await request<unknown>("/api/user/logout", {
    method: "GET",
    headers: { "Access-Token": accessToken },
  });
}

export function saveSession(session: AuthSession) {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
}

export function loadSession(): AuthSession | null {
  const stored = window.localStorage.getItem(STORAGE_KEY);
  if (!stored) return null;

  try {
    return JSON.parse(stored) as AuthSession;
  } catch {
    window.localStorage.removeItem(STORAGE_KEY);
    return null;
  }
}

export function clearSession() {
  window.localStorage.removeItem(STORAGE_KEY);
}

export const demoModeEnabled = isDemoMode();
