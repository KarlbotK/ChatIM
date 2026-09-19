import { demoModeEnabled, type AuthSession } from "./auth";

export type PreparedChatImage = {
  blob: Blob;
  fileName: string;
  originalName: string;
  previewUrl: string;
  width: number;
  height: number;
  size: number;
};

export type UploadedChatImage = Omit<PreparedChatImage, "blob"> & {
  downloadUrl: string;
};

type UploadUrlResponse = {
  uploadUrl: string;
  downloadUrl: string;
};

type ApiResponse<T> = {
  code: number;
  data: T;
  message?: string;
};

const API_BASE = (import.meta.env.VITE_API_BASE_URL || "http://localhost:10010").replace(/\/$/, "");
const MAX_SOURCE_SIZE = 20 * 1024 * 1024;
const MAX_IMAGE_EDGE = 2048;

export class MediaApiError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "MediaApiError";
  }
}

export async function prepareChatImage(file: File): Promise<PreparedChatImage> {
  if (!/^image\/(jpeg|png|webp)$/i.test(file.type)) {
    throw new MediaApiError("请选择 JPG、PNG 或 WebP 图片");
  }
  if (file.size > MAX_SOURCE_SIZE) {
    throw new MediaApiError("图片不能超过 20 MB");
  }

  const sourceUrl = await readAsDataUrl(file);
  const image = await loadImage(sourceUrl);
  const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(image.naturalWidth, image.naturalHeight));
  const width = Math.max(1, Math.round(image.naturalWidth * scale));
  const height = Math.max(1, Math.round(image.naturalHeight * scale));

  let blob: Blob = file;
  if (scale < 1) {
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext("2d");
    if (!context) throw new MediaApiError("这张图片暂时无法处理");
    context.drawImage(image, 0, 0, width, height);
    const outputType = file.type === "image/png" ? "image/png" : file.type === "image/webp" ? "image/webp" : "image/jpeg";
    blob = await canvasToBlob(canvas, outputType);
  }

  const previewUrl = scale < 1 ? await readAsDataUrl(blob) : sourceUrl;
  return {
    blob,
    fileName: makeObjectName(file.name, blob.type || file.type),
    originalName: file.name,
    previewUrl,
    width,
    height,
    size: blob.size,
  };
}

export async function uploadChatImage(
  session: AuthSession,
  prepared: PreparedChatImage,
  onProgress: (progress: number) => void,
): Promise<UploadedChatImage> {
  if (demoModeEnabled) {
    for (const progress of [18, 42, 68, 91, 100]) {
      await delay(110);
      onProgress(progress);
    }
    return { ...prepared, downloadUrl: prepared.previewUrl };
  }

  const target = await requestUploadUrl(session, prepared.fileName);
  await putObject(target.uploadUrl, prepared.blob, onProgress);
  return {
    fileName: prepared.fileName,
    originalName: prepared.originalName,
    previewUrl: prepared.previewUrl,
    downloadUrl: target.downloadUrl,
    width: prepared.width,
    height: prepared.height,
    size: prepared.size,
  };
}

async function requestUploadUrl(session: AuthSession, fileName: string) {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 12_000);
  try {
    const response = await fetch(`${API_BASE}/api/user/uploadUrl?fileName=${encodeURIComponent(fileName)}`, {
      headers: {
        Accept: "application/json",
        "Access-Token": session.accessToken,
        "Refresh-Token": session.refreshToken,
      },
      signal: controller.signal,
    });
    const payload = await response.json().catch(() => null) as ApiResponse<UploadUrlResponse> | null;
    if (!response.ok || !payload || payload.code !== 200 || !payload.data?.uploadUrl || !payload.data?.downloadUrl) {
      throw new MediaApiError(payload?.message || "暂时无法获取图片上传地址");
    }
    return payload.data;
  } catch (error) {
    if (error instanceof MediaApiError) throw error;
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new MediaApiError("获取上传地址超时，请稍后重试");
    }
    throw new MediaApiError("暂时连接不上图片服务");
  } finally {
    window.clearTimeout(timeout);
  }
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
        reject(new MediaApiError("图片上传失败，请稍后重试"));
      }
    };
    xhr.onerror = () => reject(new MediaApiError("图片上传失败，请检查网络"));
    xhr.ontimeout = () => reject(new MediaApiError("图片上传超时，请稍后重试"));
    xhr.send(blob);
  });
}

function readAsDataUrl(blob: Blob) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new MediaApiError("这张图片暂时无法读取"));
    reader.readAsDataURL(blob);
  });
}

function loadImage(source: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new MediaApiError("这张图片格式无法识别"));
    image.src = source;
  });
}

function canvasToBlob(canvas: HTMLCanvasElement, type: string) {
  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob(
      (blob) => blob ? resolve(blob) : reject(new MediaApiError("图片压缩失败，请重新选择")),
      type,
      type === "image/png" ? undefined : 0.84,
    );
  });
}

function makeObjectName(originalName: string, mimeType: string) {
  const extension = mimeType === "image/png" ? "png" : mimeType === "image/webp" ? "webp" : "jpg";
  const stem = originalName.replace(/\.[^.]+$/, "").replace(/[^a-zA-Z0-9_-]+/g, "-").slice(0, 32) || "image";
  const nonce = typeof crypto.randomUUID === "function" ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `chat/${stem}-${nonce}.${extension}`;
}

function delay(duration: number) {
  return new Promise((resolve) => window.setTimeout(resolve, duration));
}
