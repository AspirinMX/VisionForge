import type { ReactNode } from "react";

import { StudioAuthGuard } from "@/components/common/auth-guard";
import { AppShell } from "@/components/layout/app-shell";
import { ConversationsProvider } from "@/components/providers/conversations-provider";
import { CreditProvider } from "@/components/providers/credit-provider";

export default function StudioLayout({ children }: { children: ReactNode }) {
  return (
    <StudioAuthGuard>
      <ConversationsProvider>
        <CreditProvider>
          <AppShell>{children}</AppShell>
        </CreditProvider>
      </ConversationsProvider>
    </StudioAuthGuard>
  );
}
