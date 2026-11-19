"use client";

import { useState } from "react";
import { TrophyIcon } from "lucide-react";
import { motion, useReducedMotion } from "motion/react";

import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import type { AdminDashboardLeaderboardEntry } from "@/lib/api/types";
import {
  formatCompactNumber,
  formatInteger,
  getInitials,
} from "@/lib/format";
import { cn } from "@/lib/utils";

const EASE = [0.16, 1, 0.3, 1] as const;

type LeaderboardMetric = "credits" | "tokens";

interface LeaderboardCardProps {
  rangeDays: number;
  credits: AdminDashboardLeaderboardEntry[];
  tokens: AdminDashboardLeaderboardEntry[];
  creditTotal: number;
  tokenTotal: number;
  className?: string;
}

export function LeaderboardCard({
  rangeDays,
  credits,
  tokens,
  creditTotal,
  tokenTotal,
  className,
}: LeaderboardCardProps) {
  const [metric, setMetric] = useState<LeaderboardMetric>("credits");
  const reduceMotion = useReducedMotion();
  const entries = metric === "credits" ? credits : tokens;
  const total = metric === "credits" ? creditTotal : tokenTotal;
  const maxValue = Math.max(...entries.map((entry) => entry.value), 1);

  return (
    <Card size="sm" className={cn("flex h-full flex-col", className)}>
      <CardHeader>
        <div className="flex items-center gap-2">
          <span className="flex size-7 items-center justify-center rounded-control-md bg-muted/50 text-muted-foreground">
            <TrophyIcon className="size-3.5" />
          </span>
          <CardTitle className="text-sm">消耗排名</CardTitle>
        </div>
        <CardDescription className="text-xs">
          近 {rangeDays} 天 · 按用户聚合
        </CardDescription>
        <CardAction>
          <Tabs
            value={metric}
            onValueChange={(value) => setMetric(value as LeaderboardMetric)}
          >
            <TabsList className="h-7">
              <TabsTrigger value="credits" className="px-2 text-xs">
                额度
              </TabsTrigger>
              <TabsTrigger value="tokens" className="px-2 text-xs">
                Token
              </TabsTrigger>
            </TabsList>
          </Tabs>
        </CardAction>
      </CardHeader>

      <CardContent className="flex-1">
        {entries.length === 0 ? (
          <div className="flex h-full min-h-[220px] flex-col items-center justify-center gap-2 text-center">
            <span className="flex size-10 items-center justify-center rounded-control-md bg-muted/50 text-muted-foreground">
              <TrophyIcon className="size-4" />
            </span>
            <p className="text-xs text-muted-foreground">
              近 {rangeDays} 天暂无消耗记录
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {entries.map((entry, index) => {
              const width = Math.max((entry.value / maxValue) * 100, 2);
              const share = total > 0 ? (entry.value / total) * 100 : 0;
              return (
                <div key={entry.userId} className="flex items-center gap-2">
                  <span className="w-4 shrink-0 text-right text-[11px] text-muted-foreground tabular-nums">
                    {index + 1}
                  </span>
                  <Avatar size="sm">
                    <AvatarFallback>{getInitials(entry.displayName)}</AvatarFallback>
                  </Avatar>
                  <div className="min-w-0 flex-1">
                    <div className="flex items-baseline justify-between gap-2">
                      <span className="truncate text-xs font-medium">
                        {entry.displayName || "未命名用户"}
                      </span>
                      <span
                        className="shrink-0 text-xs tabular-nums"
                        title={formatInteger(entry.value)}
                      >
                        {metric === "credits"
                          ? formatInteger(entry.value)
                          : formatCompactNumber(entry.value)}
                      </span>
                    </div>
                    <div className="mt-1 flex items-center gap-2">
                      <div className="h-1.5 flex-1 overflow-hidden rounded-pill bg-muted">
                        <motion.div
                          className="h-full rounded-pill bg-foreground"
                          initial={
                            reduceMotion ? false : { width: 0 }
                          }
                          animate={{ width: `${width}%` }}
                          transition={{ duration: 0.24, ease: EASE }}
                        />
                      </div>
                      <span className="w-10 shrink-0 text-right text-[10px] text-muted-foreground tabular-nums">
                        {share.toFixed(1)}%
                      </span>
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </CardContent>
    </Card>
  );
}
