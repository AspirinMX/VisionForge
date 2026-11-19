"use client";

import type { ReactNode } from "react";
import {
  ArrowDownRightIcon,
  ArrowUpRightIcon,
  type LucideIcon,
} from "lucide-react";

import { Card, CardContent } from "@/components/ui/card";
import { cn } from "@/lib/utils";

import { Sparkline } from "./charts";

type DeltaKind = "relative" | "points";

interface DeltaLabelProps {
  value: number | null | undefined;
  kind?: DeltaKind;
}

export function DeltaLabel({ value, kind = "relative" }: DeltaLabelProps) {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return (
      <span className="text-[11px] text-muted-foreground">暂无对比</span>
    );
  }
  if (value === 0) {
    return (
      <span className="inline-flex items-center gap-0.5 text-[11px] text-muted-foreground tabular-nums">
        持平
      </span>
    );
  }
  const positive = value > 0;
  const Icon = positive ? ArrowUpRightIcon : ArrowDownRightIcon;
  const amount = `${Math.abs(value * 100).toFixed(1)}${
    kind === "points" ? " 个百分点" : "%"
  }`;
  return (
    <span
      className={cn(
        "inline-flex items-center gap-0.5 text-[11px] tabular-nums",
        positive ? "text-success" : "text-destructive"
      )}
    >
      <Icon className="size-3" />
      {positive ? "+" : "-"}
      {amount}
    </span>
  );
}

function IconChip({ icon: Icon }: { icon: LucideIcon }) {
  return (
    <span className="flex size-7 shrink-0 items-center justify-center rounded-control-md bg-muted/50 text-muted-foreground">
      <Icon className="size-3.5" />
    </span>
  );
}

interface StatTileProps {
  icon: LucideIcon;
  label: string;
  value: string;
  hint?: ReactNode;
  delta?: number | null;
  deltaKind?: DeltaKind;
  series?: number[];
  meter?: number | null;
}

export function StatTile({
  icon,
  label,
  value,
  hint,
  delta,
  deltaKind = "relative",
  series,
  meter,
}: StatTileProps) {
  const meterWidth =
    meter === null || meter === undefined
      ? null
      : Math.min(Math.max(meter, 0), 1) * 100;
  const meterColor =
    meter !== null && meter !== undefined && meter >= 0.98
      ? "bg-success"
      : meter !== null && meter !== undefined && meter >= 0.9
        ? "bg-warning"
        : "bg-destructive";

  return (
    <Card size="sm" className="flex h-full flex-col">
      <CardContent className="flex h-full flex-col justify-between gap-2">
        <div className="flex items-center justify-between gap-2">
          <div className="flex min-w-0 items-center gap-2 text-xs text-muted-foreground">
            <IconChip icon={icon} />
            <span className="truncate">{label}</span>
          </div>
          <DeltaLabel value={delta} kind={deltaKind} />
        </div>

        <div>
          <div className="text-2xl font-semibold tracking-tight tabular-nums">
            {value}
          </div>
          {hint ? (
            <p className="mt-0.5 text-xs leading-relaxed text-muted-foreground">
              {hint}
            </p>
          ) : null}
          {meterWidth !== null ? (
            <div
              className="mt-2 h-1.5 overflow-hidden rounded-pill bg-muted"
              role="presentation"
            >
              <div
                className={cn("h-full rounded-pill", meterColor)}
                style={{ width: `${meterWidth}%` }}
              />
            </div>
          ) : null}
          {series && series.length > 1 ? (
            <Sparkline values={series} className="mt-2" />
          ) : null}
        </div>
      </CardContent>
    </Card>
  );
}

interface HeroStatCardProps {
  icon: LucideIcon;
  label: string;
  value: string;
  unit: string;
  hint: ReactNode;
  delta: number | null | undefined;
  series: number[];
}

export function HeroStatCard({
  icon,
  label,
  value,
  unit,
  hint,
  delta,
  series,
}: HeroStatCardProps) {
  return (
    <Card size="sm" className="flex h-full flex-col">
      <CardContent className="flex h-full flex-col justify-between gap-3">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <IconChip icon={icon} />
              <span>{label}</span>
            </div>
            <div className="mt-3 flex items-baseline gap-2">
              <span className="text-4xl font-semibold tracking-tight tabular-nums md:text-5xl">
                {value}
              </span>
              <span className="text-sm text-muted-foreground">{unit}</span>
            </div>
            <p className="mt-1 text-xs leading-relaxed text-muted-foreground">
              {hint}
            </p>
            <div className="mt-2 flex flex-wrap items-center gap-1.5 text-[11px] text-muted-foreground">
              <DeltaLabel value={delta} />
              <span>较上一周期</span>
            </div>
          </div>
          <div className="w-24 shrink-0 self-end sm:w-32">
            <Sparkline values={series} />
          </div>
        </div>
      </CardContent>
    </Card>
  );
}
