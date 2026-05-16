"use client";

import { useState, type FormEvent } from "react";

import { Field } from "@/components/ui/Field";
import { Modal, ModalBody, ModalFoot } from "@/components/ui/Modal";
import type { OperatorRole } from "@/lib/api/types";

const ROLES: OperatorRole[] = ["Admin", "Support"];

export function NewOperatorModal({
  open,
  onClose,
  onSubmit,
  submitting,
}: {
  open: boolean;
  onClose: () => void;
  onSubmit: (input: {
    email: string;
    firstName: string;
    lastName: string;
    role: OperatorRole;
  }) => void;
  submitting?: boolean;
}) {
  const [email, setEmail] = useState("");
  const [firstName, setFirstName] = useState("");
  const [lastName, setLastName] = useState("");
  const [role, setRole] = useState<OperatorRole>("Support");
  const [err, setErr] = useState("");

  function submit(e: FormEvent) {
    e.preventDefault();
    if (!/^\S+@\S+\.\S+$/.test(email)) return setErr("Enter a valid email address.");
    if (!firstName.trim() || !lastName.trim())
      return setErr("First and last name are required.");
    onSubmit({
      email: email.trim(),
      firstName: firstName.trim(),
      lastName: lastName.trim(),
      role,
    });
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Invite operator"
      sub="Operators are Orochiverse staff. They sign in once, then get tenant access via assignments."
    >
      <form onSubmit={submit}>
        <ModalBody>
          <Field label="Email">
            <input
              autoFocus
              className="input"
              type="email"
              placeholder="name@orochiverse.com"
              value={email}
              onChange={(e) => {
                setEmail(e.target.value);
                setErr("");
              }}
            />
          </Field>
          <div className="grid-2" style={{ gap: 10 }}>
            <Field label="First name">
              <input
                className="input"
                value={firstName}
                onChange={(e) => setFirstName(e.target.value)}
                placeholder="Marisol"
              />
            </Field>
            <Field label="Last name">
              <input
                className="input"
                value={lastName}
                onChange={(e) => setLastName(e.target.value)}
                placeholder="Vega"
              />
            </Field>
          </div>
          <Field
            label="Role"
            hint={
              role === "Admin"
                ? "Full control: can manage tenants, operators, and assignments."
                : "Read-only across the platform; mutations require an Admin."
            }
          >
            <div
              style={{
                display: "grid",
                gridTemplateColumns: `repeat(${ROLES.length}, 1fr)`,
                gap: 4,
                border: "1px solid var(--border)",
                borderRadius: 6,
                padding: 4,
              }}
            >
              {ROLES.map((r) => (
                <button
                  key={r}
                  type="button"
                  className={"btn btn-sm " + (r === role ? "btn-primary" : "btn-ghost")}
                  style={{ justifyContent: "center", width: "100%" }}
                  onClick={() => setRole(r)}
                >
                  {r}
                </button>
              ))}
            </div>
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
            {submitting ? "Sending…" : "Send invitation"}
          </button>
        </ModalFoot>
      </form>
    </Modal>
  );
}
