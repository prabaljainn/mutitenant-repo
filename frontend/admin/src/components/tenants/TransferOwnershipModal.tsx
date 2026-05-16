"use client";

import { useEffect, useState } from "react";

import { Modal, ModalBody, ModalFoot } from "@/components/ui/Modal";
import type { Member } from "@/lib/api/types";

export function TransferOwnershipModal({
  open,
  currentOwnerName,
  candidates,
  onClose,
  onSubmit,
  submitting,
}: {
  open: boolean;
  currentOwnerName: string | null;
  candidates: Member[];
  onClose: () => void;
  onSubmit: (userId: string) => void;
  submitting?: boolean;
}) {
  const [pick, setPick] = useState("");

  useEffect(() => {
    if (open) setPick("");
  }, [open]);

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Transfer ownership"
      sub={
        currentOwnerName
          ? `Move ownership from ${currentOwnerName} to another admin. The previous owner stays as a plain Admin.`
          : "Pick the user who should own this tenant."
      }
    >
      <ModalBody>
        {candidates.length === 0 ? (
          <div className="muted" style={{ padding: 12 }}>
            No eligible new owner. Promote an active Member to <strong>Admin</strong> first, then
            try again.
          </div>
        ) : (
          <div className="field">
            <label className="field-label">New owner</label>
            <select
              autoFocus
              className="select"
              value={pick}
              onChange={(e) => setPick(e.target.value)}
              disabled={submitting}
            >
              <option value="">Pick an admin…</option>
              {candidates.map((m) => (
                <option key={m.userId} value={m.userId}>
                  {m.name} ({m.email})
                </option>
              ))}
            </select>
            <span className="field-hint">
              Only active tenant admins (excluding the current owner) appear here.
            </span>
          </div>
        )}
      </ModalBody>
      <ModalFoot>
        <button type="button" className="btn" onClick={onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="btn btn-primary"
          disabled={!pick || submitting || candidates.length === 0}
          onClick={() => pick && onSubmit(pick)}
        >
          {submitting ? "Transferring…" : "Transfer ownership"}
        </button>
      </ModalFoot>
    </Modal>
  );
}
