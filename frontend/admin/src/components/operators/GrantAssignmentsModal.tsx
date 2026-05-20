"use client";

import { useEffect, useState } from "react";

import { Modal, ModalBody, ModalFoot } from "@/components/ui/Modal";
import type { Tenant } from "@/lib/api/types";

export function GrantAssignmentsModal({
  open,
  grantable,
  onClose,
  onSubmit,
  submitting,
}: {
  open: boolean;
  grantable: Tenant[];
  onClose: () => void;
  onSubmit: (tenantIds: string[]) => void;
  submitting?: boolean;
}) {
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [filter, setFilter] = useState("");

  // Reset picks each time the modal opens.
  useEffect(() => {
    if (open) {
      setPicked(new Set());
      setFilter("");
    }
  }, [open]);

  function toggle(id: string) {
    setPicked((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function selectAll() {
    setPicked(new Set(filtered.map((t) => t.id)));
  }
  function clearAll() {
    setPicked(new Set());
  }

  function submit() {
    if (picked.size === 0) return;
    onSubmit(Array.from(picked));
  }

  const filtered = grantable.filter((t) => {
    const q = filter.trim().toLowerCase();
    if (!q) return true;
    return t.name.toLowerCase().includes(q) || t.id.toLowerCase().includes(q);
  });

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Grant tenant access"
      sub="Pick one or more tenants this operator should be able to act in."
    >
      <ModalBody>
        {grantable.length === 0 ? (
          <div className="muted" style={{ padding: 16, textAlign: "center" }}>
            All tenants are already assigned.
          </div>
        ) : (
          <>
            <div style={{ display: "flex", gap: 8, marginBottom: 10 }}>
              <input
                autoFocus
                className="input"
                placeholder="Filter tenants…"
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
                style={{ flex: 1 }}
              />
              <button type="button" className="btn btn-sm" onClick={selectAll}>
                Select all
              </button>
              <button type="button" className="btn btn-sm" onClick={clearAll}>
                Clear
              </button>
            </div>
            <div
              style={{
                maxHeight: 320,
                overflowY: "auto",
                border: "1px solid var(--border)",
                borderRadius: 6,
              }}
            >
              {filtered.length === 0 ? (
                <div className="muted" style={{ padding: 16, textAlign: "center" }}>
                  No tenants match &ldquo;{filter}&rdquo;.
                </div>
              ) : (
                filtered.map((t) => {
                  const checked = picked.has(t.id);
                  return (
                    <label
                      key={t.id}
                      style={{
                        display: "flex",
                        alignItems: "center",
                        gap: 10,
                        padding: "8px 12px",
                        borderBottom: "1px solid var(--border)",
                        cursor: "pointer",
                      }}
                    >
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={() => toggle(t.id)}
                      />
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div className="user-cell-name">{t.name}</div>
                        <div className="user-cell-email mono">{t.id}</div>
                      </div>
                    </label>
                  );
                })
              )}
            </div>
            <div className="field-hint" style={{ marginTop: 8 }}>
              {picked.size} selected
            </div>
          </>
        )}
      </ModalBody>
      <ModalFoot>
        <button type="button" className="btn" onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="btn btn-primary"
          disabled={picked.size === 0 || submitting}
          onClick={submit}
        >
          {submitting
            ? "Granting…"
            : `Grant access${picked.size ? ` (${picked.size})` : ""}`}
        </button>
      </ModalFoot>
    </Modal>
  );
}
