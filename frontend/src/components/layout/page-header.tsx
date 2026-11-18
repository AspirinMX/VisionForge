"use client";

import type { ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { WalletIcon } from "lucide-react";

import { useOptionalCredit } from "@/components/providers/credit-provider";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { SidebarTrigger } from "@/components/ui/sidebar";

interface PageHeaderProps {
  title: string;
  subtitle?: string;
  badge?: ReactNode;
  actions?: ReactNode;
}

export function PageHeader({
  title,
  subtitle,
  badge,
  actions,
}: PageHeaderProps) {
  const pathname = usePathname();
  const creditState = useOptionalCredit();
  const credit = creditState?.credit ?? null;
  const showCreditPill = Boolean(credit && pathname !== "/account");

  return (
    <header className="flex h-12 shrink-0 items-center justify-between gap-3 border-b bg-background px-3 md:px-4">
      <div className="flex min-w-0 items-center gap-2">
        <SidebarTrigger aria-label="切换侧边栏" />
        <Separator orientation="vertical" className="h-4" />
        <div className="flex min-w-0 items-center gap-2">
          <h1 className="truncate text-sm font-medium tracking-tight text-foreground">
            {title}
          </h1>
          {badge}
          {subtitle ? (
            <span className="hidden truncate text-xs text-muted-foreground sm:inline">
              {subtitle}
            </span>
          ) : null}
        </div>
      </div>
      {actions || showCreditPill ? (
        <div className="flex shrink-0 items-center gap-2">
          {showCreditPill && credit ? (
            <Button
              variant="ghost"
              size="xs"
              nativeButton={false}
              render={<Link href="/account" />}
              className="hidden text-muted-foreground sm:inline-flex"
            >
              <WalletIcon className="size-3.5" />
              <span className="tabular-nums">
                可用 {credit.availableUnits}
                {credit.reservedUnits > 0
                  ? `（预占 ${credit.reservedUnits}）`
                  : ""}
              </span>
            </Button>
          ) : null}
          {actions}
        </div>
      ) : null}
    </header>
  );
}
