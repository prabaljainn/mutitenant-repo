"use client";

import { useEffect, useState, type FormEvent } from "react";

import { Field } from "@/components/ui/Field";
import { Modal, ModalBody, ModalFoot } from "@/components/ui/Modal";
import { suggestTenantId } from "@/lib/api/adapters";

export function NewTenantModal({
  open,
  onClose,
  onSubmit,
  submitting,
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (input: { id: string; name: string }) => void;
  submitting?: boolean;
}) {
  const [name, setName] = useState("");
  const [id, setId] = useState("");
  const [idTouched, setIdTouched] = useState(false);
  const [err, setErr] = useState("");

  // Auto-derive a slug while the user types, until they manually edit the id.
  useEffect(() => {
    if (!idTouched) setId(suggestTenantId(name));
  }, [name, idTouched]);

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!name.trim()) return setErr("Name is required.");
    if (!/^[a-z0-9][a-z0-9-]{1,23}$/.test(id))
      return setErr("ID must be lowercase letters/numbers/hyphens, 2–24 chars.");
    onSubmit({ id, name: name.trim() });
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
          <Field label="Name">
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
          <Field
            label="Tenant ID"
            hint="Lowercase slug used in URLs and Mongo database names. Cannot be changed later."
          >
            <input
              className="input mono"
              value={id}
              onChange={(e) => {
                setId(e.target.value);
                setIdTouched(true);
                setErr("");
              }}
              placeholder="acme-drones"
            />
          </Field>
          <Field
            label="Host"
            hint={
              <>
                All tenants share the same host. Members sign in at{" "}
                <span className="mono">cloudgcs.io</span>; super-admins use{" "}
                <span className="mono">admin.cloudgcs.io</span>.
              </>
            }
          >
            <input className="input mono" readOnly value="cloudgcs.io" />
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
