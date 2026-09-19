import { demoModeEnabled, type AuthSession } from "./auth";

export type RealtimeConnectionState =
  | "connecting"
  | "connected"
  | "reconnecting"
  | "disconnected"
  | "auth-failed";

export type RealtimeMessageBody = {
  content: string;
  replyId?: string | number | null;
  redPacketId?: string | null;
  redPacketWrapperText?: string | null;
};

export type RealtimeMessage = {
  sessionId: string | number;
  receiverId?: string | number | null;
  senderId: string | number;
  type: number;
  sessionType: number;
  createdTime?: number;
  messageId?: string | number;
  clientMessageId?: string;
  nickname?: string | null;
  avatar?: string | null;
  role?: number | null;
  body: RealtimeMessageBody;
};

export type OutgoingRealtimeMessage = Pick<
  RealtimeMessage,
  "sessionId" | "receiverId" | "senderId" | "type" | "sessionType" | "clientMessageId" | "body"
>;

type ApiResponse<T> = {
  code: number;
  data: T;
  message?: string;
};

type NativeSocketFactory = (url: string, headers: Record<string, string>) => WebSocket;

declare global {
  interface Window {
    chatIMCreateWebSocket?: NativeSocketFactory;
  }
}

const API_BASE = (import.meta.env.VITE_API_BASE_URL || "http://localhost:10010").replace(/\/$/, "");
const OFFLINE_CURSOR_PREFIX = "chatim.realtime.offline-cursor.v1";
const HEARTBEAT_INTERVAL = 20_000;
const PONG_TIMEOUT = 12_000;
const RECONNECT_DELAYS = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000];

export class RealtimeApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RealtimeApiError";
  }
}

type ClientOptions = {
  session: AuthSession;
  onState: (state: RealtimeConnectionState) => void;
  onMessage: (message: RealtimeMessage) => void;
  onConnected: () => void;
};

export class ChatRealtimeClient {
  private readonly session: AuthSession;
  private readonly onState: ClientOptions["onState"];
  private readonly onMessage: ClientOptions["onMessage"];
  private readonly onConnected: ClientOptions["onConnected"];
  private socket: WebSocket | null = null;
  private stopped = false;
  private opened = false;
  private opening = false;
  private reconnectAttempt = 0;
  private reconnectTimer: number | undefined;
  private heartbeatTimer: number | undefined;
  private pongTimer: number | undefined;

  constructor(options: ClientOptions) {
    this.session = options.session;
    this.onState = options.onState;
    this.onMessage = options.onMessage;
    this.onConnected = options.onConnected;
  }

  connect() {
    this.stopped = false;
    this.open(false);
  }

  isConnected() {
    return this.opened || this.opening || Boolean(this.socket);
  }

  disconnect() {
    this.stopped = true;
    this.opening = false;
    this.opened = false;
    this.clearTimers();
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, "screen closed");
  }

  reconnectNow() {
    this.stopped = false;
    this.opened = false;
    this.opening = false;
    this.reconnectAttempt = 0;
    this.clearTimers();
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, "manual reconnect");
    this.open(true);
  }

  send(message: OutgoingRealtimeMessage) {
    if (demoModeEnabled) {
      if (!this.opened) return false;
      const now = Date.now();
      window.setTimeout(() => {
        if (this.stopped) return;
        this.onMessage({
          ...message,
          messageId: `demo-${message.clientMessageId}`,
          createdTime: now,
        });
      }, 420);
      return true;
    }

    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return false;
    this.socket.send(JSON.stringify(message));
    return true;
  }

  private open(reconnecting: boolean) {
    if (this.stopped) return;
    if (this.opening || this.opened || this.socket) return;
    this.opening = true;
    this.onState(reconnecting ? "reconnecting" : "connecting");

    if (demoModeEnabled) {
      window.setTimeout(() => {
        if (this.stopped) return;
        this.opening = false;
        this.opened = true;
        this.reconnectAttempt = 0;
        this.onState("connected");
        this.onConnected();
      }, reconnecting ? 360 : 520);
      return;
    }

    if (!this.session.nettyUri) {
      this.opening = false;
      this.onState("disconnected");
      return;
    }

    try {
      const url = normalizeWebSocketUrl(this.session.nettyUri);
      const socket = window.chatIMCreateWebSocket
        ? window.chatIMCreateWebSocket(url, { Authorization: this.session.accessToken })
        : new WebSocket(url);
      this.socket = socket;
      this.opened = false;

      socket.addEventListener("open", () => {
        if (this.stopped || socket !== this.socket) return;
        this.opening = false;
        this.opened = true;
        this.reconnectAttempt = 0;
        this.onState("connected");
        this.startHeartbeat();
        this.onConnected();
      });

      socket.addEventListener("message", (event) => {
        if (event.data === "pong") {
          if (this.pongTimer) window.clearTimeout(this.pongTimer);
          this.pongTimer = undefined;
          return;
        }
        try {
          const message = JSON.parse(String(event.data)) as RealtimeMessage;
          if (message && message.sessionId != null && message.body) this.onMessage(message);
        } catch {
          // Ignore malformed or unrelated service frames without breaking the live connection.
        }
      });

      socket.addEventListener("close", (event) => {
        if (socket !== this.socket) return;
        this.socket = null;
        this.opening = false;
        this.opened = false;
        this.clearHeartbeat();
        if (this.stopped) return;
        if (event.code === 4001 || event.code === 4401) {
          this.onState("auth-failed");
          return;
        }
        this.scheduleReconnect();
      });

      socket.addEventListener("error", () => {
        if (socket === this.socket && socket.readyState < WebSocket.CLOSING) socket.close();
      });
    } catch {
      this.opening = false;
      this.scheduleReconnect();
    }
  }

  private scheduleReconnect() {
    if (this.stopped) return;
    this.opened = false;
    this.opening = false;
    this.onState("reconnecting");
    const delay = RECONNECT_DELAYS[Math.min(this.reconnectAttempt, RECONNECT_DELAYS.length - 1)];
    this.reconnectAttempt += 1;
    this.reconnectTimer = window.setTimeout(() => this.open(true), delay);
  }

  private startHeartbeat() {
    this.clearHeartbeat();
    const heartbeat = () => {
      if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return;
      this.socket.send("ping");
      if (this.pongTimer) window.clearTimeout(this.pongTimer);
      this.pongTimer = window.setTimeout(() => {
        if (this.socket && this.socket.readyState < WebSocket.CLOSING) this.socket.close();
      }, PONG_TIMEOUT);
      this.heartbeatTimer = window.setTimeout(heartbeat, HEARTBEAT_INTERVAL);
    };
    this.heartbeatTimer = window.setTimeout(heartbeat, HEARTBEAT_INTERVAL);
  }

  private clearHeartbeat() {
    if (this.heartbeatTimer) window.clearTimeout(this.heartbeatTimer);
    if (this.pongTimer) window.clearTimeout(this.pongTimer);
    this.heartbeatTimer = undefined;
    this.pongTimer = undefined;
  }

  private clearTimers() {
    if (this.reconnectTimer) window.clearTimeout(this.reconnectTimer);
    this.reconnectTimer = undefined;
    this.clearHeartbeat();
  }
}

export function resolveOfflineStart(session: AuthSession, fallback: number) {
  const localCursor = readOfflineCursor(session.userId);
  if (localCursor > 0) return localCursor;
  const serverOfflineTime = Number(session.offlineTime);
  return Number.isFinite(serverOfflineTime) && serverOfflineTime > 0 ? serverOfflineTime : fallback;
}

export function saveOfflineCursor(userId: AuthSession["userId"], value: number) {
  window.localStorage.setItem(`${OFFLINE_CURSOR_PREFIX}.${userId}`, String(value));
}

export async function fetchOfflineMessages(session: AuthSession, offlineTime: number) {
  if (demoModeEnabled) {
    await delay(300);
    return {} as Record<string, RealtimeMessage[]>;
  }
  return request<Record<string, RealtimeMessage[]>>(session, "/api/message/offline", {
    userId: session.userId,
    offlineTime,
  });
}

export async function fetchHistoryMessages(session: AuthSession, sessionId: string, beforeTime: number, limit = 20) {
  if (demoModeEnabled) {
    await delay(360);
    return [] as RealtimeMessage[];
  }
  return request<RealtimeMessage[]>(session, "/api/message/history", {
    sessionId,
    beforeTime,
    limit,
  });
}

function readOfflineCursor(userId: AuthSession["userId"]) {
  const parsed = Number(window.localStorage.getItem(`${OFFLINE_CURSOR_PREFIX}.${userId}`));
  return Number.isFinite(parsed) ? parsed : 0;
}

function normalizeWebSocketUrl(raw: string) {
  const url = new URL(raw, window.location.href);
  if (url.protocol === "http:") url.protocol = "ws:";
  if (url.protocol === "https:") url.protocol = "wss:";
  const trimmedPath = url.pathname.replace(/\/$/, "");
  if (!trimmedPath || trimmedPath === "/" || trimmedPath === "/ws") url.pathname = "/ws/netty";
  return url.toString();
}

async function request<T>(session: AuthSession, path: string, body: Record<string, unknown>) {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 12_000);
  try {
    const response = await fetch(`${API_BASE}${path}`, {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "Access-Token": session.accessToken,
        "Refresh-Token": session.refreshToken,
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const payload = (await response.json().catch(() => null)) as ApiResponse<T> | null;
    if (!response.ok || !payload || payload.code !== 200) {
      throw new RealtimeApiError(payload?.message || "消息同步暂时不可用");
    }
    return payload.data;
  } catch (error) {
    if (error instanceof RealtimeApiError) throw error;
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new RealtimeApiError("消息同步超时，请稍后重试");
    }
    throw new RealtimeApiError("暂时连接不上消息服务");
  } finally {
    window.clearTimeout(timeout);
  }
}

function delay(duration: number) {
  return new Promise((resolve) => window.setTimeout(resolve, duration));
}
