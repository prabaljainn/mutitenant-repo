"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

export type Theme = "light" | "sand" | "forest" | "dark" | "plum";
export type Density = "spacious" | "balanced" | "dense";
export type SidebarMode = "labelled" | "iconOnly" | "collapsible";
export type TableMode = "lined" | "striped" | "card";

export type Tweaks = {
  theme: Theme;
  density: Density;
  sidebar: SidebarMode;
  table: TableMode;
};

const DEFAULTS: Tweaks = {
  theme: "light",
  density: "balanced",
  sidebar: "labelled",
  table: "lined",
};

const STORAGE_KEY = "cloudgcs.theme";

type ThemeCtx = {
  tweaks: Tweaks;
  set: <K extends keyof Tweaks>(key: K, value: Tweaks[K]) => void;
  reset: () => void;
};

const Ctx = createContext<ThemeCtx | null>(null);

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [tweaks, setTweaks] = useState<Tweaks>(DEFAULTS);

  // Hydrate from localStorage exactly once. We deliberately don't render the
  // initial children server-side with persisted values — that would require
  // server-readable cookies, and the design system uses CSS custom properties
  // that swap instantly so a one-frame flash to the default theme is fine.
  useEffect(() => {
    if (typeof window === "undefined") return;
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return;
      const parsed = JSON.parse(raw) as Partial<Tweaks>;
      setTweaks({ ...DEFAULTS, ...parsed });
    } catch {
      /* ignore corrupt JSON */
    }
  }, []);

  useEffect(() => {
    if (typeof window === "undefined") return;
    localStorage.setItem(STORAGE_KEY, JSON.stringify(tweaks));
  }, [tweaks]);

  const set = useCallback(<K extends keyof Tweaks>(key: K, value: Tweaks[K]) => {
    setTweaks((prev) => ({ ...prev, [key]: value }));
  }, []);

  const reset = useCallback(() => setTweaks(DEFAULTS), []);

  const value = useMemo<ThemeCtx>(() => ({ tweaks, set, reset }), [tweaks, set, reset]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useTheme(): ThemeCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useTheme() must be used inside <ThemeProvider>");
  return ctx;
}
