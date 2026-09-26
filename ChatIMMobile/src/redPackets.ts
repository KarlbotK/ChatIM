import { demoModeEnabled, type AuthSession } from "./auth";

export type RedPacketType = 0 | 1;

export type RedPacketDraft = {
  redPacketType: RedPacketType;
  totalAmount: number;
  totalCount: number;
  wrapperText: string;
};

export type SendRedPacketPayload = RedPacketDraft & {
  sessionId: string;
  receiverId?: string | null;
  sessionType: 0 | 1;
  clientMessageId: string;
};

export type RedPacketSendResult = {
  redPacketId: string;
  messageId: string;
};

export type RedPacketReceiveRecord = {
  receiverId: string;
  receiverNickname?: string | null;
  receiverAvatar?: string | null;
  amount: number;
  receivedAt: string | number;
};

export type RedPacketDetail = {
  redPacketId: string;
  senderId: string;
  senderNickname?: string | null;
  senderAvatar?: string | null;
  sessionId: string;
  sessionType: number;
  redPacketWrapperText: string;
  redPacketType: RedPacketType;
  totalAmount: number;
  totalCount: number;
  receivedCount: number;
  receivedAmount: number;
  status: number;
  createdTime: string | number;
  receiveRecords: RedPacketReceiveRecord[];
};

export type RedPacketReceiveResult = {
  status: number;
  message: string;
  amount?: number | null;
};

type ApiResponse<T> = {
  code: number;
  data: T;
  message?: string;
};

const API_BASE = (import.meta.env.VITE_API_BASE_URL || "http://localhost:10010").replace(/\/$/, "");
const DEMO_PACKET_PREFIX = "chatim.demo.red-packets.v1";

export class RedPacketApiError extends Error {
  readonly code?: number;

  constructor(message: string, code?: number) {
    super(message);
    this.name = "RedPacketApiError";
    this.code = code;
  }
}

export async function sendRedPacket(
  session: AuthSession,
  payload: SendRedPacketPayload,
): Promise<RedPacketSendResult> {
  validateDraft(payload);
  if (demoModeEnabled) {
    await delay(420);
    const redPacketId = `demo-packet-${Date.now()}`;
    const messageId = `demo-message-${Date.now()}`;
    const detail: RedPacketDetail = {
      redPacketId,
      senderId: String(session.userId),
      senderNickname: session.nickname,
      senderAvatar: session.avatar,
      sessionId: payload.sessionId,
      sessionType: payload.sessionType,
      redPacketWrapperText: payload.wrapperText,
      redPacketType: payload.redPacketType,
      totalAmount: payload.totalAmount,
      totalCount: payload.totalCount,
      receivedCount: 0,
      receivedAmount: 0,
      status: 0,
      createdTime: Date.now(),
      receiveRecords: [],
    };
    writeDemoPacket(session.userId, detail);
    return { redPacketId, messageId };
  }

  const result = await request<RedPacketSendResult>(session, "/api/chat/redPacket/send", {
    sessionId: payload.sessionId,
    receiverId: payload.receiverId || null,
    senderId: session.userId,
    type: 3,
    sessionType: payload.sessionType,
    clientMessageId: payload.clientMessageId,
    body: {
      redPacketType: payload.redPacketType,
      totalAmount: payload.totalAmount,
      totalCount: payload.totalCount,
      redPacketWrapperText: payload.wrapperText,
    },
  });
  return {
    redPacketId: String(result.redPacketId),
    messageId: String(result.messageId),
  };
}

export async function receiveRedPacket(
  session: AuthSession,
  redPacketId: string,
): Promise<RedPacketReceiveResult> {
  if (demoModeEnabled) {
    await delay(360);
    const detail = readDemoPacket(session.userId, redPacketId);
    if (!detail) return { status: -1, message: "体验红包不存在" };
    const existing = detail.receiveRecords.find((record) => record.receiverId === String(session.userId));
    if (existing) return { status: detail.status, message: "你已经领取过这个体验红包", amount: existing.amount };
    if (detail.status === 1) return { status: 1, message: "体验红包已被领完" };
    if (detail.status === 2) return { status: 2, message: "体验红包已过期" };

    const remainingCount = Math.max(1, detail.totalCount - detail.receivedCount);
    const remainingAmount = Math.max(0, detail.totalAmount - detail.receivedAmount);
    const amount = detail.redPacketType === 0 || remainingCount === 1
      ? roundVirtualAmount(remainingAmount / remainingCount)
      : roundVirtualAmount(Math.max(0.01, Math.min(remainingAmount, remainingAmount * (0.35 + Math.random() * 0.45))));
    detail.receiveRecords.unshift({
      receiverId: String(session.userId),
      receiverNickname: session.nickname,
      receiverAvatar: session.avatar,
      amount,
      receivedAt: Date.now(),
    });
    detail.receivedCount += 1;
    detail.receivedAmount = roundVirtualAmount(detail.receivedAmount + amount);
    if (detail.receivedCount >= detail.totalCount) detail.status = 1;
    writeDemoPacket(session.userId, detail);
    return { status: detail.status, message: "领取成功，仅作为体验展示", amount };
  }

  return request<RedPacketReceiveResult>(session, "/api/chat/redPacket/receive", {
    userId: session.userId,
    redPacketId,
  });
}

export async function fetchRedPacketDetail(session: AuthSession, redPacketId: string) {
  if (demoModeEnabled) {
    await delay(260);
    const detail = readDemoPacket(session.userId, redPacketId);
    if (!detail) throw new RedPacketApiError("暂时找不到这个体验红包");
    return detail;
  }
  const params = new URLSearchParams({ redPacketId, pageNum: "1", pageSize: "30" });
  const result = await requestGet<RedPacketDetail>(session, `/api/chat/redPacket/?${params.toString()}`);
  return normalizeDetail(result);
}

function validateDraft(payload: RedPacketDraft) {
  if (!Number.isFinite(payload.totalAmount) || payload.totalAmount <= 0) {
    throw new RedPacketApiError("请输入大于 0 的体验金额");
  }
  if (!Number.isInteger(payload.totalCount) || payload.totalCount <= 0) {
    throw new RedPacketApiError("红包数量必须是正整数");
  }
  if (payload.totalAmount < payload.totalCount * 0.01) {
    throw new RedPacketApiError("每个体验红包至少需要 0.01 点");
  }
  if (payload.totalAmount / payload.totalCount > 200) {
    throw new RedPacketApiError("单个体验红包不能超过 200 点");
  }
}

async function request<T>(session: AuthSession, path: string, body: Record<string, unknown>) {
  return requestApi<T>(session, path, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

async function requestGet<T>(session: AuthSession, path: string) {
  return requestApi<T>(session, path, { method: "GET" });
}

async function requestApi<T>(session: AuthSession, path: string, init: RequestInit) {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 12_000);
  try {
    const response = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: {
        Accept: "application/json",
        ...(init.body ? { "Content-Type": "application/json" } : {}),
        Authorization: `Bearer ${session.accessToken}`,
        "Access-Token": session.accessToken,
        "Refresh-Token": session.refreshToken,
      },
      signal: controller.signal,
    });
    const payload = await response.json().catch(() => null) as ApiResponse<T> | null;
    if (!response.ok || !payload || payload.code !== 200 || payload.data == null) {
      throw new RedPacketApiError(payload?.message || "体验红包服务暂时不可用", payload?.code);
    }
    return payload.data;
  } catch (error) {
    if (error instanceof RedPacketApiError) throw error;
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new RedPacketApiError("体验红包请求超时，请稍后重试");
    }
    throw new RedPacketApiError("暂时连接不上体验红包服务");
  } finally {
    window.clearTimeout(timeout);
  }
}

function normalizeDetail(detail: RedPacketDetail): RedPacketDetail {
  return {
    ...detail,
    redPacketId: String(detail.redPacketId),
    senderId: String(detail.senderId),
    sessionId: String(detail.sessionId),
    totalAmount: Number(detail.totalAmount),
    receivedAmount: Number(detail.receivedAmount),
    receiveRecords: (detail.receiveRecords || []).map((record) => ({
      ...record,
      receiverId: String(record.receiverId),
      amount: Number(record.amount),
    })),
  };
}

function demoPacketKey(userId: AuthSession["userId"]) {
  return `${DEMO_PACKET_PREFIX}.${userId}`;
}

function readDemoPackets(userId: AuthSession["userId"]) {
  try {
    return JSON.parse(window.localStorage.getItem(demoPacketKey(userId)) || "{}") as Record<string, RedPacketDetail>;
  } catch {
    return {};
  }
}

function readDemoPacket(userId: AuthSession["userId"], redPacketId: string) {
  return readDemoPackets(userId)[redPacketId] || null;
}

function writeDemoPacket(userId: AuthSession["userId"], detail: RedPacketDetail) {
  const packets = readDemoPackets(userId);
  packets[detail.redPacketId] = detail;
  window.localStorage.setItem(demoPacketKey(userId), JSON.stringify(packets));
}

function roundVirtualAmount(value: number) {
  return Math.round(value * 100) / 100;
}

function delay(ms: number) {
  return new Promise((resolve) => window.setTimeout(resolve, ms));
}
