"use client";

import { useState, type ReactNode } from "react";
import { motion, useReducedMotion } from "motion/react";

import type { AdminDashboardDailyPoint } from "@/lib/api/types";
import { formatCompactNumber, formatInteger, formatPercent } from "@/lib/format";
import { cn } from "@/lib/utils";

import {
  DEFAULT_MARGIN,
  areaPath,
  createScale,
  formatAxisValue,
  formatLongDate,
  formatShortDate,
  linePath,
  movingAverage,
  topRoundedBarPath,
  useElementWidth,
} from "./chart-utils";

const EASE = [0.16, 1, 0.3, 1] as const;
const CHART_HEIGHT = 260;

interface LegendItem {
  label: string;
  color: string;
}

function Legend({ items }: { items: LegendItem[] }) {
  return (
    <div className="mb-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px] text-muted-foreground">
      {items.map((item) => (
        <span key={item.label} className="inline-flex items-center gap-1.5">
          <span
            className="size-2 rounded-[2px]"
            style={{ backgroundColor: item.color }}
          />
          {item.label}
        </span>
      ))}
    </div>
  );
}

function ChartTooltip({
  x,
  containerWidth,
  children,
}: {
  x: number;
  containerWidth: number;
  children: ReactNode;
}) {
  const left = Math.min(Math.max(x, 88), Math.max(88, containerWidth - 88));
  return (
    <div
      className="pointer-events-none absolute top-7 z-10 w-44 -translate-x-1/2 rounded-surface-sm border border-border/60 bg-popover/95 px-3 py-2 text-xs shadow-lg backdrop-blur-sm"
      style={{ left }}
    >
      {children}
    </div>
  );
}

function AxisLabel({
  x,
  y,
  children,
  anchor = "middle",
}: {
  x: number;
  y: number;
  children: ReactNode;
  anchor?: "start" | "middle" | "end";
}) {
  return (
    <text
      x={x}
      y={y}
      textAnchor={anchor}
      className="fill-muted-foreground text-[10px] tabular-nums"
    >
      {children}
    </text>
  );
}

export function Sparkline({
  values,
  className,
}: {
  values: number[];
  className?: string;
}) {
  const [ref, width] = useElementWidth<HTMLDivElement>();
  const reduceMotion = useReducedMotion();
  const height = 40;
  const max = Math.max(...values, 0);
  const points = values.map((value, index) => ({
    x: values.length <= 1 ? 0 : (index / (values.length - 1)) * width,
    y:
      max <= 0
        ? height - 2
        : height - 2 - (Math.max(value, 0) / max) * (height - 6),
  }));

  return (
    <div ref={ref} className={cn("h-10 w-full", className)}>
      {width > 0 && values.length > 1 ? (
        <svg width={width} height={height} aria-hidden="true">
          <path
            d={areaPath(points, height)}
            fill="var(--foreground)"
            opacity={0.07}
          />
          <motion.path
            d={linePath(points)}
            fill="none"
            stroke="var(--foreground)"
            strokeWidth={1.5}
            strokeLinecap="round"
            strokeLinejoin="round"
            initial={reduceMotion ? false : { pathLength: 0 }}
            animate={{ pathLength: 1 }}
            transition={{ duration: 0.4, ease: EASE }}
          />
        </svg>
      ) : null}
    </div>
  );
}

export function BillingTrendChart({
  daily,
}: {
  daily: AdminDashboardDailyPoint[];
}) {
  const [ref, width] = useElementWidth<HTMLDivElement>();
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);
  const reduceMotion = useReducedMotion();
  const margin = DEFAULT_MARGIN;
  const innerWidth = Math.max(0, width - margin.left - margin.right);
  const innerHeight = CHART_HEIGHT - margin.top - margin.bottom;
  const values = daily.map((point) => point.settledCredits);
  const scale = createScale(Math.max(...values, 0), 4);
  const band = daily.length > 0 ? innerWidth / daily.length : 0;
  const barWidth = Math.min(24, Math.max(2, band * 0.55));
  const baseline = margin.top + innerHeight;
  const xAt = (index: number) => margin.left + band * index + band / 2;
  const yAt = (value: number) => baseline - (value / scale.max) * innerHeight;
  const averages = movingAverage(values, 7);
  const averagePoints = averages.map((value, index) => ({
    x: xAt(index),
    y: yAt(value),
  }));
  const maxIndex = values.indexOf(Math.max(...values, 0));

  const handleMove = (event: React.PointerEvent<SVGRectElement>) => {
    if (band <= 0 || daily.length === 0) {
      return;
    }
    const bounds = event.currentTarget.ownerSVGElement?.getBoundingClientRect();
    if (!bounds) {
      return;
    }
    const relative = event.clientX - bounds.left - margin.left;
    const next = Math.floor(relative / band);
    setHoverIndex(Math.min(Math.max(next, 0), daily.length - 1));
  };

  return (
    <div ref={ref} className="relative w-full">
      <Legend
        items={[
          { label: "已结算额度", color: "var(--foreground)" },
          { label: "7 日均线", color: "var(--muted-foreground)" },
        ]}
      />
      {width > 0 ? (
        <svg
          width={width}
          height={CHART_HEIGHT}
          role="img"
          aria-label={`近 ${daily.length} 天额度消耗趋势`}
        >
          {scale.ticks.map((tick) => (
            <g key={tick}>
              <line
                x1={margin.left}
                x2={width - margin.right}
                y1={yAt(tick)}
                y2={yAt(tick)}
                stroke="var(--border)"
                strokeWidth={1}
              />
              <AxisLabel x={margin.left - 8} y={yAt(tick) + 3} anchor="end">
                {formatAxisValue(tick)}
              </AxisLabel>
            </g>
          ))}

          {daily.map((point, index) => {
            if (point.settledCredits <= 0) {
              return null;
            }
            const y = yAt(point.settledCredits);
            return (
              <motion.path
                key={point.date}
                d={topRoundedBarPath(
                  xAt(index) - barWidth / 2,
                  y,
                  barWidth,
                  baseline - y,
                  4
                )}
                fill="var(--foreground)"
                initial={reduceMotion ? false : { opacity: 0, scaleY: 0 }}
                animate={{ opacity: 1, scaleY: 1 }}
                transition={{ duration: 0.24, ease: EASE }}
                style={{
                  transformBox: "view-box",
                  transformOrigin: `${xAt(index)}px ${baseline}px`,
                }}
              />
            );
          })}

          <motion.path
            d={linePath(averagePoints)}
            fill="none"
            stroke="var(--muted-foreground)"
            strokeWidth={2}
            strokeLinecap="round"
            strokeLinejoin="round"
            initial={reduceMotion ? false : { pathLength: 0 }}
            animate={{ pathLength: 1 }}
            transition={{ duration: 0.24, ease: EASE }}
          />

          {maxIndex >= 0 && values[maxIndex] > 0 ? (
            <AxisLabel x={xAt(maxIndex)} y={yAt(values[maxIndex]) - 6}>
              {formatCompactNumber(values[maxIndex])}
            </AxisLabel>
          ) : null}

          {daily.map((point, index) => {
            const labelStep = Math.max(1, Math.ceil(daily.length / 6));
            if (index % labelStep !== 0 && index !== daily.length - 1) {
              return null;
            }
            return (
              <AxisLabel
                key={point.date}
                x={xAt(index)}
                y={CHART_HEIGHT - 8}
              >
                {formatShortDate(point.date)}
              </AxisLabel>
            );
          })}

          {hoverIndex !== null ? (
            <line
              x1={xAt(hoverIndex)}
              x2={xAt(hoverIndex)}
              y1={margin.top}
              y2={baseline}
              stroke="var(--border)"
              strokeWidth={1}
            />
          ) : null}

          <rect
            x={margin.left}
            y={margin.top}
            width={innerWidth}
            height={innerHeight}
            fill="transparent"
            onPointerMove={handleMove}
            onPointerLeave={() => setHoverIndex(null)}
          />
        </svg>
      ) : (
        <div style={{ height: CHART_HEIGHT }} />
      )}

      {hoverIndex !== null && daily[hoverIndex] ? (
        <ChartTooltip x={xAt(hoverIndex)} containerWidth={width}>
          <p className="font-medium text-foreground">
            {formatLongDate(daily[hoverIndex].date)}
          </p>
          <div className="mt-1 flex items-center justify-between gap-3 text-muted-foreground">
            <span>已结算额度</span>
            <span className="tabular-nums text-foreground">
              {formatInteger(daily[hoverIndex].settledCredits)}
            </span>
          </div>
          <div className="mt-0.5 flex items-center justify-between gap-3 text-muted-foreground">
            <span>7 日均线</span>
            <span className="tabular-nums text-foreground">
              {formatInteger(Math.round(averages[hoverIndex] ?? 0))}
            </span>
          </div>
        </ChartTooltip>
      ) : null}
    </div>
  );
}

export function TokenTrendChart({
  daily,
}: {
  daily: AdminDashboardDailyPoint[];
}) {
  const [ref, width] = useElementWidth<HTMLDivElement>();
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);
  const reduceMotion = useReducedMotion();
  const margin = { ...DEFAULT_MARGIN, right: 64 };
  const innerWidth = Math.max(0, width - margin.left - margin.right);
  const innerHeight = CHART_HEIGHT - margin.top - margin.bottom;
  const series = [
    {
      key: "inputTokens",
      label: "输入 Token",
      color: "var(--muted-foreground)",
    },
    {
      key: "outputTokens",
      label: "输出 Token",
      color: "var(--foreground)",
    },
  ] as const;
  const maxValue = Math.max(
    ...daily.flatMap((point) => [point.inputTokens, point.outputTokens]),
    0
  );
  const scale = createScale(maxValue, 4);
  const band = daily.length > 0 ? innerWidth / daily.length : 0;
  const baseline = margin.top + innerHeight;
  const xAt = (index: number) =>
    margin.left + band * index + (daily.length > 1 ? band / 2 : 0);
  const yAt = (value: number) => baseline - (value / scale.max) * innerHeight;
  const lastIndex = daily.length - 1;
  const lastValues = daily[lastIndex];
  const lastYs = lastValues
    ? series.map((item) => yAt(lastValues[item.key]))
    : [0, 0];

  const handleMove = (event: React.PointerEvent<SVGRectElement>) => {
    if (band <= 0 || daily.length === 0) {
      return;
    }
    const bounds = event.currentTarget.ownerSVGElement?.getBoundingClientRect();
    if (!bounds) {
      return;
    }
    const relative = event.clientX - bounds.left - margin.left;
    const next = Math.floor(relative / band);
    setHoverIndex(Math.min(Math.max(next, 0), daily.length - 1));
  };

  return (
    <div ref={ref} className="relative w-full">
      <Legend
        items={series.map((item) => ({
          label: item.label,
          color: item.color,
        }))}
      />
      {width > 0 ? (
        <svg
          width={width}
          height={CHART_HEIGHT}
          role="img"
          aria-label={`近 ${daily.length} 天对话 Token 消耗趋势`}
        >
          {scale.ticks.map((tick) => (
            <g key={tick}>
              <line
                x1={margin.left}
                x2={width - margin.right}
                y1={yAt(tick)}
                y2={yAt(tick)}
                stroke="var(--border)"
                strokeWidth={1}
              />
              <AxisLabel x={margin.left - 8} y={yAt(tick) + 3} anchor="end">
                {formatAxisValue(tick)}
              </AxisLabel>
            </g>
          ))}

          {series.map((item) => (
            <motion.path
              key={item.key}
              d={linePath(
                daily.map((point, index) => ({
                  x: xAt(index),
                  y: yAt(point[item.key]),
                }))
              )}
              fill="none"
              stroke={item.color}
              strokeWidth={2}
              strokeLinecap="round"
              strokeLinejoin="round"
              initial={reduceMotion ? false : { pathLength: 0 }}
              animate={{ pathLength: 1 }}
              transition={{ duration: 0.24, ease: EASE }}
            />
          ))}

          {daily.map((point, index) => {
            const labelStep = Math.max(1, Math.ceil(daily.length / 6));
            if (index % labelStep !== 0 && index !== daily.length - 1) {
              return null;
            }
            return (
              <AxisLabel
                key={point.date}
                x={xAt(index)}
                y={CHART_HEIGHT - 8}
              >
                {formatShortDate(point.date)}
              </AxisLabel>
            );
          })}

          {lastValues && Math.abs(lastYs[0] - lastYs[1]) >= 12
            ? series.map((item, index) => (
                <AxisLabel
                  key={item.key}
                  x={width - margin.right + 6}
                  y={lastYs[index] + 3}
                  anchor="start"
                >
                  {formatCompactNumber(lastValues[item.key])}
                </AxisLabel>
              ))
            : null}

          {hoverIndex !== null ? (
            <>
              <line
                x1={xAt(hoverIndex)}
                x2={xAt(hoverIndex)}
                y1={margin.top}
                y2={baseline}
                stroke="var(--border)"
                strokeWidth={1}
              />
              {series.map((item) => (
                <circle
                  key={item.key}
                  cx={xAt(hoverIndex)}
                  cy={yAt(daily[hoverIndex][item.key])}
                  r={4}
                  fill={item.color}
                  stroke="var(--card)"
                  strokeWidth={2}
                />
              ))}
            </>
          ) : null}

          <rect
            x={margin.left}
            y={margin.top}
            width={innerWidth}
            height={innerHeight}
            fill="transparent"
            onPointerMove={handleMove}
            onPointerLeave={() => setHoverIndex(null)}
          />
        </svg>
      ) : (
        <div style={{ height: CHART_HEIGHT }} />
      )}

      {hoverIndex !== null && daily[hoverIndex] ? (
        <ChartTooltip x={xAt(hoverIndex)} containerWidth={width}>
          <p className="font-medium text-foreground">
            {formatLongDate(daily[hoverIndex].date)}
          </p>
          {series.map((item) => (
            <div
              key={item.key}
              className="mt-1 flex items-center justify-between gap-3 text-muted-foreground"
            >
              <span>{item.label}</span>
              <span className="tabular-nums text-foreground">
                {formatInteger(daily[hoverIndex][item.key])}
              </span>
            </div>
          ))}
          <div className="mt-1 flex items-center justify-between gap-3 border-t border-border/50 pt-1 text-muted-foreground">
            <span>成功调用</span>
            <span className="tabular-nums text-foreground">
              {formatInteger(daily[hoverIndex].invocations)}
            </span>
          </div>
        </ChartTooltip>
      ) : null}
    </div>
  );
}

export function TaskOutcomeChart({
  daily,
}: {
  daily: AdminDashboardDailyPoint[];
}) {
  const [ref, width] = useElementWidth<HTMLDivElement>();
  const [hoverIndex, setHoverIndex] = useState<number | null>(null);
  const margin = DEFAULT_MARGIN;
  const innerWidth = Math.max(0, width - margin.left - margin.right);
  const innerHeight = CHART_HEIGHT - margin.top - margin.bottom;
  const series = [
    { key: "succeeded", label: "完成", color: "var(--success)" },
    { key: "failed", label: "失败", color: "var(--destructive)" },
    { key: "cancelled", label: "取消", color: "var(--muted-foreground)" },
  ] as const;
  const totals = daily.map(
    (point) => point.succeeded + point.failed + point.cancelled
  );
  const scale = createScale(Math.max(...totals, 0), 4);
  const band = daily.length > 0 ? innerWidth / daily.length : 0;
  const barWidth = Math.min(24, Math.max(2, band * 0.55));
  const baseline = margin.top + innerHeight;
  const xAt = (index: number) => margin.left + band * index + band / 2;
  const yHeight = (value: number) => (value / scale.max) * innerHeight;

  const handleMove = (event: React.PointerEvent<SVGRectElement>) => {
    if (band <= 0 || daily.length === 0) {
      return;
    }
    const bounds = event.currentTarget.ownerSVGElement?.getBoundingClientRect();
    if (!bounds) {
      return;
    }
    const relative = event.clientX - bounds.left - margin.left;
    const next = Math.floor(relative / band);
    setHoverIndex(Math.min(Math.max(next, 0), daily.length - 1));
  };

  return (
    <div ref={ref} className="relative w-full">
      <Legend
        items={series.map((item) => ({
          label: item.label,
          color: item.color,
        }))}
      />
      {width > 0 ? (
        <svg
          width={width}
          height={CHART_HEIGHT}
          role="img"
          aria-label={`近 ${daily.length} 天媒体生成任务结果`}
        >
          {scale.ticks.map((tick) => {
            const y = baseline - yHeight(tick);
            return (
              <g key={tick}>
                <line
                  x1={margin.left}
                  x2={width - margin.right}
                  y1={y}
                  y2={y}
                  stroke="var(--border)"
                  strokeWidth={1}
                />
                <AxisLabel x={margin.left - 8} y={y + 3} anchor="end">
                  {formatAxisValue(tick)}
                </AxisLabel>
              </g>
            );
          })}

          {daily.map((point, dayIndex) => {
            let cursor = baseline;
            const activeSeries = series.filter(
              (item) => point[item.key] > 0
            );
            return (
              <g key={point.date}>
                {activeSeries.map((item, index) => {
                  const value = point[item.key];
                  const height = yHeight(value);
                  const isTop = index === activeSeries.length - 1;
                  const drawnHeight = isTop
                    ? height
                    : Math.max(0, height - 2);
                  const y = cursor - drawnHeight;
                  const segment = (
                    <path
                      key={item.key}
                      d={topRoundedBarPath(
                        xAt(dayIndex) - barWidth / 2,
                        y,
                        barWidth,
                        drawnHeight,
                        isTop ? 4 : 0
                      )}
                      fill={item.color}
                    />
                  );
                  cursor -= height;
                  return segment;
                })}
              </g>
            );
          })}

          {daily.map((point, index) => {
            const labelStep = Math.max(1, Math.ceil(daily.length / 6));
            if (index % labelStep !== 0 && index !== daily.length - 1) {
              return null;
            }
            return (
              <AxisLabel
                key={point.date}
                x={xAt(index)}
                y={CHART_HEIGHT - 8}
              >
                {formatShortDate(point.date)}
              </AxisLabel>
            );
          })}

          {hoverIndex !== null ? (
            <line
              x1={xAt(hoverIndex)}
              x2={xAt(hoverIndex)}
              y1={margin.top}
              y2={baseline}
              stroke="var(--border)"
              strokeWidth={1}
            />
          ) : null}

          <rect
            x={margin.left}
            y={margin.top}
            width={innerWidth}
            height={innerHeight}
            fill="transparent"
            onPointerMove={handleMove}
            onPointerLeave={() => setHoverIndex(null)}
          />
        </svg>
      ) : (
        <div style={{ height: CHART_HEIGHT }} />
      )}

      {hoverIndex !== null && daily[hoverIndex] ? (
        <ChartTooltip x={xAt(hoverIndex)} containerWidth={width}>
          <p className="font-medium text-foreground">
            {formatLongDate(daily[hoverIndex].date)}
          </p>
          {series.map((item) => (
            <div
              key={item.key}
              className="mt-1 flex items-center justify-between gap-3 text-muted-foreground"
            >
              <span>{item.label}</span>
              <span className="tabular-nums text-foreground">
                {formatInteger(daily[hoverIndex][item.key])}
              </span>
            </div>
          ))}
          <div className="mt-1 flex items-center justify-between gap-3 border-t border-border/50 pt-1 text-muted-foreground">
            <span>成功率</span>
            <span className="tabular-nums text-foreground">
              {formatPercent(
                totals[hoverIndex] > 0
                  ? daily[hoverIndex].succeeded / totals[hoverIndex]
                  : null
              )}
            </span>
          </div>
        </ChartTooltip>
      ) : null}
    </div>
  );
}
