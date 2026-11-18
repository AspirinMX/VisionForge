"use client";

import { Fragment, useState, type ReactNode } from "react";
import { CheckIcon, CopyIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { copyToClipboard } from "@/lib/api/feedback";
import { cn } from "@/lib/utils";

function renderInline(text: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const tokenRegex = /(`[^`]+`|\*\*[^*]+\*\*)/g;
  let lastIndex = 0;
  let match: RegExpExecArray | null;
  let key = 0;

  while ((match = tokenRegex.exec(text)) !== null) {
    if (match.index > lastIndex) {
      nodes.push(text.slice(lastIndex, match.index));
    }
    const token = match[0];
    if (token.startsWith("`") && token.endsWith("`")) {
      nodes.push(
        <code
          key={key++}
          className="rounded-md bg-muted px-1.5 py-0.5 font-mono text-[0.85em]"
        >
          {token.slice(1, -1)}
        </code>
      );
    } else if (token.startsWith("**") && token.endsWith("**")) {
      nodes.push(
        <strong key={key++} className="font-semibold text-foreground">
          {token.slice(2, -2)}
        </strong>
      );
    }
    lastIndex = tokenRegex.lastIndex;
  }

  if (lastIndex < text.length) {
    nodes.push(text.slice(lastIndex));
  }
  return nodes;
}

function CodeBlock({ language, code }: { language: string; code: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = async () => {
    const ok = await copyToClipboard(code);
    if (ok) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    }
  };

  return (
    <div className="my-2 overflow-hidden rounded-xl border bg-muted/40">
      <div className="flex items-center justify-between border-b px-3 py-1 text-[11px] text-muted-foreground">
        <span className="font-mono uppercase">{language || "code"}</span>
        <Button
          type="button"
          variant="ghost"
          size="xs"
          onClick={() => void handleCopy()}
        >
          {copied ? <CheckIcon /> : <CopyIcon />}
          <span>{copied ? "已复制" : "复制"}</span>
        </Button>
      </div>
      <pre className="overflow-x-auto p-3 font-mono text-xs leading-relaxed">
        <code>{code}</code>
      </pre>
    </div>
  );
}

const TABLE_SEPARATOR_RE = /^\s*\|?(?:\s*:?-{3,}:?\s*\|)+\s*(?::?-{3,}:?\s*\|?)?\s*$/;

function splitTableRow(line: string): string[] {
  const trimmed = line.trim();
  const stripped = trimmed.replace(/^\|/, "").replace(/\|$/, "");
  return stripped.split("|").map((cell) => cell.trim());
}

function isTableStart(lines: string[], index: number): boolean {
  if (index + 1 >= lines.length) {
    return false;
  }
  return (
    lines[index].includes("|") &&
    TABLE_SEPARATOR_RE.test(lines[index + 1]) &&
    splitTableRow(lines[index]).length > 0
  );
}

interface MarkdownViewProps {
  content: string;
  className?: string;
}

export function MarkdownView({ content, className }: MarkdownViewProps) {
  const lines = content.replace(/\r\n/g, "\n").split("\n");
  const blocks: ReactNode[] = [];
  let index = 0;
  let blockKey = 0;

  while (index < lines.length) {
    const line = lines[index];

    if (line.startsWith("```")) {
      const language = line.slice(3).trim();
      const codeLines: string[] = [];
      index += 1;
      while (index < lines.length && !lines[index].startsWith("```")) {
        codeLines.push(lines[index]);
        index += 1;
      }
      index += 1;
      blocks.push(
        <CodeBlock
          key={blockKey++}
          language={language}
          code={codeLines.join("\n")}
        />
      );
      continue;
    }

    if (!line.trim()) {
      index += 1;
      continue;
    }

    const headingMatch = /^(#{1,4})\s+(.+)$/.exec(line);
    if (headingMatch) {
      const level = headingMatch[1].length;
      const title = renderInline(headingMatch[2]);
      const headingClass =
        level === 1
          ? "mt-3 mb-1.5 text-lg font-semibold tracking-tight"
          : level === 2
            ? "mt-3 mb-1 text-base font-semibold tracking-tight"
            : "mt-2 mb-1 text-sm font-semibold";
      blocks.push(
        <div key={blockKey++} className={headingClass}>
          {title}
        </div>
      );
      index += 1;
      continue;
    }

    if (/^[-*]\s+/.test(line)) {
      const items: string[] = [];
      while (index < lines.length && /^[-*]\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^[-*]\s+/, ""));
        index += 1;
      }
      blocks.push(
        <ul key={blockKey++} className="my-1.5 list-disc space-y-1 pl-5">
          {items.map((item, idx) => (
            <li key={idx} className="leading-relaxed">
              {renderInline(item)}
            </li>
          ))}
        </ul>
      );
      continue;
    }

    if (/^\d+\.\s+/.test(line)) {
      const items: string[] = [];
      while (index < lines.length && /^\d+\.\s+/.test(lines[index])) {
        items.push(lines[index].replace(/^\d+\.\s+/, ""));
        index += 1;
      }
      blocks.push(
        <ol key={blockKey++} className="my-1.5 list-decimal space-y-1 pl-5">
          {items.map((item, idx) => (
            <li key={idx} className="leading-relaxed">
              {renderInline(item)}
            </li>
          ))}
        </ol>
      );
      continue;
    }

    if (isTableStart(lines, index)) {
      const headers = splitTableRow(lines[index]);
      const alignRow = splitTableRow(lines[index + 1]).map((cell) => {
        if (cell.startsWith(":") && cell.endsWith(":")) {
          return "text-center";
        }
        if (cell.endsWith(":")) {
          return "text-right";
        }
        return "text-left";
      });
      index += 2;
      const rows: string[][] = [];
      while (
        index < lines.length &&
        lines[index].trim() &&
        lines[index].includes("|") &&
        !lines[index].startsWith("```")
      ) {
        rows.push(splitTableRow(lines[index]));
        index += 1;
      }
      blocks.push(
        <div key={blockKey++} className="my-3">
          <Table>
            <TableHeader className="[&_tr]:border-border/70">
              <TableRow className="hover:bg-transparent">
                {headers.map((header, cellIdx) => (
                  <TableHead
                    key={cellIdx}
                    className={cn(
                      "h-9 px-2.5 text-xs font-semibold text-foreground whitespace-normal",
                      alignRow[cellIdx] ?? "text-left"
                    )}
                  >
                    {renderInline(header)}
                  </TableHead>
                ))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.map((row, rowIdx) => (
                <TableRow
                  key={rowIdx}
                  className="border-border/50 hover:bg-muted/30"
                >
                  {headers.map((_, cellIdx) => (
                    <TableCell
                      key={cellIdx}
                      className={cn(
                        "px-2.5 py-2.5 text-sm whitespace-normal",
                        alignRow[cellIdx] ?? "text-left"
                      )}
                    >
                      {renderInline(row[cellIdx] ?? "")}
                    </TableCell>
                  ))}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      );
      continue;
    }

    if (line.startsWith("> ")) {
      const quotes: string[] = [];
      while (index < lines.length && lines[index].startsWith("> ")) {
        quotes.push(lines[index].slice(2));
        index += 1;
      }
      blocks.push(
        <blockquote
          key={blockKey++}
          className="my-2 border-l border-border pl-3 text-muted-foreground"
        >
          {quotes.map((q, idx) => (
            <Fragment key={idx}>
              {idx > 0 ? <br /> : null}
              {renderInline(q)}
            </Fragment>
          ))}
        </blockquote>
      );
      continue;
    }

    const paragraphLines: string[] = [];
    while (
      index < lines.length &&
      lines[index].trim() &&
      !lines[index].startsWith("```") &&
      !/^#{1,4}\s+/.test(lines[index]) &&
      !/^[-*]\s+/.test(lines[index]) &&
      !/^\d+\.\s+/.test(lines[index]) &&
      !lines[index].startsWith("> ") &&
      !isTableStart(lines, index)
    ) {
      paragraphLines.push(lines[index]);
      index += 1;
    }

    blocks.push(
      <p key={blockKey++} className="my-1 leading-relaxed break-words">
        {paragraphLines.map((pLine, idx) => (
          <Fragment key={idx}>
            {idx > 0 ? <br /> : null}
            {renderInline(pLine)}
          </Fragment>
        ))}
      </p>
    );
  }

  return (
    <div className={cn("text-sm text-foreground", className)}>{blocks}</div>
  );
}
