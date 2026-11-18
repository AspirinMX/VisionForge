"use client";

import type { ReactNode } from "react";

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
      {actions ? (
        <div className="flex shrink-0 items-center gap-2">{actions}</div>
      ) : null}
    </header>
  );
}
