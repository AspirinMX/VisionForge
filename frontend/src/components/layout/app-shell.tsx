"use client";

import { useMemo, useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  ChevronsUpDownIcon,
  ClapperboardIcon,
  CpuIcon,
  ListTodoIcon,
  LogOutIcon,
  MessageSquareIcon,
  MonitorIcon,
  MoonIcon,
  PlusIcon,
  SearchIcon,
  SunIcon,
  UsersIcon,
  WalletIcon,
} from "lucide-react";
import { useTheme } from "next-themes";

import { useAuth } from "@/components/providers/auth-provider";
import { useConversations } from "@/components/providers/conversations-provider";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Badge } from "@/components/ui/badge";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
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
import { getInitials } from "@/lib/format";

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
  const router = useRouter();
  const { user, logout } = useAuth();
  const { conversations, loading } = useConversations();
  const { isMobile, setOpenMobile } = useSidebar();
  const { theme, setTheme } = useTheme();
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

  const handleLogout = async () => {
    await logout();
    router.replace("/login");
  };

  const isAdmin = user?.role === "ADMIN";

  return (
    <Sidebar collapsible="icon" variant="sidebar">
      <SidebarHeader>
        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              size="lg"
              tooltip="VisionForge · 幻影工坊"
              render={<Link href="/conversations" onClick={closeMobileIfNeeded} />}
            >
              <div className="flex size-8 shrink-0 items-center justify-center rounded-xl bg-primary text-primary-foreground">
                <ClapperboardIcon className="size-4" />
              </div>
              <div className="grid flex-1 text-left text-sm leading-tight">
                <span className="truncate font-semibold">VisionForge</span>
                <span className="truncate text-xs text-muted-foreground">
                  幻影工坊
                </span>
              </div>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>

        <SidebarMenu>
          <SidebarMenuItem>
            <SidebarMenuButton
              variant="outline"
              tooltip="新建对话"
              isActive={pathname === "/conversations"}
              render={<Link href="/conversations" onClick={closeMobileIfNeeded} />}
            >
              <PlusIcon />
              <span>新建对话</span>
            </SidebarMenuButton>
          </SidebarMenuItem>
        </SidebarMenu>

        <div className="relative group-data-[collapsible=icon]:hidden">
          <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-3.5 -translate-y-1/2 text-muted-foreground" />
          <SidebarInput
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            placeholder="搜索历史会话..."
            aria-label="搜索历史会话"
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
          {isAdmin ? (
            <>
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="模型与协议配置"
                  isActive={pathname === "/admin/models"}
                  render={
                    <Link href="/admin/models" onClick={closeMobileIfNeeded} />
                  }
                >
                  <CpuIcon />
                  <span>模型配置</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
              <SidebarMenuItem>
                <SidebarMenuButton
                  tooltip="用户与角色管理"
                  isActive={pathname === "/admin/users"}
                  render={
                    <Link href="/admin/users" onClick={closeMobileIfNeeded} />
                  }
                >
                  <UsersIcon />
                  <span>用户管理</span>
                </SidebarMenuButton>
              </SidebarMenuItem>
            </>
          ) : null}
        </SidebarMenu>

        <SidebarMenu>
          <SidebarMenuItem>
            <DropdownMenu>
              <DropdownMenuTrigger
                render={
                  <SidebarMenuButton
                    size="lg"
                    className="data-popup-open:bg-sidebar-accent data-popup-open:text-sidebar-accent-foreground"
                  />
                }
              >
                <Avatar className="size-7 rounded-xl">
                  <AvatarFallback className="rounded-xl text-xs">
                    {getInitials(user?.displayName)}
                  </AvatarFallback>
                </Avatar>
                <div className="grid flex-1 text-left text-sm leading-tight">
                  <div className="flex items-center gap-1.5">
                    <span className="truncate font-medium">
                      {user?.displayName}
                    </span>
                    {isAdmin ? (
                      <Badge variant="secondary" className="h-4 px-1 text-[10px]">
                        ADMIN
                      </Badge>
                    ) : null}
                  </div>
                  <span className="truncate text-xs text-muted-foreground">
                    {user?.email}
                  </span>
                </div>
                <ChevronsUpDownIcon className="ml-auto size-4" />
              </DropdownMenuTrigger>
              <DropdownMenuContent
                side="top"
                align="end"
                className="w-56"
              >
                <DropdownMenuGroup>
                  <DropdownMenuLabel>界面外观</DropdownMenuLabel>
                  <DropdownMenuRadioGroup
                    value={theme ?? "system"}
                    onValueChange={(value) => setTheme(value)}
                  >
                    <DropdownMenuRadioItem value="light">
                      <SunIcon />
                      <span>浅色模式</span>
                    </DropdownMenuRadioItem>
                    <DropdownMenuRadioItem value="dark">
                      <MoonIcon />
                      <span>深色模式</span>
                    </DropdownMenuRadioItem>
                    <DropdownMenuRadioItem value="system">
                      <MonitorIcon />
                      <span>跟随系统</span>
                    </DropdownMenuRadioItem>
                  </DropdownMenuRadioGroup>
                </DropdownMenuGroup>
                <DropdownMenuSeparator />
                <DropdownMenuItem
                  render={
                    <Link href="/account" onClick={closeMobileIfNeeded} />
                  }
                >
                  <WalletIcon />
                  <span>账户与额度</span>
                </DropdownMenuItem>
                <DropdownMenuItem variant="destructive" onClick={handleLogout}>
                  <LogOutIcon />
                  <span>退出登录</span>
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </SidebarMenuItem>
        </SidebarMenu>
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
