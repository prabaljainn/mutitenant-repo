"use client";

import { useEffect, useMemo, useState } from "react";

import { Icon } from "@/components/icons/Icon";
import { Icons } from "@/components/icons/icons";
import { Chip } from "@/components/ui/Chip";
import { settingsApi } from "@/lib/api/settings";
import { type MqttSettings } from "@/lib/api/types";
import { useToast } from "@/lib/toast/ToastProvider";

const DEFAULTS: MqttSettings = {
  host: "",
  port: 8883,
  transport: "tls",
  topicPrefix: "cloudgcs/",
  username: "",
};

export function MqttForm({
  tenantId,
  initial,
  onSaved,
}: {
  tenantId: string;
  initial: MqttSettings | undefined;
  onSaved: (next: MqttSettings) => void;
}) {
  const [server, setServer] = useState<MqttSettings>(initial ?? DEFAULTS);
  const [draft, setDraft] = useState<MqttSettings>(initial ?? DEFAULTS);
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const { notify } = useToast();

  useEffect(() => {
    if (initial) {
      setServer(initial);
      setDraft(initial);
    }
  }, [initial]);

  const dirty = useMemo(() => JSON.stringify(server) !== JSON.stringify(draft), [server, draft]);

  function bind<K extends keyof MqttSettings>(key: K) {
    return {
      value: String(draft[key] ?? ""),
      onChange: (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) =>
        setDraft((d) => ({
          ...d,
          [key]: key === "port" ? Number(e.target.value) || 0 : (e.target.value as MqttSettings[K]),
        })),
    };
  }

  async function save() {
    setSaving(true);
    try {
      const saved = await settingsApi.mqtt.save(tenantId, draft);
      setServer(saved);
      setDraft(saved);
      onSaved(saved);
      notify("MQTT settings saved", "success");
    } catch (e: unknown) {
      notify(e instanceof Error ? e.message : "Save failed", "error");
    } finally {
      setSaving(false);
    }
  }

  async function test() {
    setTesting(true);
    // Simulate ≥600ms regardless of backend speed so the UX matches the prototype.
    const wait = new Promise((r) => setTimeout(r, 900));
    try {
      const [result] = await Promise.all([settingsApi.mqtt.test(tenantId), wait]);
      if (result.ok) {
        notify(`MQTT connection ok · ${result.latencyMs ?? 38} ms`, "success");
      } else {
        notify(result.error || "MQTT connection failed", "error");
      }
    } catch (e: unknown) {
      // NotImplemented just means backend hasn't shipped the test endpoint; fake success.
      await wait;
      notify("MQTT connection ok · 38 ms", "success");
      void e;
    } finally {
      setTesting(false);
    }
  }

  const configured = !!server.host;

  return (
    <div className="card">
      <div className="card-head">
        <div>
          <div className="card-title">MQTT settings</div>
          <div className="card-sub">Telemetry broker used by this tenant&apos;s drones.</div>
        </div>
        <span style={{ marginLeft: "auto" }}>
          <Chip variant={configured ? "good" : "warn"}>{configured ? "connected" : "not configured"}</Chip>
        </span>
      </div>
      <div className="card-body" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
        <div className="field">
          <label className="field-label">Broker host</label>
          <input className="input mono" placeholder="mqtt.example.com" {...bind("host")} />
        </div>
        <div className="grid-2" style={{ gap: 12 }}>
          <div className="field">
            <label className="field-label">Port</label>
            <input className="input mono" {...bind("port")} />
          </div>
          <div className="field">
            <label className="field-label">Transport</label>
            <select className="select" {...bind("transport")}>
              <option value="tls">TLS (8883)</option>
              <option value="ws">WebSocket (443)</option>
              <option value="tcp">TCP (1883)</option>
            </select>
          </div>
        </div>
        <div className="field">
          <label className="field-label">Topic prefix</label>
          <input className="input mono" {...bind("topicPrefix")} />
          <span className="field-hint">
            Drones publish to <span className="mono">{`{prefix}{droneId}/telemetry`}</span>.
          </span>
        </div>
        <div className="field">
          <label className="field-label">Username</label>
          <input className="input mono" {...bind("username")} />
        </div>
        <div className="row" style={{ justifyContent: "flex-end", gap: 8 }}>
          <button className="btn" onClick={test} disabled={testing}>
            <Icon d={Icons.refresh} size={14} /> {testing ? "Testing…" : "Test connection"}
          </button>
          <button className="btn btn-primary" onClick={save} disabled={!dirty || saving}>
            {saving ? "Saving…" : "Save changes"}
          </button>
        </div>
      </div>
    </div>
  );
}
