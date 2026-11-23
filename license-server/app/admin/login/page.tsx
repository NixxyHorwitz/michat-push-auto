"use client";

import { useActionState } from "react";
import { loginAction } from "@/lib/actions";

export default function LoginPage() {
  const [state, action, pending] = useActionState(loginAction, undefined);
  return (
    <main className="login">
      <div className="card">
        <div className="top">
          <div className="logo">⚡</div>
          <h1>Panel Lisensi</h1>
        </div>
        <form action={action}>
          <label>
            Password admin
            <input name="password" type="password" autoComplete="current-password" autoFocus required />
          </label>
          {state?.error && <div className="error">{state.error}</div>}
          <button className="primary" disabled={pending}>{pending ? "Masuk…" : "Masuk"}</button>
        </form>
        <p className="credit">Made by Nixxy</p>
      </div>
    </main>
  );
}
