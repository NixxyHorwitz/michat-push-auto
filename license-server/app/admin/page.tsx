import Link from "next/link";
import { requireAdmin } from "@/lib/auth";
import { keyAction, logoutAction, savePlanAction } from "@/lib/actions";
import { sql, type LicenseRow, type Plan } from "@/lib/db";
import ConfirmButton from "./ConfirmButton";
import CopyButton from "./CopyButton";
import CreateKeysForm from "./CreateKeysForm";

export const dynamic = "force-dynamic";

const fmt = (iso: string | null) =>
  iso
    ? new Date(iso).toLocaleString("id-ID", {
        timeZone: "Asia/Jakarta",
        day: "2-digit",
        month: "short",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "—";

type St = "active" | "unused" | "expired" | "revoked";

function statusOf(k: LicenseRow): St {
  if (k.status === "revoked") return "revoked";
  if (!k.activated_at) return "unused";
  if (k.expires_at && new Date(k.expires_at) <= new Date()) return "expired";
  return "active";
}

const STATUS_LABEL: Record<St, string> = {
  active: "Aktif",
  unused: "Belum dipakai",
  expired: "Kadaluarsa",
  revoked: "Dicabut",
};

function remaining(k: LicenseRow) {
  if (!k.activated_at) return k.duration_days ? `${k.duration_days} hari` : "Permanen";
  if (!k.expires_at) return "Permanen";
  const ms = new Date(k.expires_at).getTime() - Date.now();
  if (ms <= 0) return "Habis";
  const d = Math.floor(ms / 86_400_000);
  const h = Math.floor((ms % 86_400_000) / 3_600_000);
  return d > 0 ? `${d}h ${h}j` : `${h} jam`;
}

export default async function AdminPage({
  searchParams,
}: {
  searchParams: Promise<{ q?: string; s?: string }>;
}) {
  await requireAdmin();
  const { q = "", s = "all" } = await searchParams;

  const plans = (await sql`SELECT id, name, features FROM plans ORDER BY created_at`) as Plan[];
  const like = `%${q.trim()}%`;
  const keys = (await sql`
    SELECT k.*, p.name AS plan_name, p.features,
           (SELECT count(*)::int FROM activations a WHERE a.key = k.key) AS device_count,
           (SELECT max(last_seen) FROM activations a WHERE a.key = k.key) AS last_seen
    FROM license_keys k JOIN plans p ON p.id = k.plan_id
    WHERE (${q.trim()} = '' OR k.key ILIKE ${like} OR k.note ILIKE ${like})
    ORDER BY k.created_at DESC
    LIMIT 500`) as LicenseRow[];

  const all = keys.map((k) => ({ k, st: statusOf(k) }));
  const count = (st: St) => all.filter((x) => x.st === st).length;
  const shown = s === "all" ? all : all.filter((x) => x.st === s);
  const href = (st: string) => {
    const p = new URLSearchParams();
    if (q) p.set("q", q);
    if (st !== "all") p.set("s", st);
    const qs = p.toString();
    return qs ? `/admin?${qs}` : "/admin";
  };

  const filters: { id: "all" | St; label: string; n: number }[] = [
    { id: "all", label: "Semua", n: all.length },
    { id: "active", label: "Aktif", n: count("active") },
    { id: "unused", label: "Baru", n: count("unused") },
    { id: "expired", label: "Habis", n: count("expired") },
    { id: "revoked", label: "Dicabut", n: count("revoked") },
  ];

  return (
    <>
      <header className="appbar">
        <div className="appbar-inner">
          <div className="logo" aria-hidden>⚡</div>
          <div className="appbar-title">
            <strong>Lisensi</strong>
            <span>MiChat Push Auto</span>
          </div>
          <form action={logoutAction}>
            <button className="btn-ghost" aria-label="Keluar">Keluar</button>
          </form>
        </div>
      </header>

      <main className="wrap">
        {/* Statistik sekaligus filter status */}
        <nav className="chips" aria-label="Filter status">
          {filters.map((f) => (
            <Link
              key={f.id}
              href={href(f.id)}
              className={`chip ${f.id} ${s === f.id ? "on" : ""}`}
              aria-current={s === f.id ? "page" : undefined}
            >
              <b>{f.n}</b>
              <span>{f.label}</span>
            </Link>
          ))}
        </nav>

        <div className="layout-2">
        <CreateKeysForm plans={plans} />

        <section aria-labelledby="keys-title">
          <div className="section-head">
            <h2 id="keys-title">Key {s !== "all" && <small>· {filters.find((f) => f.id === s)?.label}</small>}</h2>
            <span className="muted">{shown.length} item</span>
          </div>

          <form className="search" role="search">
            {s !== "all" && <input type="hidden" name="s" value={s} />}
            <input name="q" defaultValue={q} placeholder="Cari key atau nama pembeli…" aria-label="Cari key" inputMode="search" />
            <button aria-label="Cari">🔍</button>
          </form>

          {shown.length === 0 ? (
            <p className="empty">Belum ada key di sini.</p>
          ) : (
            <ul className="key-list">
              {shown.map(({ k, st }) => (
                <li key={k.key} className={`key-card ${st}`}>
                  <div className="key-top">
                    <code className="key-code">{k.key}</code>
                    <CopyButton text={k.key} />
                  </div>

                  <div className="key-meta">
                    <span className={`pill ${st}`}>{STATUS_LABEL[st]}</span>
                    <span className="pill plan">{k.plan_name}</span>
                    <span className="meta-item" title="Sisa masa aktif">⏳ {remaining(k)}</span>
                    <span className="meta-item" title="HP terpakai">📱 {k.device_count}/{k.max_devices}</span>
                  </div>

                  {k.note && <p className="key-note">👤 {k.note}</p>}
                  <p className="key-dates">
                    {k.expires_at ? <>s/d {fmt(k.expires_at)}</> : <>dibuat {fmt(k.created_at)}</>}
                    {k.last_seen && <> · online {fmt(k.last_seen)}</>}
                  </p>

                  <div className="key-actions">
                    <form action={keyAction} className="extend">
                      <input type="hidden" name="key" value={k.key} />
                      <input type="hidden" name="op" value="extend" />
                      <select name="days" aria-label="Tambah hari" defaultValue="30">
                        <option value="1">+1 hari</option>
                        <option value="3">+3 hari</option>
                        <option value="7">+7 hari</option>
                        <option value="30">+30 hari</option>
                        <option value="90">+90 hari</option>
                        <option value="365">+1 tahun</option>
                      </select>
                      <button>Tambah</button>
                    </form>
                    {k.status === "revoked" ? (
                      <form action={keyAction}>
                        <input type="hidden" name="key" value={k.key} />
                        <button name="op" value="restore">Aktifkan</button>
                      </form>
                    ) : (
                      <form action={keyAction}>
                        <input type="hidden" name="key" value={k.key} />
                        <ConfirmButton
                          className="danger"
                          name="op"
                          value="revoke"
                          message={`Cabut ${k.key}? App pembeli akan terkunci saat cek berikutnya.`}
                        >
                          Cabut
                        </ConfirmButton>
                      </form>
                    )}
                    {k.device_count > 0 && (
                      <form action={keyAction}>
                        <input type="hidden" name="key" value={k.key} />
                        <ConfirmButton name="op" value="reset" message={`Lepas HP dari ${k.key}? Pembeli bisa aktivasi di HP baru.`}>
                          Reset HP
                        </ConfirmButton>
                      </form>
                    )}
                    <form action={keyAction}>
                      <input type="hidden" name="key" value={k.key} />
                      <ConfirmButton
                        className="danger icon-only"
                        name="op"
                        value="delete"
                        aria-label={`Hapus ${k.key}`}
                        message={`Hapus permanen ${k.key}? Tidak bisa dibatalkan.`}
                      >
                        🗑
                      </ConfirmButton>
                    </form>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>
        </div>

        <details className="card plans">
          <summary>
            <span>📦 Paket &amp; fitur</span>
            <span className="muted">{plans.length} paket</span>
          </summary>
          <p className="muted">Perubahan berlaku untuk semua key di paket itu saat app cek ulang ke server.</p>
          {[...plans, { id: "", name: "", features: { max_target: 20 } } as Plan].map((p) => (
            <form key={p.id || "new"} action={savePlanAction} className={`plan-row ${p.id ? "" : "new"}`}>
              <div className="plan-fields">
                <label>
                  ID
                  <input name="id" defaultValue={p.id} readOnly={!!p.id} placeholder="vip" required />
                </label>
                <label>
                  Nama
                  <input name="name" defaultValue={p.name} placeholder="VIP" required />
                </label>
                <label>
                  Maks. sapaan
                  <input name="max_target" type="number" inputMode="numeric" min={1} max={1000} defaultValue={p.features.max_target ?? 20} />
                </label>
              </div>
              <div className="plan-toggles">
                <label className="check">
                  <input type="checkbox" name="custom_message" defaultChecked={!!p.features.custom_message} /> Pesan custom
                </label>
                <label className="check">
                  <input type="checkbox" name="gender_icon" defaultChecked={!!p.features.gender_icon} /> Ikon gender
                </label>
                <button className={p.id ? "" : "primary"}>{p.id ? "Simpan" : "+ Tambah"}</button>
              </div>
            </form>
          ))}
        </details>

        <p className="credit">Made by Nixxy</p>
      </main>
    </>
  );
}
