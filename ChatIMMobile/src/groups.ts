import { demoModeEnabled, type AuthSession } from "./auth";
import type { PreparedChatImage } from "./media";

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

export type SessionParticipant = {
  userId: string;
  nickname: string;
  avatar?: string | null;
  description?: string | null;
};

export type SessionDetail = {
  sessionId: string;
  sessionType: number;
  name: string;
  avatar?: string | null;
  announcement?: string | null;
  avatarObjectName?: string | null;
  peer?: SessionParticipant | null;
  ownerId?: string | null;
  memberCount?: number | null;
  currentUserRole?: number | null;
  currentUserMember: boolean;
  pinned: boolean;
  muted: boolean;
  lastReadMessageId?: string | null;
  createdTime: number;
  updatedTime: number;
};

export type GroupMemberResult = {
  userId: string;
  nickname: string;
  groupNickname?: string | null;
  avatar?: string | null;
  description?: string | null;
  role: number;
  status: number;
  joinedTime: number;
};

export type GroupMemberPage = {
  items: GroupMemberResult[];
  nextCursor?: string | null;
  hasMore: boolean;
  serverTime: number;
};

export type GroupProfileResult = {
  sessionId: string;
  name?: string;
  announcement?: string | null;
  avatar?: string | null;
  avatarObjectName?: string | null;
  updatedTime?: number;
};

type GroupAvatarUploadTarget = {
  uploadUrl: string;
  downloadUrl: string;
  objectName: string;
  expiresInSeconds: number;
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
        Authorization: `Bearer ${session.accessToken}`,
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
      inviteeIds,
    }),
  });
}

export async function fetchSessionDetail(session: AuthSession, sessionId: string) {
  return request<SessionDetail>(session, `/api/session/${encodeURIComponent(sessionId)}`, {
    method: "GET",
  });
}

export async function fetchGroupMembers(
  session: AuthSession,
  sessionId: string,
  cursor: string | null = null,
  limit = 50,
) {
  const query = new URLSearchParams({ limit: String(limit) });
  if (cursor) query.set("cursor", cursor);
  return request<GroupMemberPage>(
    session,
    `/api/group/${encodeURIComponent(sessionId)}/members?${query}`,
    { method: "GET" },
  );
}

export async function fetchAllGroupMembers(session: AuthSession, sessionId: string) {
  const members: GroupMemberResult[] = [];
  let cursor: string | null = null;
  let pageCount = 0;
  do {
    const page = await fetchGroupMembers(session, sessionId, cursor);
    members.push(...page.items);
    pageCount += 1;
    if (!page.hasMore) break;
    const nextCursor = page.nextCursor || null;
    if (!nextCursor || nextCursor === cursor || pageCount >= 100) {
      throw new GroupApiError("群成员列表没有完整返回，请稍后重试");
    }
    cursor = nextCursor;
  } while (true);
  return members;
}

export async function updateGroupProfile(
  session: AuthSession,
  sessionId: string,
  changes: { name?: string; announcement?: string },
) {
  if (demoModeEnabled) {
    await demoDelay(420);
    return {
      sessionId,
      ...(changes.name !== undefined ? { name: changes.name.trim() } : {}),
      ...(changes.announcement !== undefined
        ? { announcement: changes.announcement.trim() || null }
        : {}),
      updatedTime: Date.now(),
    } satisfies GroupProfileResult;
  }
  return request<GroupProfileResult>(session, `/api/group/${encodeURIComponent(sessionId)}`, {
    method: "PATCH",
    body: JSON.stringify(changes),
  });
}

export async function uploadGroupAvatar(
  session: AuthSession,
  sessionId: string,
  prepared: PreparedChatImage,
  onProgress: (progress: number) => void,
) {
  if (demoModeEnabled) {
    for (const progress of [24, 51, 78, 100]) {
      await demoDelay(90);
      onProgress(progress);
    }
    return {
      sessionId,
      avatar: URL.createObjectURL(prepared.blob),
      avatarObjectName: prepared.fileName,
      updatedTime: Date.now(),
    } satisfies GroupProfileResult;
  }

  const query = new URLSearchParams({ fileName: prepared.originalName || prepared.fileName });
  const target = await request<GroupAvatarUploadTarget>(
    session,
    `/api/group/${encodeURIComponent(sessionId)}/avatar/upload-url?${query}`,
    { method: "GET" },
  );
  await putObject(target.uploadUrl, prepared.blob, onProgress);
  return request<GroupProfileResult>(session, `/api/group/${encodeURIComponent(sessionId)}/avatar`, {
    method: "PUT",
    body: JSON.stringify({ objectName: target.objectName }),
  });
}

function putObject(uploadUrl: string, blob: Blob, onProgress: (progress: number) => void) {
  return new Promise<void>((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("PUT", uploadUrl, true);
    xhr.timeout = 30_000;
    xhr.setRequestHeader("Content-Type", blob.type || "application/octet-stream");
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable) onProgress(Math.max(1, Math.round((event.loaded / event.total) * 100)));
    };
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        onProgress(100);
        resolve();
      } else {
        reject(new GroupApiError("群头像上传失败，请稍后重试"));
      }
    };
    xhr.onerror = () => reject(new GroupApiError("群头像上传失败，请检查网络"));
    xhr.ontimeout = () => reject(new GroupApiError("群头像上传超时，请稍后重试"));
    xhr.send(blob);
  });
}
