"use client";

import { useState, type FormEvent } from "react";

import { Field } from "@/components/ui/Field";
import { Modal, ModalBody, ModalFoot } from "@/components/ui/Modal";

export function NewTenantModal({
  open,
  onClose,
  onSubmit,
  submitting,
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (input: { name: string }) => void;
  submitting?: boolean;
}) {
  const [name, setName] = useState("");
  const [err, setErr] = useState("");

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!name.trim()) return setErr("Name is required.");
    onSubmit({ name: name.trim() });
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="New tenant"
      sub="Create a new workspace. You can invite members afterwards."
    >
      <form onSubmit={submit}>
        <ModalBody>
          <Field
            label="Name"
            hint="The tenant ID is generated from the name. You can edit it later only by recreating the tenant."
          >
            <input
              autoFocus
              className="input"
              value={name}
              onChange={(e) => {
                setName(e.target.value);
                setErr("");
              }}
              placeholder="Acme Drones"
            />
          </Field>
          {err && (
            <div className="field-hint" style={{ color: "var(--bad)" }}>
              {err}
            </div>
          )}
        </ModalBody>
        <ModalFoot>
          <button type="button" className="btn" onClick={onClose}>
            Cancel
          </button>
          <button className="btn btn-primary" disabled={submitting}>
            {submitting ? "Creating…" : "Create tenant"}
          </button>
        </ModalFoot>
      </form>
    </Modal>
  );
}
