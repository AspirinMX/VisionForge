"use client";

import type { ReactNode } from "react";

import { Separator } from "@/components/ui/separator";
import { SidebarTrigger } from "@/components/ui/sidebar";

interface PageHeaderProps {
  title: string;
  actions?: ReactNode;
}

export function PageHeader({ title, actions }: PageHeaderProps) {
  return (
    <header className="flex h-12 shrink-0 items-center justify-between gap-3 border-b border-border/60 bg-background px-3 md:px-4">
      <div className="flex min-w-0 items-center gap-2">
        <SidebarTrigger aria-label="切换侧边栏" />
        <Separator orientation="vertical" className="h-4" />
        <h1 className="truncate text-sm font-medium tracking-tight text-foreground">
          {title}
        </h1>
      </div>
      {actions ? (
        <div className="flex shrink-0 items-center gap-1.5">{actions}</div>
      ) : null}
    </header>
  );
}
