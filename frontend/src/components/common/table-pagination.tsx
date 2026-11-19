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
  /** Whether more records can still be fetched from the server. */
  hasMore?: boolean;
  /** Disable page changes while the next page is loading. */
  busy?: boolean;
}

export function TablePagination({
  page,
  pageSize,
  total,
  onPageChange,
  itemLabel = "条",
  className,
  hasMore = false,
  busy = false,
}: TablePaginationProps) {
  const totalPages = Math.max(1, Math.ceil(total / pageSize));
  const safePage = Math.min(Math.max(page, 1), totalPages);
  const canGoNext = safePage < totalPages || hasMore;

  return (
    <div
      className={cn(
        "flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground",
        className
      )}
    >
      <span className="tabular-nums">
        {hasMore
          ? `第 ${safePage} 页 · 已加载 ${total} ${itemLabel}（还有更多）`
          : `第 ${safePage} / ${totalPages} 页 · 共 ${total} ${itemLabel}`}
      </span>
      <div className="flex items-center gap-2">
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={safePage <= 1 || busy}
          onClick={() => onPageChange(safePage - 1)}
        >
          <ChevronLeftIcon />
          <span>上一页</span>
        </Button>
        <Button
          type="button"
          variant="outline"
          size="sm"
          disabled={!canGoNext || busy}
          onClick={() => onPageChange(safePage + 1)}
        >
          <span>{busy ? "加载中..." : "下一页"}</span>
          <ChevronRightIcon />
        </Button>
      </div>
    </div>
  );
}
