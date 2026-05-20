"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";

type ToastKind = "info" | "success" | "error";
type Toast = { id: number; msg: string; kind: ToastKind };

type ToastCtx = {
  notify: (msg: string, kind?: ToastKind) => void;
};

const Ctx = createContext<ToastCtx | null>(null);

const AUTO_DISMISS_MS = 2600;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);

  const notify = useCallback((msg: string, kind: ToastKind = "info") => {
    const id = Date.now() + Math.random();
    setToasts((prev) => [...prev, { id, msg, kind }]);
  }, []);

  useEffect(() => {
    if (toasts.length === 0) return;
    const first = toasts[0];
    const h = window.setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== first.id));
    }, AUTO_DISMISS_MS);
    return () => window.clearTimeout(h);
  }, [toasts]);

  return (
    <Ctx.Provider value={{ notify }}>
      {children}
      <div className="toast-stack">
        {toasts.map((t) => (
          <div key={t.id} className={`toast toast-${t.kind}`} role="status">
            <Icon d={t.kind === "success" ? Icons.check : Icons.bell} size={14} />
            <span>{t.msg}</span>
          </div>
        ))}
      </div>
    </Ctx.Provider>
  );
}

export function useToast(): ToastCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useToast() must be used inside <ToastProvider>");
  return ctx;
}
