export type StorageMode = "native-secure" | "browser-prototype";

type MaybePromise<T> = T | Promise<T>;

export type NativeSecureStorageBridge = {
  getItem: (key: string) => MaybePromise<string | null>;
  setItem: (key: string, value: string) => MaybePromise<void>;
  removeItem: (key: string) => MaybePromise<void>;
};

export type NativeAccountStorageBridge = {
  getItem: (userId: string, key: string) => MaybePromise<string | null>;
  setItem: (userId: string, key: string, value: string) => MaybePromise<void>;
  removeItem: (userId: string, key: string) => MaybePromise<void>;
  clearUser: (userId: string) => MaybePromise<void>;
};

declare global {
  interface Window {
    /** Backed by iOS Keychain or Android Keystore in the native container. */
    chatIMSecureStorage?: NativeSecureStorageBridge;
    /** Backed by the account-scoped SQLite database in the native container. */
    chatIMAccountStorage?: NativeAccountStorageBridge;
  }
}

export function storageMode(): StorageMode {
  return window.chatIMSecureStorage ? "native-secure" : "browser-prototype";
}

export async function readSecureValue(key: string) {
  const bridge = window.chatIMSecureStorage;
  if (bridge) return bridge.getItem(key);
  return window.localStorage.getItem(key);
}

export async function writeSecureValue(key: string, value: string) {
  const bridge = window.chatIMSecureStorage;
  if (bridge) {
    await bridge.setItem(key, value);
    return;
  }
  window.localStorage.setItem(key, value);
}

export async function removeSecureValue(key: string) {
  const bridge = window.chatIMSecureStorage;
  if (bridge) {
    await bridge.removeItem(key);
    return;
  }
  window.localStorage.removeItem(key);
}

export async function readAccountValue(userId: string | number, key: string) {
  const normalizedUserId = String(userId);
  const bridge = window.chatIMAccountStorage;
  if (bridge) return bridge.getItem(normalizedUserId, key);
  return window.localStorage.getItem(accountBrowserKey(normalizedUserId, key));
}

export async function writeAccountValue(userId: string | number, key: string, value: string) {
  const normalizedUserId = String(userId);
  const bridge = window.chatIMAccountStorage;
  if (bridge) {
    await bridge.setItem(normalizedUserId, key, value);
    return;
  }
  window.localStorage.setItem(accountBrowserKey(normalizedUserId, key), value);
}

export async function removeAccountValue(userId: string | number, key: string) {
  const normalizedUserId = String(userId);
  const bridge = window.chatIMAccountStorage;
  if (bridge) {
    await bridge.removeItem(normalizedUserId, key);
    return;
  }
  window.localStorage.removeItem(accountBrowserKey(normalizedUserId, key));
}

export async function clearAccountStorage(userId: string | number) {
  const normalizedUserId = String(userId);
  try {
    if (window.chatIMAccountStorage) {
      await window.chatIMAccountStorage.clearUser(normalizedUserId);
    }
  } finally {
    clearBrowserAccountStorage(normalizedUserId);
  }
}

function clearBrowserAccountStorage(userId: string) {
  // The browser build is only a development prototype. Its account cache is
  // still isolated by userId and removed completely on logout.
  const suffix = `.${userId}`;
  const keysToRemove: string[] = [];
  for (let index = 0; index < window.localStorage.length; index += 1) {
    const key = window.localStorage.key(index);
    if (key?.startsWith("chatim.") && key.endsWith(suffix)) keysToRemove.push(key);
  }
  keysToRemove.forEach((key) => window.localStorage.removeItem(key));
}

function accountBrowserKey(userId: string, key: string) {
  return `${key}.${userId}`;
}
