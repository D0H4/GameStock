import { Link } from "../../components/Link";
import { useProfile } from "../../lib/profile";
import { ADMIN_SECTIONS, AdminUsers } from "../../pages/AdminPage";
import { AdminAudit } from "../../pages/admin/AdminAudit";
import { AdminNotices } from "../../pages/admin/AdminNotices";
import { AdminOrders } from "../../pages/admin/AdminOrders";
import { AdminPromos } from "../../pages/admin/AdminPromos";
import { AdminRuntime } from "../../pages/admin/AdminRuntime";
import { useRoute } from "../../router";

/** 관리자 화면. 서버가 모든 요청에서 ADMIN 권한을 다시 확인한다. 각 구획의 동작은 기존 관리 화면과 같다. */
export function NxAdminPage() {
  const profile = useProfile();
  const route = useRoute();
  const section = route.name === "admin" ? route.section : "users";

  if (!profile) return <p className="nx-empty-state" role="status">권한을 확인하고 있어요.</p>;
  if (!profile.admin) return <p className="nx-note is-error" role="alert">관리자만 볼 수 있는 화면이에요.</p>;

  return (
    <div className="nx-screen nx-admin">
      <header className="nx-screen-head">
        <div>
          <h1 className="nx-page-title">관리자</h1>
          <p className="nx-screen-sub">모든 조치는 감사 기록에 남아요.</p>
        </div>
      </header>
      <div className="nx-admin-runtime"><AdminRuntime /></div>
      {/* 구획은 다섯뿐이라 계좌 화면처럼 가로 탭으로 두고, 본문이 화면 폭을 다 쓰게 한다. */}
      <nav className="nx-tabs nx-admin-nav" aria-label="관리 메뉴">
        {ADMIN_SECTIONS.map((item) => (
          <Link key={item.section} to={item.to} className={item.section === section ? "is-active" : undefined} aria-current={item.section === section ? "page" : undefined}>
            {item.label}
          </Link>
        ))}
      </nav>
      <div className="nx-admin-body">
        {section === "users" && <AdminUsers />}
        {section === "orders" && <AdminOrders />}
        {section === "notices" && <AdminNotices />}
        {section === "promos" && <AdminPromos />}
        {section === "audit" && <AdminAudit />}
      </div>
    </div>
  );
}
