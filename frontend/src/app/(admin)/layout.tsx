import type { ReactNode } from "react";

import { AdminShell } from "@/components/admin/admin-shell";
import { AdminRoleGuard, StudioAuthGuard } from "@/components/common/auth-guard";

export default function AdminLayout({ children }: { children: ReactNode }) {
  return (
    <StudioAuthGuard>
      <AdminRoleGuard>
        <AdminShell>{children}</AdminShell>
      </AdminRoleGuard>
    </StudioAuthGuard>
  );
}
