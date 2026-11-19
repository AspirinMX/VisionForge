"use client";

import { useMemo, useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  ClapperboardIcon,
  ListTodoIcon,
  MessageSquareIcon,
  MoreHorizontalIcon,
  PencilIcon,
  PlusIcon,
  RefreshCwIcon,
  SearchIcon,
  Trash2Icon,
  WalletIcon,
} from "lucide-react";

import { SidebarUserMenu } from "@/components/layout/sidebar-user-menu";
import { useConversations } from "@/components/providers/conversations-provider";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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
  SidebarMenuAction,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSkeleton,
  SidebarProvider,
  SidebarRail,
  SidebarSeparator,
  useSidebar,
} from "@/components/ui/sidebar";
import { notifyApiError, notifyError, notifySuccess } from "@/lib/api/feedback";
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
  const router = useRouter();
  const {
    conversations,
    loading,
    error,
    refresh,
    renameConversation,
    deleteConversation,
  } = useConversations();
  const { isMobile, setOpenMobile } = useSidebar();
  const [search, setSearch] = useState("");
  const [renameTarget, setRenameTarget] = useState<ConversationResponse | null>(
    null
  );
  const [renameTitle, setRenameTitle] = useState("");
  const [renaming, setRenaming] = useState(false);
  const [deleteTarget, setDeleteTarget] = useState<ConversationResponse | null>(
    null
  );
  const [deleting, setDeleting] = useState(false);

  const buckets = useMemo(
    () => groupConversations(conversations, search),
    [conversations, search]
  );

  const closeMobileIfNeeded = () => {
    if (isMobile) {
      setOpenMobile(false);
    }
  };

  const handleConfirmRename = async () => {
    if (!renameTarget || renaming) {
      return;
    }
    const trimmed = renameTitle.trim();
    if (!trimmed) {
      notifyError("会话标题不能为空");
      return;
    }
    if (trimmed.length > 200) {
      notifyError("会话标题最多 200 个字符");
      return;
    }
    setRenaming(true);
    try {
      await renameConversation(renameTarget.id, trimmed);
      setRenameTarget(null);
      notifySuccess("会话已重命名");
    } catch (err) {
      notifyApiError(err, "重命名会话失败");
    } finally {
      setRenaming(false);
    }
  };

  const handleConfirmDelete = async () => {
    if (!deleteTarget || deleting) {
      return;
    }
    setDeleting(true);
    const targetId = deleteTarget.id;
    try {
      await deleteConversation(targetId);
      setDeleteTarget(null);
      notifySuccess("会话已删除");
      if (pathname === `/conversations/${targetId}`) {
        router.replace("/conversations");
      }
    } catch (err) {
      notifyApiError(err, "删除会话失败");
    } finally {
      setDeleting(false);
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
        {loading && conversations.length === 0 ? (
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
        ) : error && conversations.length === 0 ? (
          <SidebarGroup className="group-data-[collapsible=icon]:hidden">
            <SidebarGroupLabel>会话历史</SidebarGroupLabel>
            <SidebarGroupContent>
              <div className="space-y-2 rounded-surface-sm border border-destructive/30 bg-destructive/5 p-2.5 text-xs">
                <p className="text-destructive">加载会话列表失败</p>
                <p className="text-[11px] text-muted-foreground">
                  {error.message}
                </p>
                <Button
                  type="button"
                  size="xs"
                  variant="outline"
                  onClick={refresh}
                >
                  <RefreshCwIcon />
                  <span>重试加载</span>
                </Button>
              </div>
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
                        <DropdownMenu>
                          <DropdownMenuTrigger
                            render={
                              <SidebarMenuAction
                                showOnHover
                                aria-label="会话操作"
                              />
                            }
                          >
                            <MoreHorizontalIcon />
                          </DropdownMenuTrigger>
                          <DropdownMenuContent side="right" align="start">
                            <DropdownMenuItem
                              onClick={() => {
                                setRenameTarget(conversation);
                                setRenameTitle(conversation.title);
                              }}
                            >
                              <PencilIcon />
                              <span>重命名</span>
                            </DropdownMenuItem>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem
                              variant="destructive"
                              onClick={() => setDeleteTarget(conversation)}
                            >
                              <Trash2Icon />
                              <span>删除会话</span>
                            </DropdownMenuItem>
                          </DropdownMenuContent>
                        </DropdownMenu>
                      </SidebarMenuItem>
                    );
                  })}
                </SidebarMenu>
              </SidebarGroupContent>
            </SidebarGroup>
          ))
        )}
      </SidebarContent>

      <Dialog
        open={Boolean(renameTarget)}
        onOpenChange={(open) => {
          if (!open && !renaming) {
            setRenameTarget(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>重命名会话</DialogTitle>
            <DialogDescription>
              修改当前创作会话在侧边栏和工作区中显示的标题。
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-1.5 py-1">
            <Label htmlFor="rename-conversation-input">会话标题</Label>
            <Input
              id="rename-conversation-input"
              maxLength={200}
              value={renameTitle}
              onChange={(event) => setRenameTitle(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === "Enter") {
                  void handleConfirmRename();
                }
              }}
            />
          </div>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={renaming}
              onClick={() => setRenameTarget(null)}
            >
              取消
            </Button>
            <Button
              type="button"
              disabled={renaming || !renameTitle.trim()}
              onClick={() => void handleConfirmRename()}
            >
              {renaming ? "保存中..." : "保存"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => {
          if (!open && !deleting) {
            setDeleteTarget(null);
          }
        }}
      >
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>删除会话</DialogTitle>
            <DialogDescription>
              确定删除「{deleteTarget?.title}」？若会话仍有执行中的 AI
              轮次，需先停止后再删除。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              type="button"
              variant="outline"
              disabled={deleting}
              onClick={() => setDeleteTarget(null)}
            >
              取消
            </Button>
            <Button
              type="button"
              variant="destructive"
              disabled={deleting}
              onClick={() => void handleConfirmDelete()}
            >
              {deleting ? "删除中..." : "确认删除"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

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
