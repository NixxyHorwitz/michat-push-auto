"use client";

import { useActionState, useState } from "react";
import { createKeysAction } from "@/lib/actions";
import type { Plan } from "@/lib/db";

export default function CreateKeysForm({ plans }: { plans: Plan[] }) {
  const [state, action, pending] = useActionState(createKeysAction, undefined);
  const [duration, setDuration] = useState("30");
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    if (!state?.keys) return;
    await navigator.clipboard.writeText(state.keys.join("\n"));
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  return (
    <div className="card">
      <h2>➕ Buat key</h2>
      <form action={action} className="grid">
        <label>
          Paket
          <select name="plan" defaultValue={plans[0]?.id}>
            {plans.map((p) => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
        </label>
        <label>
          Durasi
          <select name="duration" value={duration} onChange={(e) => setDuration(e.target.value)}>
            <option value="1">1 hari</option>
            <option value="3">3 hari</option>
            <option value="7">7 hari</option>
            <option value="30">30 hari</option>
            <option value="90">90 hari</option>
            <option value="365">1 tahun</option>
            <option value="lifetime">Permanen</option>
            <option value="custom">Lainnya…</option>
          </select>
        </label>
        {duration === "custom" && (
          <label>
            Jumlah hari
            <input name="customDays" type="number" min={1} max={3650} defaultValue={14} />
          </label>
        )}
        <label>
          Jumlah key
          <input name="qty" type="number" min={1} max={200} defaultValue={1} />
        </label>
        <label>
          Maks. HP per key
          <input name="maxDevices" type="number" min={1} max={20} defaultValue={1} />
        </label>
        <label>
          Catatan (nama pembeli, dll)
          <input name="note" maxLength={200} placeholder="opsional" />
        </label>
        <label>
          &nbsp;
          <button className="primary" disabled={pending}>{pending ? "Membuat…" : "Buat key"}</button>
        </label>
      </form>

      {state?.error && <p className="error">{state.error}</p>}
      {state?.keys && (
        <div style={{ marginTop: 14 }}>
          <p className="ok">✓ {state.summary}. Masa aktif dihitung sejak key pertama kali diaktivasi.</p>
          <textarea readOnly rows={Math.min(state.keys.length, 8)} value={state.keys.join("\n")} />
          <button onClick={copy} type="button" style={{ marginTop: 8 }}>{copied ? "Tersalin ✓" : "Salin semua"}</button>
        </div>
      )}
    </div>
  );
}
