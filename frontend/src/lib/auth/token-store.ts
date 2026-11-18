import type { TokenResponse, UserResponse } from "@/lib/api/types";

const STORAGE_KEY = "vf.auth.session.v1";
const REFRESH_SKEW_MS = 30_000;

export interface StoredAuthSession {
  accessToken: string;
  refreshToken: string;
  expiresAtMs: number;
  userId: string;
  email: string;
  user?: UserResponse;
}

type SessionListener = (session: StoredAuthSession | null) => void;

let memorySession: StoredAuthSession | null = null;
let hydrated = false;
let refreshInFlight: Promise<StoredAuthSession | null> | null = null;
const listeners = new Set<SessionListener>();

function isBrowser(): boolean {
  return typeof window !== "undefined";
}

function readFromStorage(): StoredAuthSession | null {
  if (!isBrowser()) {
    return null;
  }
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const parsed = JSON.parse(raw) as Partial<StoredAuthSession>;
    if (
      typeof parsed.accessToken !== "string" ||
      typeof parsed.refreshToken !== "string" ||
      typeof parsed.expiresAtMs !== "number" ||
      typeof parsed.userId !== "string"
    ) {
      return null;
    }
    return {
      accessToken: parsed.accessToken,
      refreshToken: parsed.refreshToken,
      expiresAtMs: parsed.expiresAtMs,
      userId: parsed.userId,
      email: typeof parsed.email === "string" ? parsed.email : "",
      user: parsed.user,
    };
  } catch {
    return null;
  }
}

function writeToStorage(session: StoredAuthSession | null): void {
  if (!isBrowser()) {
    return;
  }
  try {
    if (session) {
      window.localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } else {
      window.localStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // Ignore storage quota or private browsing errors; memory session remains active.
  }
}

function notifyListeners(session: StoredAuthSession | null): void {
  for (const listener of listeners) {
    listener(session);
  }
}

if (isBrowser()) {
  window.addEventListener("storage", (event) => {
    if (event.key !== STORAGE_KEY) {
      return;
    }
    memorySession = readFromStorage();
    hydrated = true;
    notifyListeners(memorySession);
  });
}

export function getStoredSession(): StoredAuthSession | null {
  if (!hydrated && isBrowser()) {
    memorySession = readFromStorage();
    hydrated = true;
  }
  return memorySession;
}

export function setStoredSession(
  token: TokenResponse,
  user?: UserResponse
): StoredAuthSession {
  const existing = getStoredSession();
  const next: StoredAuthSession = {
    accessToken: token.accessToken,
    refreshToken: token.refreshToken,
    expiresAtMs: Date.now() + Math.max(token.expiresIn, 0) * 1000,
    userId: token.userId,
    email: token.email,
    user: user ?? existing?.user,
  };
  memorySession = next;
  hydrated = true;
  writeToStorage(next);
  notifyListeners(next);
  return next;
}

export function updateStoredUser(user: UserResponse): void {
  const current = getStoredSession();
  if (!current) {
    return;
  }
  const next: StoredAuthSession = {
    ...current,
    user,
    email: user.email,
    userId: user.id,
  };
  memorySession = next;
  writeToStorage(next);
  notifyListeners(next);
}

export function clearStoredSession(): void {
  memorySession = null;
  hydrated = true;
  writeToStorage(null);
  notifyListeners(null);
}

export function subscribeSession(listener: SessionListener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function isAccessTokenFresh(session: StoredAuthSession | null): boolean {
  if (!session?.accessToken) {
    return false;
  }
  return session.expiresAtMs - Date.now() > REFRESH_SKEW_MS;
}

export async function refreshAuthSession(
  fetcher: (refreshToken: string) => Promise<TokenResponse | null>
): Promise<StoredAuthSession | null> {
  if (refreshInFlight) {
    return refreshInFlight;
  }

  refreshInFlight = (async () => {
    // Re-read storage in case another tab already rotated the refresh token.
    const latest = readFromStorage() ?? getStoredSession();
    if (!latest?.refreshToken) {
      clearStoredSession();
      return null;
    }
    if (isAccessTokenFresh(latest) && memorySession?.refreshToken !== latest.refreshToken) {
      memorySession = latest;
      hydrated = true;
      notifyListeners(latest);
      return latest;
    }

    try {
      const token = await fetcher(latest.refreshToken);
      if (!token) {
        clearStoredSession();
        return null;
      }
      return setStoredSession(token, latest.user);
    } catch {
      clearStoredSession();
      return null;
    }
  })().finally(() => {
    refreshInFlight = null;
  });

  return refreshInFlight;
}
