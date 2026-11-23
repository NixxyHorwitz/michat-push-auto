"use client";

import { useActionState, useEffect, useRef, useState } from "react";
import { createKeysAction } from "@/lib/actions";
import type { Plan } from "@/lib/db";

const DURATIONS = [
  { v: "1", l: "1 hari" },
  { v: "3", l: "3 hari" },
  { v: "7", l: "7 hari" },
  { v: "30", l: "30 hari" },
  { v: "90", l: "90 hari" },
  { v: "365", l: "1 tahun" },
  { v: "lifetime", l: "Permanen" },
  { v: "custom", l: "Lainnya" },
];

export default function CreateKeysForm({ plans }: { plans: Plan[] }) {
  const [state, action, pending] = useActionState(createKeysAction, undefined);
  const [duration, setDuration] = useState("30");
  const [plan, setPlan] = useState(plans[0]?.id ?? "");
  const [copied, setCopied] = useState(false);
  const [canShare, setCanShare] = useState(false);
  const resultRef = useRef<HTMLDivElement>(null);

  useEffect(() => setCanShare(typeof navigator !== "undefined" && !!navigator.share), []);
  useEffect(() => {
    if (state?.keys) resultRef.current?.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }, [state]);

  const message = state?.keys
    ? `Key MiChat Push Auto (${state.summary}):\n${state.keys.join("\n")}\n\nBuka app → masukkan key → Aktifkan.`
    : "";

  const copy = async () => {
    await navigator.clipboard.writeText(state?.keys?.join("\n") ?? "");
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <details className="card create" open>
      <summary>
        <span>➕ Buat key</span>
        <span className="muted">untuk pembeli</span>
      </summary>

      <form action={action} className="create-form">
        <fieldset>
          <legend>Paket</legend>
          <div className="seg">
            {plans.map((p) => (
              <label key={p.id} className={plan === p.id ? "on" : ""}>
                <input type="radio" name="plan" value={p.id} checked={plan === p.id} onChange={() => setPlan(p.id)} />
                {p.name}
              </label>
            ))}
          </div>
        </fieldset>

        <fieldset>
          <legend>Durasi</legend>
          <div className="seg multi">
            {DURATIONS.map((d) => (
              <label key={d.v} className={duration === d.v ? "on" : ""}>
                <input type="radio" name="duration" value={d.v} checked={duration === d.v} onChange={() => setDuration(d.v)} />
                {d.l}
              </label>
            ))}
          </div>
          {duration === "custom" && (
            <label className="inline-field">
              Jumlah hari
              <input name="customDays" type="number" inputMode="numeric" min={1} max={3650} defaultValue={14} />
            </label>
          )}
        </fieldset>

        <div className="two">
          <label>
            Jumlah key
            <input name="qty" type="number" inputMode="numeric" min={1} max={200} defaultValue={1} />
          </label>
          <label>
            HP per key
            <input name="maxDevices" type="number" inputMode="numeric" min={1} max={20} defaultValue={1} />
          </label>
        </div>

        <label>
          Catatan
          <input name="note" maxLength={200} placeholder="Nama / no. WA pembeli (opsional)" />
        </label>

        <button className="primary block" disabled={pending}>
          {pending ? "Membuat…" : "Buat key"}
        </button>
      </form>

      {state?.error && <p className="error" role="alert">{state.error}</p>}

      {state?.keys && (
        <div className="result" ref={resultRef} role="status">
          <p className="ok">✓ {state.summary}</p>
          <ul className="result-keys">
            {state.keys.map((k) => (
              <li key={k}><code>{k}</code></li>
            ))}
          </ul>
          <div className="result-actions">
            <button type="button" onClick={copy}>{copied ? "Tersalin ✓" : "⧉ Salin"}</button>
            {canShare && (
              <button type="button" className="primary" onClick={() => navigator.share({ text: message }).catch(() => {})}>
                ↗ Kirim ke pembeli
              </button>
            )}
          </div>
          <p className="muted">Masa aktif mulai dihitung saat key pertama kali diaktivasi.</p>
        </div>
      )}
    </details>
  );
}
