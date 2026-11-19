"use client";

import { ChevronLeftIcon, ChevronRightIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

interface TablePaginationProps {
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
  itemLabel?: string;
  className?: string;
}

export function TablePagination({
  page,
  pageSize,
  total,
  onPageChange,
  itemLabel = "条",
  className,
}: TablePaginationProps) {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(Math.max(page, 1), totalPages);

  return (
    <div
      className={cn(
        "flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground",
        className
      )}
    >
      <span className="tabular-nums">
        第 {safePage} / {totalPages} 页 · 共 {total} {itemLabel}
      </span>
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={safePage <= 1}
          onClick={() => onPageChange(safePage - 1)}
        >
          <ChevronLeftIcon />
          <span>上一页</span>
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={safePage >= totalPages}
          onClick={() => onPageChange(safePage + 1)}
        >
          <span>下一页</span>
          <ChevronRightIcon />
        </Button>
      </div>
    </div>
  );
}
