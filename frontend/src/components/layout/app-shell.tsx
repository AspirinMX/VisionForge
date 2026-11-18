"use client";

import { useMemo, useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  ClapperboardIcon,
  ListTodoIcon,
  MessageSquareIcon,
  PlusIcon,
  SearchIcon,
  WalletIcon,
} from "lucide-react";

import { SidebarUserMenu } from "@/components/layout/sidebar-user-menu";
import { useConversations } from "@/components/providers/conversations-provider";
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupContent,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInput,
  SidebarInset,
  SidebarMenu,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSkeleton,
  SidebarProvider,
  SidebarRail,
  SidebarSeparator,
  useSidebar,
} from "@/components/ui/sidebar";
import type { ConversationResponse } from "@/lib/api/types";

interface ConversationBucket {
  label: string;
  items: ConversationResponse[];
}

function groupConversations(
  items: ConversationResponse[],
  query: string
): ConversationBucket[] {
  const normalized = query.trim().toLowerCase();
  const filtered = normalized
    ? items.filter((item) => item.title.toLowerCase().includes(normalized))
    : items;

  const startOfToday = new Date();
  startOfToday.setHours(0, 0, 0, 0);
  const todayMs = startOfToday.getTime();
  const sevenDaysMs = todayMs - 6 * 24 * 60 * 60 * 1000;

  const today: ConversationResponse[] = [];
  const last7Days: ConversationResponse[] = [];
  const earlier: ConversationResponse[] = [];

  for (const item of filtered) {
    const timestamp = Date.parse(item.lastMessageAt ?? item.createdAt);
    if (Number.isNaN(timestamp) || timestamp >= todayMs) {
      today.push(item);
    } else if (timestamp >= sevenDaysMs) {
      last7Days.push(item);
    } else {
      earlier.push(item);
    }
  }

  return [
    { label: "今天", items: today },
    { label: "近 7 天", items: last7Days },
    { label: "更早", items: earlier },
  ].filter((bucket) => bucket.items.length > 0);
}

function AppSidebarContent() {
  const pathname = usePathname();
  const { conversations, loading } = useConversations();
  const { isMobile, setOpenMobile } = useSidebar();
  const [search, setSearch] = useState("");

  const buckets = useMemo(
    () => groupConversations(conversations, search),
    [conversations, search]
  );

  const closeMobileIfNeeded = () => {
    if (isMobile) {
      setOpenMobile(false);
    }
  };

  return (
    <Sidebar collapsible="icon" variant="sidebar">
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              tooltip="VisionForge"
              render={<Link href="/conversations" onClick={closeMobileIfNeeded} />}
            >
              <ClapperboardIcon />
              <span className="font-semibold tracking-tight">VisionForge</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
          <SidebarMenuItem>
            <SidebarMenuButton
              tooltip="新聊天"
              isActive={pathname === "/conversations"}
              render={<Link href="/conversations" onClick={closeMobileIfNeeded} />}
            >
              <PlusIcon />
              <span>新聊天</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>

        <div className="relative group-data-[collapsible=icon]:hidden">
          <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <SidebarInput
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="搜索历史对话..."
            aria-label="搜索历史对话"
            className="pl-8"
          />
        </div>
      </SidebarHeader>

      <SidebarContent>
        {loading ? (
          <SidebarGroup>
            <SidebarGroupLabel>会话历史</SidebarGroupLabel>
            <SidebarGroupContent>
              <SidebarMenu>
                <SidebarMenuSkeleton showIcon />
                <SidebarMenuSkeleton showIcon />
                <SidebarMenuSkeleton showIcon />
                <SidebarMenuSkeleton showIcon />
              </SidebarMenu>
            </SidebarGroupContent>
          </SidebarGroup>
        ) : buckets.length === 0 ? (
          <SidebarGroup className="group-data-[collapsible=icon]:hidden">
            <SidebarGroupLabel>会话历史</SidebarGroupLabel>
            <SidebarGroupContent>
              <p className="px-2 py-3 text-xs text-muted-foreground">
                {search.trim()
                  ? "未找到匹配的会话标题。"
                  : "暂无历史会话，在右侧输入第一句话即可开始创作。"}
              </p>
            </SidebarGroupContent>
          </SidebarGroup>
        ) : (
          buckets.map((bucket) => (
            <SidebarGroup key={bucket.label}>
              <SidebarGroupLabel>{bucket.label}</SidebarGroupLabel>
              <SidebarGroupContent>
                <SidebarMenu>
                  {bucket.items.map((conversation) => {
                    const href = `/conversations/${conversation.id}`;
                    return (
                      <SidebarMenuItem key={conversation.id}>
                        <SidebarMenuButton
                          tooltip={conversation.title}
                          isActive={pathname === href}
                          render={
                            <Link href={href} onClick={closeMobileIfNeeded} />
                          }
                        >
                          <MessageSquareIcon />
                          <span>{conversation.title}</span>
                        </SidebarMenuButton>
                      </SidebarMenuItem>
                    );
                  })}
                </SidebarMenu>
              </SidebarGroupContent>
            </SidebarGroup>
          ))
        )}
      </SidebarContent>

      <SidebarFooter>
        <SidebarSeparator />
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              tooltip="全局任务中心"
              isActive={pathname === "/tasks"}
              render={<Link href="/tasks" onClick={closeMobileIfNeeded} />}
            >
              <ListTodoIcon />
              <span>全局任务中心</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
          <SidebarMenuItem>
            <SidebarMenuButton
              tooltip="账户与额度"
              isActive={pathname === "/account"}
              render={<Link href="/account" onClick={closeMobileIfNeeded} />}
            >
              <WalletIcon />
              <span>账户与额度</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>

        <SidebarUserMenu mode="studio" onNavigate={closeMobileIfNeeded} />
      </SidebarFooter>
      <SidebarRail />
    </Sidebar>
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  return (
    <SidebarProvider>
      <AppSidebarContent />
      <SidebarInset className="flex h-svh min-w-0 flex-col overflow-hidden">
        {children}
      </SidebarInset>
    </SidebarProvider>
  );
}
