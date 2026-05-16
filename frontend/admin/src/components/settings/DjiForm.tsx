"use client";

import { useEffect, useMemo, useState } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Chip } from "@/components/ui/Chip";
import { settingsApi } from "@/lib/api/settings";
import { type DjiSettings } from "@/lib/api/types";
import { useToast } from "@/lib/toast/ToastProvider";

const DEFAULTS: DjiSettings = {
  region: "ap",
  endpoint: "",
  appKey: "",
  configured: false,
};

export function DjiForm({
  tenantId,
  initial,
  canManage = true,
  onSaved,
}: {
  tenantId: string;
  initial: DjiSettings | undefined;
  canManage?: boolean;
  onSaved: (next: DjiSettings) => void;
}) {
  const [server, setServer] = useState<DjiSettings>(initial ?? DEFAULTS);
  const [draft, setDraft] = useState<DjiSettings>(initial ?? DEFAULTS);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const { notify } = useToast();

  useEffect(() => {
    if (initial) {
      setServer(initial);
      setDraft(initial);
    }
  }, [initial]);

  const dirty = useMemo(() => {
    const keys: (keyof DjiSettings)[] = ["region", "endpoint", "appKey"];
    return keys.some((k) => server[k] !== draft[k]);
  }, [server, draft]);

  function bind<K extends keyof DjiSettings>(key: K) {
    return {
      value: String(draft[key] ?? ""),
      onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
        setDraft((d) => ({ ...d, [key]: e.target.value as DjiSettings[K] })),
    };
  }

  async function save() {
    setSaving(true);
    try {
      const saved = await settingsApi.dji.save(tenantId, draft);
      setServer(saved);
      setDraft(saved);
      onSaved(saved);
      notify("DJI endpoint saved", "success");
    } catch (e: unknown) {
      notify(e instanceof Error ? e.message : "Save failed", "error");
    } finally {
      setSaving(false);
    }
  }

  async function test() {
    setTesting(true);
    const wait = new Promise((r) => setTimeout(r, 900));
    try {
      const [result] = await Promise.all([settingsApi.dji.test(tenantId), wait]);
      if (result.ok) {
        notify(`DJI endpoint ok · ${result.latencyMs ?? 38} ms`, "success");
      } else {
        notify(result.error || "DJI endpoint failed", "error");
      }
    } catch (e: unknown) {
      notify(e instanceof Error ? e.message : "DJI test failed", "error");
    } finally {
      setTesting(false);
    }
  }

  async function clear() {
    if (!window.confirm("Clear DJI settings? Drone integration won't function until you reconfigure.")) {
      return;
    }
    setSaving(true);
    try {
      await settingsApi.dji.clear(tenantId);
      setServer(DEFAULTS);
      setDraft(DEFAULTS);
      onSaved(DEFAULTS);
      notify("DJI settings cleared", "info");
    } catch (e: unknown) {
      notify(e instanceof Error ? e.message : "Clear failed", "error");
    } finally {
      setSaving(false);
    }
  }

  const callback = `https://cloudgcs.io/dji/callback?tenant=${tenantId}`;

  async function copyCallback() {
    try {
      await navigator.clipboard?.writeText(callback);
      notify("Copied callback URL", "info");
    } catch {
      notify("Copy failed — your browser blocked clipboard access", "error");
    }
  }

  return (
    <div className="card">
      <div className="card-head">
        <div>
          <div className="card-title">DJI endpoint</div>
          <div className="card-sub">Integration with DJI Cloud / Pilot for this tenant.</div>
        </div>
        <span style={{ marginLeft: "auto" }}>
          <Chip variant={server.configured ? "good" : "warn"}>
            {server.configured ? "configured" : "not configured"}
          </Chip>
        </span>
      </div>
      <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
        <div className="field">
          <label className="field-label">DJI region</label>
          <select className="select" {...bind("region")} disabled={!canManage}>
            <option value="ap">Asia Pacific</option>
            <option value="us">Americas</option>
            <option value="eu">Europe</option>
          </select>
        </div>
        <div className="field">
          <label className="field-label">Endpoint URL</label>
          <input className="input mono" placeholder="https://api-cloud.dji.com" {...bind("endpoint")} disabled={!canManage} />
        </div>
        <div className="field">
          <label className="field-label">App key</label>
          <input className="input mono" placeholder="dji_app_key_…" {...bind("appKey")} disabled={!canManage} />
        </div>
        <div className="field">
          <label className="field-label">Callback URL</label>
          <div style={{ display: "flex", gap: 6 }}>
            <input className="input mono" readOnly value={callback} />
            <button className="btn btn-icon" title="Copy" onClick={copyCallback}>
              <Icon d={Icons.copy} size={14} />
            </button>
          </div>
          <span className="field-hint">Add this URL to your DJI developer console.</span>
        </div>
        {canManage && (
          <div className="row" style={{ justifyContent: "flex-end", gap: 8 }}>
            {server.configured && (
              <button
                className="btn"
                style={{ color: "var(--bad)" }}
                onClick={clear}
                disabled={saving}
                title="Clear all DJI settings"
              >
                <Icon d={Icons.trash} size={14} /> Clear
              </button>
            )}
            <button className="btn" onClick={test} disabled={testing}>
              <Icon d={Icons.refresh} size={14} /> {testing ? "Testing…" : "Test connection"}
            </button>
            <button className="btn btn-primary" onClick={save} disabled={!dirty || saving}>
              {saving ? "Saving…" : "Save changes"}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
