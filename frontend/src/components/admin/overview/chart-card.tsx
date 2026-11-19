"use client";

import { useState, type ReactNode } from "react";
import { ChartNoAxesColumnIcon, Table2Icon, type LucideIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { cn } from "@/lib/utils";

export interface ChartTable {
  columns: Array<{ label: string; align?: "left" | "right" }>;
  rows: ReactNode[][];
}

interface ChartCardProps {
  icon: LucideIcon;
  title: string;
  description?: string;
  table?: ChartTable;
  children: ReactNode;
  className?: string;
}

export function ChartCard({
  icon: Icon,
  title,
  description,
  table,
  children,
  className,
}: ChartCardProps) {
  const [showTable, setShowTable] = useState(false);

  return (
    <Card size="sm" className={cn("flex h-full flex-col", className)}>
      <CardHeader>
        <div className="flex items-center gap-2">
          <span className="flex size-7 items-center justify-center rounded-control-md bg-muted/50 text-muted-foreground">
            <Icon className="size-3.5" />
          </span>
          <CardTitle className="text-sm">{title}</CardTitle>
        </div>
        {description ? (
          <CardDescription className="text-xs">{description}</CardDescription>
        ) : null}
        {table ? (
          <CardAction>
            <Button
              type="button"
              variant="ghost"
              size="icon-xs"
              aria-label={showTable ? "查看图表" : "查看数据表"}
              aria-pressed={showTable}
              onClick={() => setShowTable((current) => !current)}
            >
              {showTable ? <ChartNoAxesColumnIcon /> : <Table2Icon />}
            </Button>
          </CardAction>
        ) : null}
      </CardHeader>
      <CardContent className="flex min-h-0 flex-1 flex-col">
        {showTable && table ? <DataTableView table={table} /> : children}
      </CardContent>
    </Card>
  );
}

function DataTableView({ table }: { table: ChartTable }) {
  return (
    <div className="max-h-[252px] overflow-auto">
      <table className="w-full text-xs">
        <thead className="sticky top-0 z-[1] bg-card">
          <tr className="border-b border-border/60 text-muted-foreground">
            {table.columns.map((column) => (
              <th
                key={column.label}
                scope="col"
                className={cn(
                  "py-1.5 font-medium",
                  column.align === "right" ? "text-right" : "text-left"
                )}
              >
                {column.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-border/50">
          {table.rows.map((row, rowIndex) => (
            <tr key={rowIndex}>
              {row.map((cell, cellIndex) => (
                <td
                  key={cellIndex}
                  className={cn(
                    "py-1.5",
                    table.columns[cellIndex]?.align === "right"
                      ? "text-right tabular-nums"
                      : "text-left"
                  )}
                >
                  {cell}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
