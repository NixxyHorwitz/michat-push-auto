import { requireAdmin } from "@/lib/auth";
import { keyAction, logoutAction, savePlanAction } from "@/lib/actions";
import { sql, type LicenseRow, type Plan } from "@/lib/db";
import ConfirmButton from "./ConfirmButton";
import CreateKeysForm from "./CreateKeysForm";

export const dynamic = "force-dynamic";

const fmt = (iso: string | null) =>
  iso
    ? new Date(iso).toLocaleString("id-ID", {
        timeZone: "Asia/Jakarta",
        day: "2-digit",
        month: "short",
        year: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
      })
    : "—";

function statusOf(k: LicenseRow): "active" | "unused" | "expired" | "revoked" {
  if (k.status === "revoked") return "revoked";
  if (!k.activated_at) return "unused";
  if (k.expires_at && new Date(k.expires_at) <= new Date()) return "expired";
  return "active";
}

const STATUS_LABEL = { active: "Aktif", unused: "Belum dipakai", expired: "Kadaluarsa", revoked: "Dicabut" };

function remaining(k: LicenseRow) {
  if (!k.activated_at) return k.duration_days ? `${k.duration_days} hari (belum mulai)` : "Permanen";
  if (!k.expires_at) return "Permanen";
  const ms = new Date(k.expires_at).getTime() - Date.now();
  if (ms <= 0) return "Habis";
  const d = Math.floor(ms / 86_400_000);
  const h = Math.floor((ms % 86_400_000) / 3_600_000);
  return d > 0 ? `${d} hari ${h} jam` : `${h} jam`;
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
  const count = (st: string) => all.filter((x) => x.st === st).length;
  const shown = s === "all" ? all : all.filter((x) => x.st === s);

  return (
    <main className="wrap">
      <div className="top">
        <div className="logo">⚡</div>
        <h1>Lisensi MiChat Push Auto</h1>
        <form action={logoutAction}>
          <button className="small">Keluar</button>
        </form>
      </div>

      <div className="stats">
        <div className="stat"><b>{count("active")}</b><span>Aktif</span></div>
        <div className="stat"><b>{count("unused")}</b><span>Belum dipakai</span></div>
        <div className="stat"><b>{count("expired")}</b><span>Kadaluarsa</span></div>
        <div className="stat"><b>{count("revoked")}</b><span>Dicabut</span></div>
      </div>

      <CreateKeysForm plans={plans} />

      <div className="card">
        <h2>🔑 Daftar key</h2>
        <form className="filters">
          <input name="q" defaultValue={q} placeholder="Cari key atau catatan…" />
          <select name="s" defaultValue={s}>
            <option value="all">Semua status</option>
            <option value="active">Aktif</option>
            <option value="unused">Belum dipakai</option>
            <option value="expired">Kadaluarsa</option>
            <option value="revoked">Dicabut</option>
          </select>
          <button>Filter</button>
        </form>

        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Key</th>
                <th>Paket</th>
                <th>Status</th>
                <th>Sisa</th>
                <th className="hide-sm">HP</th>
                <th className="hide-sm">Terakhir online</th>
                <th>Aksi</th>
              </tr>
            </thead>
            <tbody>
              {shown.length === 0 && (
                <tr><td colSpan={7} className="muted">Belum ada key.</td></tr>
              )}
              {shown.map(({ k, st }) => (
                <tr key={k.key}>
                  <td>
                    <code>{k.key}</code>
                    {k.note && <div className="muted">{k.note}</div>}
                    <div className="muted">dibuat {fmt(k.created_at)}</div>
                  </td>
                  <td>{k.plan_name}</td>
                  <td><span className={`pill ${st}`}>{STATUS_LABEL[st]}</span></td>
                  <td>
                    {remaining(k)}
                    {k.expires_at && <div className="muted">s/d {fmt(k.expires_at)}</div>}
                  </td>
                  <td className="hide-sm">{k.device_count}/{k.max_devices}</td>
                  <td className="hide-sm">{fmt(k.last_seen)}</td>
                  <td>
                    <div className="row-actions">
                      <form action={keyAction}>
                        <input type="hidden" name="key" value={k.key} />
                        <input type="hidden" name="op" value="extend" />
                        <input type="number" name="days" min={1} defaultValue={30} aria-label="Hari" />
                        <button className="small">+ hari</button>
                      </form>
                      {k.status === "revoked" ? (
                        <form action={keyAction}>
                          <input type="hidden" name="key" value={k.key} />
                          <button className="small" name="op" value="restore">Aktifkan</button>
                        </form>
                      ) : (
                        <form action={keyAction}>
                          <input type="hidden" name="key" value={k.key} />
                          <ConfirmButton className="small danger" name="op" value="revoke" message={`Cabut ${k.key}? App pembeli akan terkunci saat cek berikutnya.`}>Cabut</ConfirmButton>
                        </form>
                      )}
                      {k.device_count > 0 && (
                        <form action={keyAction}>
                          <input type="hidden" name="key" value={k.key} />
                          <button className="small" name="op" value="reset" title="Lepas HP agar key bisa dipakai di HP baru">
                            Reset HP
                          </button>
                        </form>
                      )}
                      <form action={keyAction}>
                        <input type="hidden" name="key" value={k.key} />
                        <ConfirmButton className="small danger" name="op" value="delete" title="Hapus permanen" message={`Hapus permanen ${k.key}? Tidak bisa dibatalkan.`}>Hapus</ConfirmButton>
                      </form>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      <div className="card">
        <h2>📦 Paket &amp; fitur</h2>
        <p className="muted">Perubahan fitur berlaku untuk semua key di paket itu saat app cek ulang ke server.</p>
        {[...plans, { id: "", name: "", features: { max_target: 20 } } as Plan].map((p) => (
          <form key={p.id || "new"} action={savePlanAction} className="grid" style={{ marginBottom: 12, alignItems: "end" }}>
            <label>
              ID paket
              <input name="id" defaultValue={p.id} readOnly={!!p.id} placeholder="mis. vip" required />
            </label>
            <label>
              Nama
              <input name="name" defaultValue={p.name} placeholder="mis. VIP" required />
            </label>
            <label>
              Maks. sapaan/sesi
              <input name="max_target" type="number" min={1} max={1000} defaultValue={p.features.max_target ?? 20} />
            </label>
            <label className="inline">
              <input type="checkbox" name="custom_message" defaultChecked={!!p.features.custom_message} /> Pesan custom
            </label>
            <label className="inline">
              <input type="checkbox" name="gender_icon" defaultChecked={!!p.features.gender_icon} /> Cek ikon gender
            </label>
            <button className={p.id ? "" : "primary"}>{p.id ? "Simpan" : "Tambah paket"}</button>
          </form>
        ))}
      </div>

      <p className="credit">Made by Nixxy</p>
    </main>
  );
}
