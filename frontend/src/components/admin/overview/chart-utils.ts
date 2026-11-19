"use client";

import { useEffect, useRef, useState } from "react";

import { formatCompactNumber } from "@/lib/format";

export interface ChartMargin {
  top: number;
  right: number;
  bottom: number;
  left: number;
}

export const DEFAULT_MARGIN: ChartMargin = {
  top: 16,
  right: 16,
  bottom: 28,
  left: 52,
};

export interface Scale {
  max: number;
  ticks: number[];
}

/** Rounds an axis maximum up to a readable step count. */
export function createScale(maxValue: number, tickCount = 4): Scale {
  if (!Number.isFinite(maxValue) || maxValue <= 0) {
    return { max: 1, ticks: [0, 1] };
  }
  const rawStep = maxValue / tickCount;
  const exponent = Math.floor(Math.log10(rawStep));
  const fraction = rawStep / 10 ** exponent;
  const niceFraction =
    fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 2.5 ? 2.5 : fraction <= 5 ? 5 : 10;
  const step = niceFraction * 10 ** exponent;
  const max = Math.ceil(maxValue / step) * step;
  const ticks = Array.from({ length: Math.round(max / step) + 1 }, (_, index) =>
    Number((index * step).toFixed(6))
  );
  return { max, ticks };
}

export function formatAxisValue(value: number): string {
  if (value > 0 && value < 1) {
    return value.toFixed(1);
  }
  return formatCompactNumber(value);
}

export function formatShortDate(date: string): string {
  return date.slice(5);
}

export function formatLongDate(date: string): string {
  return date.replaceAll("-", "/");
}

export function movingAverage(values: number[], windowSize: number): number[] {
  return values.map((_, index) => {
    const start = Math.max(0, index - windowSize + 1);
    const slice = values.slice(start, index + 1);
    const sum = slice.reduce((total, value) => total + value, 0);
    return slice.length === 0 ? 0 : sum / slice.length;
  });
}

export function linePath(points: Array<{ x: number; y: number }>): string {
  if (points.length === 0) {
    return "";
  }
  return points
    .map((point, index) => `${index === 0 ? "M" : "L"}${point.x} ${point.y}`)
    .join(" ");
}

export function areaPath(points: Array<{ x: number; y: number }>, baseline: number): string {
  if (points.length === 0) {
    return "";
  }
  const first = points[0];
  const last = points[points.length - 1];
  return `${linePath(points)} L${last.x} ${baseline} L${first.x} ${baseline} Z`;
}

/** A bar whose top corners are rounded and whose baseline stays square. */
export function topRoundedBarPath(
  x: number,
  y: number,
  width: number,
  height: number,
  radius: number
): string {
  if (width <= 0 || height <= 0) {
    return "";
  }
  const r = Math.min(radius, width / 2, height);
  return [
    `M${x} ${y + height}`,
    `L${x} ${y + r}`,
    `Q${x} ${y} ${x + r} ${y}`,
    `L${x + width - r} ${y}`,
    `Q${x + width} ${y} ${x + width} ${y + r}`,
    `L${x + width} ${y + height}`,
    "Z",
  ].join(" ");
}

/** Measures a responsive chart container so SVG text stays at real pixel sizes. */
export function useElementWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [width, setWidth] = useState(0);

  useEffect(() => {
    const element = ref.current;
    if (!element) {
      return;
    }
    const observer = new ResizeObserver((entries) => {
      const next = entries[0]?.contentRect.width ?? 0;
      setWidth(next);
    });
    observer.observe(element);
    setWidth(element.getBoundingClientRect().width);
    return () => observer.disconnect();
  }, []);

  return [ref, width] as const;
}
