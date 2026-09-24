import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { ApiError, api } from "./api";

export interface Me {
  id: string;
  email: string;
  displayName: string | null;
  role: "HOST" | "ADMIN";
  googleLinked: boolean;
}

interface AuthState {
  me: Me | null;
  loading: boolean;
  refresh: () => Promise<Me | null>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthState | null>(null);

/** The signed-in host, or null for guests (401). */
async function loadMe(): Promise<Me | null> {
  try {
    return await api<Me>("/api/me");
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return null;
    throw e;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    const data = await loadMe();
    setMe(data);
    setLoading(false);
    return data;
  }, []);

  const logout = useCallback(async () => {
    await api<void>("/api/auth/logout", { method: "POST" });
    setMe(null);
  }, []);

  useEffect(() => {
    let alive = true;
    loadMe().then(
      (data) => {
        if (!alive) return;
        setMe(data);
        setLoading(false);
      },
      () => {
        if (alive) setLoading(false);
      },
    );
    return () => {
      alive = false;
    };
  }, []);

  const value = useMemo(() => ({ me, loading, refresh, logout }), [me, loading, refresh, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth must be used inside AuthProvider");
  return ctx;
}
