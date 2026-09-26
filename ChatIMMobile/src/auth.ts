import {
  readSecureValue,
  removeSecureValue,
  storageMode,
  writeSecureValue,
} from "./storage";

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

const API_BASE = (import.meta.env.VITE_API_BASE_URL || "http://localhost:10010").replace(/\/$/, "");
const STORAGE_KEY = "chatim.auth.session.v2";
const LEGACY_STORAGE_KEY = "chatim.auth.session.v1";
const ENVIRONMENT_ID = import.meta.env.VITE_ENVIRONMENT_ID || API_BASE;
const DEMO_PASSWORD = "123456";

type StoredSession = {
  version: 2;
  environment: string;
  session: AuthSession;
};

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

export async function saveSession(session: AuthSession) {
  const stored: StoredSession = {
    version: 2,
    environment: ENVIRONMENT_ID,
    session,
  };
  removeBrowserSessionCopies();
  await writeSecureValue(STORAGE_KEY, JSON.stringify(stored));
  removeBrowserSessionCopies();
}

export async function loadSession(): Promise<AuthSession | null> {
  const stored = await readSecureValue(STORAGE_KEY);
  const restored = parseStoredSession(stored);
  if (restored) return restored;

  const legacy = parseLegacySession(window.localStorage.getItem(LEGACY_STORAGE_KEY));
  if (legacy) {
    await saveSession(legacy);
    return legacy;
  }
  await removeSecureValue(STORAGE_KEY);
  removeBrowserSessionCopies();
  return null;
}

export async function clearSession() {
  try {
    await removeSecureValue(STORAGE_KEY);
  } finally {
    removeBrowserSessionCopies();
  }
}

export function authStorageMode() {
  return storageMode();
}

function parseStoredSession(value: string | null) {
  if (!value) return null;
  try {
    const stored = JSON.parse(value) as StoredSession;
    if (stored.version !== 2 || stored.environment !== ENVIRONMENT_ID) return null;
    return isAuthSession(stored.session) ? stored.session : null;
  } catch {
    return null;
  }
}

function parseLegacySession(value: string | null) {
  if (!value) return null;
  try {
    const session = JSON.parse(value) as AuthSession;
    return isAuthSession(session) ? session : null;
  } catch {
    return null;
  }
}

function isAuthSession(value: unknown): value is AuthSession {
  if (!value || typeof value !== "object") return false;
  const session = value as Partial<AuthSession>;
  return (typeof session.userId === "string" || typeof session.userId === "number")
    && typeof session.email === "string"
    && typeof session.nickname === "string"
    && typeof session.accessToken === "string"
    && session.accessToken.length > 0
    && typeof session.refreshToken === "string"
    && session.refreshToken.length > 0;
}

function removeBrowserSessionCopies() {
  window.localStorage.removeItem(LEGACY_STORAGE_KEY);
  if (storageMode() === "native-secure") window.localStorage.removeItem(STORAGE_KEY);
}

export const demoModeEnabled = isDemoMode();
