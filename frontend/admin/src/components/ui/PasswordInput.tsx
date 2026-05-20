"use client";

import { forwardRef, useState, type InputHTMLAttributes, type KeyboardEvent } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";

export type PasswordInputProps = Omit<
  InputHTMLAttributes<HTMLInputElement>,
  "type"
> & {
  /** Auto-generated when omitted; pass through for testing / labels. */
  toggleLabel?: { show: string; hide: string };
  /** Disable the caps-lock warning ribbon (defaults to enabled). */
  disableCapsLockWarning?: boolean;
};

/**
 * Password field with an inline show/hide eye button and caps-lock
 * detection. Visual matches `.input`; the toggle sits inside the field
 * so the focus ring still wraps the whole control. The caps-lock hint
 * appears only while the field is focused and the modifier is on, so
 * it doesn't shout when irrelevant.
 */
export const PasswordInput = forwardRef<HTMLInputElement, PasswordInputProps>(
  function PasswordInput(
    { toggleLabel, disableCapsLockWarning, className, onKeyDown, onKeyUp, onBlur, ...rest },
    ref,
  ) {
    const [visible, setVisible] = useState(false);
    const [capsLock, setCapsLock] = useState(false);
    const [focused, setFocused] = useState(false);
    const labels = toggleLabel ?? { show: "Show password", hide: "Hide password" };

    function syncCaps(e: KeyboardEvent<HTMLInputElement>) {
      if (disableCapsLockWarning) return;
      // getModifierState is available on every modern browser; the
      // try/catch guards Safari's permission quirk on some platforms.
      try {
        setCapsLock(e.getModifierState("CapsLock"));
      } catch {
        // ignore
      }
    }

    return (
      <div className={"password-input " + (className ?? "")}>
        <div className="password-input-wrap">
          <input
            ref={ref}
            className="input password-input-field"
            type={visible ? "text" : "password"}
            onKeyDown={(e) => {
              syncCaps(e);
              onKeyDown?.(e);
            }}
            onKeyUp={(e) => {
              syncCaps(e);
              onKeyUp?.(e);
            }}
            onBlur={(e) => {
              setFocused(false);
              setCapsLock(false);
              onBlur?.(e);
            }}
            onFocus={() => setFocused(true)}
            {...rest}
          />
          <button
            type="button"
            className="password-input-toggle"
            onClick={() => setVisible((v) => !v)}
            aria-label={visible ? labels.hide : labels.show}
            aria-pressed={visible}
            tabIndex={-1}
          >
            <Icon d={visible ? Icons.eyeOff : Icons.eye} size={16} />
          </button>
        </div>
        {focused && capsLock && !disableCapsLockWarning && (
          <div className="password-input-hint" role="status">
            <Icon d={Icons.lock} size={12} />
            <span>Caps Lock is on</span>
          </div>
        )}
      </div>
    );
  },
);
