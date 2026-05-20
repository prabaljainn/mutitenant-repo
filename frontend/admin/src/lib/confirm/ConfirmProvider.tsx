"use client";

// Promise-based confirmation dialog — replaces every `window.confirm()`
// call site in the admin app with a styled, theme-aware modal that
// matches the rest of the design system.
//
// Usage:
//
//   const confirm = useConfirm();
//   const ok = await confirm({
//     title: "Sign out of 3 other devices?",
//     body: "They'll be signed out within the next 15 minutes.",
//     confirmLabel: "Sign out everywhere else",
//     tone: "danger",
//   });
//   if (ok) doTheThing();
//
// The provider keeps a tiny stack so concurrent confirms don't clobber
// each other (rare in practice but trivial to support and avoids "the
// second await resolves the first dialog's promise" bugs).

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";

export type ConfirmOptions = {
  title: ReactNode;
  body?: ReactNode;
  confirmLabel?: string;
  cancelLabel?: string;
  /** "danger" gives the confirm button the red treatment; defaults to neutral. */
  tone?: "neutral" | "danger";
};

type ConfirmCtx = (opts: ConfirmOptions) => Promise<boolean>;

const Ctx = createContext<ConfirmCtx | null>(null);

type PendingConfirm = ConfirmOptions & {
  id: number;
  resolve: (v: boolean) => void;
};

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [pending, setPending] = useState<PendingConfirm[]>([]);
  const idSeq = useRef(0);

  const confirm = useCallback<ConfirmCtx>((opts) => {
    return new Promise<boolean>((resolve) => {
      setPending((p) => [...p, { ...opts, id: ++idSeq.current, resolve }]);
    });
  }, []);

  // Only the topmost dialog is interactive; older ones stay in the stack
  // but are visually hidden until the top one settles.
  const top = pending[pending.length - 1] ?? null;

  function settle(id: number, value: boolean) {
    setPending((p) => {
      const entry = p.find((e) => e.id === id);
      entry?.resolve(value);
      return p.filter((e) => e.id !== id);
    });
  }

  return (
    <Ctx.Provider value={confirm}>
      {children}
      {top && (
        <ConfirmDialog
          key={top.id}
          opts={top}
          onCancel={() => settle(top.id, false)}
          onConfirm={() => settle(top.id, true)}
        />
      )}
    </Ctx.Provider>
  );
}

export function useConfirm(): ConfirmCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error("useConfirm() must be used inside <ConfirmProvider>");
  return ctx;
}

function ConfirmDialog({
  opts,
  onCancel,
  onConfirm,
}: {
  opts: ConfirmOptions;
  onCancel: () => void;
  onConfirm: () => void;
}) {
  const confirmBtnRef = useRef<HTMLButtonElement | null>(null);

  // Move focus into the dialog so keyboard users land on the primary
  // action. Escape cancels; Enter on the confirm button (its default
  // when focused) confirms.
  useEffect(() => {
    confirmBtnRef.current?.focus();
  }, []);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") {
        e.preventDefault();
        onCancel();
      }
    }
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onCancel]);

  const danger = opts.tone === "danger";
  // Lock icon reads more naturally as "sensitive action" than the bell
  // we had before; bell screamed "notification" to most operators
  // testing the flow.
  const iconPath = danger ? Icons.lock : Icons.check;

  return (
    <div className="modal-scrim" role="presentation" onClick={onCancel}>
      <div
        className="modal confirm-modal"
        role="dialog"
        aria-modal
        aria-labelledby="confirm-modal-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="confirm-modal-head">
          <div
            className={"confirm-modal-icon" + (danger ? " confirm-modal-icon--danger" : "")}
            aria-hidden
          >
            <Icon d={iconPath} size={18} />
          </div>
          <div className="confirm-modal-text">
            <div className="modal-title" id="confirm-modal-title">
              {opts.title}
            </div>
            {opts.body && <div className="modal-sub">{opts.body}</div>}
          </div>
        </div>
        <div className="confirm-modal-foot">
          <button type="button" className="btn btn-ghost" onClick={onCancel}>
            {opts.cancelLabel ?? "Cancel"}
          </button>
          <button
            type="button"
            ref={confirmBtnRef}
            className={"btn " + (danger ? "btn-danger-solid" : "btn-primary")}
            onClick={onConfirm}
          >
            {opts.confirmLabel ?? "Confirm"}
          </button>
        </div>
      </div>
    </div>
  );
}
