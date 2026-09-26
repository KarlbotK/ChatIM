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

export type MessageDeliveryStatus = "accepted" | "persisted" | "failed" | "notFound";

export type RealtimeMessageAck = {
  clientMessageId: string;
  messageId?: string | number | null;
  sessionId?: string | number | null;
  stage: Exclude<MessageDeliveryStatus, "notFound">;
  createdTime?: number | null;
  errorCode?: number | null;
  errorMessage?: string | null;
};

export type RealtimeSystemNotification = {
  messageId: string;
  sessionId?: string | number | null;
  senderId?: string | number | null;
  receiverId: string | number;
  type: number;
  sessionType?: number | null;
  timestamp: number;
  body: Record<string, unknown>;
};

export type MessageStatusResult = Omit<RealtimeMessageAck, "stage"> & {
  status: MessageDeliveryStatus;
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

type WebSocketTicketResponse = {
  ticket: string;
  nettyUri: string;
  expiresInSeconds: number;
  expiresAt: number;
};

export type OfflineSyncResponse = {
  items: RealtimeMessage[];
  nextCursor?: string | null;
  hasMore: boolean;
  serverTime: number;
};

type NativeSocketFactory = (url: string, headers: Record<string, string>) => WebSocket;

declare global {
  interface Window {
    chatIMCreateWebSocket?: NativeSocketFactory;
  }
}

const API_BASE = (import.meta.env.VITE_API_BASE_URL || "http://localhost:10010").replace(/\/$/, "");
const OFFLINE_CURSOR_PREFIX = "chatim.realtime.offline-cursor.v2";
const HEARTBEAT_INTERVAL = 20_000;
const PONG_TIMEOUT = 12_000;
const RECONNECT_DELAYS = [1_000, 2_000, 4_000, 8_000, 16_000, 30_000];

export class RealtimeApiError extends Error {
  readonly code?: number;
  readonly status?: number;

  constructor(message: string, code?: number, status?: number) {
    super(message);
    this.name = "RealtimeApiError";
    this.code = code;
    this.status = status;
  }
}

type ClientOptions = {
  session: AuthSession;
  onState: (state: RealtimeConnectionState) => void;
  onMessage: (message: RealtimeMessage) => void;
  onAck: (ack: RealtimeMessageAck) => void;
  onNotification: (notification: RealtimeSystemNotification) => void;
  onConnected: () => void;
};

export class ChatRealtimeClient {
  private readonly session: AuthSession;
  private readonly onState: ClientOptions["onState"];
  private readonly onMessage: ClientOptions["onMessage"];
  private readonly onAck: ClientOptions["onAck"];
  private readonly onNotification: ClientOptions["onNotification"];
  private readonly onConnected: ClientOptions["onConnected"];
  private socket: WebSocket | null = null;
  private stopped = false;
  private opened = false;
  private opening = false;
  private reconnectAttempt = 0;
  private reconnectTimer: number | undefined;
  private heartbeatTimer: number | undefined;
  private pongTimer: number | undefined;
  private connectionGeneration = 0;

  constructor(options: ClientOptions) {
    this.session = options.session;
    this.onState = options.onState;
    this.onMessage = options.onMessage;
    this.onAck = options.onAck;
    this.onNotification = options.onNotification;
    this.onConnected = options.onConnected;
  }

  connect() {
    if (!this.stopped && this.isConnected()) return;
    this.stopped = false;
    const generation = ++this.connectionGeneration;
    void this.open(false, generation);
  }

  isConnected() {
    return this.opened || this.opening || Boolean(this.socket);
  }

  disconnect() {
    this.connectionGeneration += 1;
    this.stopped = true;
    this.opening = false;
    this.opened = false;
    this.clearTimers();
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, "screen closed");
  }

  reconnectNow() {
    const generation = ++this.connectionGeneration;
    this.stopped = false;
    this.opened = false;
    this.opening = false;
    this.reconnectAttempt = 0;
    this.clearTimers();
    const socket = this.socket;
    this.socket = null;
    if (socket && socket.readyState < WebSocket.CLOSING) socket.close(1000, "manual reconnect");
    void this.open(true, generation);
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
        this.onAck({
          clientMessageId: message.clientMessageId || "",
          messageId: `demo-${message.clientMessageId}`,
          sessionId: message.sessionId,
          stage: "persisted",
          createdTime: now,
        });
      }, 420);
      return true;
    }

    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return false;
    this.socket.send(JSON.stringify(message));
    return true;
  }

  private async open(reconnecting: boolean, generation: number) {
    if (this.stopped) return;
    if (generation !== this.connectionGeneration) return;
    if (this.opening || this.opened || this.socket) return;
    this.opening = true;
    this.onState(reconnecting ? "reconnecting" : "connecting");

    if (demoModeEnabled) {
      window.setTimeout(() => {
        if (this.stopped || generation !== this.connectionGeneration) return;
        this.opening = false;
        this.opened = true;
        this.reconnectAttempt = 0;
        this.onState("connected");
        this.onConnected();
      }, reconnecting ? 360 : 520);
      return;
    }

    if (window.chatIMCreateWebSocket && !this.session.nettyUri) {
      this.opening = false;
      this.onState("disconnected");
      return;
    }

    try {
      let socket: WebSocket;
      if (window.chatIMCreateWebSocket) {
        const url = normalizeWebSocketUrl(this.session.nettyUri!);
        socket = window.chatIMCreateWebSocket(url, {
          Authorization: `Bearer ${this.session.accessToken}`,
        });
      } else {
        const credentials = await requestWebSocketTicket(this.session);
        if (this.stopped || generation !== this.connectionGeneration) return;
        this.session.nettyUri = credentials.nettyUri;
        const url = new URL(normalizeWebSocketUrl(credentials.nettyUri));
        url.searchParams.set("ticket", credentials.ticket);
        socket = new WebSocket(url.toString());
      }

      if (this.stopped || generation !== this.connectionGeneration) {
        socket.close(1000, "connection replaced");
        return;
      }
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
          const payload = JSON.parse(String(event.data)) as
            | RealtimeMessage
            | RealtimeSystemNotification
            | { event?: string; data?: RealtimeMessageAck };
          if ("event" in payload && payload.event === "message-ack" && payload.data?.clientMessageId) {
            this.onAck(payload.data);
            return;
          }
          if ("type" in payload && payload.type >= 100 && payload.type <= 199 && "body" in payload) {
            this.onNotification(payload as RealtimeSystemNotification);
            return;
          }
          const message = payload as RealtimeMessage;
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
    } catch (error) {
      if (this.stopped || generation !== this.connectionGeneration) return;
      this.opening = false;
      if (isAuthenticationError(error)) {
        this.onState("auth-failed");
        return;
      }
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
    const generation = this.connectionGeneration;
    this.reconnectTimer = window.setTimeout(() => void this.open(true, generation), delay);
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

export function loadOfflineCursor(userId: AuthSession["userId"]) {
  return window.localStorage.getItem(`${OFFLINE_CURSOR_PREFIX}.${userId}`);
}

export function saveOfflineCursor(userId: AuthSession["userId"], cursor: string) {
  window.localStorage.setItem(`${OFFLINE_CURSOR_PREFIX}.${userId}`, cursor);
}

export function clearOfflineCursor(userId: AuthSession["userId"]) {
  window.localStorage.removeItem(`${OFFLINE_CURSOR_PREFIX}.${userId}`);
}

export async function syncOfflineMessages(session: AuthSession, cursor: string | null, limit = 50) {
  if (demoModeEnabled) {
    await delay(300);
    return {
      items: [],
      nextCursor: cursor,
      hasMore: false,
      serverTime: Date.now(),
    } satisfies OfflineSyncResponse;
  }
  return request<OfflineSyncResponse>(session, "/api/message/offline/sync", {
    cursor,
    limit,
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

export async function fetchMessageStatus(session: AuthSession, clientMessageId: string) {
  if (demoModeEnabled) {
    return {
      clientMessageId,
      status: "persisted",
    } satisfies MessageStatusResult;
  }
  const params = new URLSearchParams({ clientMessageId });
  return requestGet<MessageStatusResult>(session, `/api/message/status?${params.toString()}`);
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
        Authorization: `Bearer ${session.accessToken}`,
        "Refresh-Token": session.refreshToken,
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const payload = (await response.json().catch(() => null)) as ApiResponse<T> | null;
    if (!response.ok || !payload || payload.code !== 200) {
      throw new RealtimeApiError(payload?.message || "消息同步暂时不可用", payload?.code, response.status);
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

async function requestGet<T>(session: AuthSession, path: string) {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 12_000);
  try {
    const response = await fetch(`${API_BASE}${path}`, {
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${session.accessToken}`,
        "Refresh-Token": session.refreshToken,
      },
      signal: controller.signal,
    });
    const payload = (await response.json().catch(() => null)) as ApiResponse<T> | null;
    if (!response.ok || !payload || payload.code !== 200) {
      throw new RealtimeApiError(payload?.message || "消息状态查询失败", payload?.code, response.status);
    }
    return payload.data;
  } catch (error) {
    if (error instanceof RealtimeApiError) throw error;
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new RealtimeApiError("消息状态查询超时");
    }
    throw new RealtimeApiError("暂时无法查询消息状态");
  } finally {
    window.clearTimeout(timeout);
  }
}

async function requestWebSocketTicket(session: AuthSession) {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 12_000);
  try {
    const response = await fetch(`${API_BASE}/api/user/ws-ticket`, {
      method: "POST",
      headers: {
        Accept: "application/json",
        Authorization: `Bearer ${session.accessToken}`,
        "Refresh-Token": session.refreshToken,
      },
      signal: controller.signal,
    });
    const payload = (await response.json().catch(() => null)) as ApiResponse<WebSocketTicketResponse> | null;
    if (!response.ok || !payload || payload.code !== 200) {
      throw new RealtimeApiError(payload?.message || "实时连接凭证获取失败", payload?.code, response.status);
    }
    return payload.data;
  } catch (error) {
    if (error instanceof RealtimeApiError) throw error;
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new RealtimeApiError("实时连接凭证获取超时");
    }
    throw new RealtimeApiError("暂时连接不上实时服务");
  } finally {
    window.clearTimeout(timeout);
  }
}

function isAuthenticationError(error: unknown) {
  if (!(error instanceof RealtimeApiError)) return false;
  return error.status === 401 || (error.code != null && error.code >= 40100 && error.code < 40200);
}

function delay(duration: number) {
  return new Promise((resolve) => window.setTimeout(resolve, duration));
}
