"use client";

import {
  ClapperboardIcon,
  FileTextIcon,
  ImagesIcon,
  ListChecksIcon,
  Maximize2Icon,
  Minimize2Icon,
  XIcon,
} from "lucide-react";

import { AssetsGeneratePanel } from "@/components/studio/assets-generate-panel";
import { FilesPanel } from "@/components/studio/files-panel";
import { StoryboardPanel } from "@/components/studio/storyboard-panel";
import {
  useStudio,
  type StudioCanvasTab,
} from "@/components/studio/studio-context";
import { TasksPanel } from "@/components/studio/tasks-panel";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";

export function StudioCanvas({ hideFullscreenToggle = false }: { hideFullscreenToggle?: boolean }) {
  const {
    activeTab,
    setActiveTab,
    files,
    activeTaskCount,
    canvasFullscreen,
    setCanvasFullscreen,
    setCanvasOpen,
  } = useStudio();

  return (
    <Tabs
      value={activeTab}
      onValueChange={(value) => {
        if (value) {
          setActiveTab(value as StudioCanvasTab);
        }
      }}
      className="flex h-full min-h-0 flex-col gap-0 bg-background"
    >
      <div className="flex shrink-0 items-center justify-between gap-2 border-b px-3 py-1.5">
        <TabsList variant="line" className="min-w-0 flex-1 justify-start overflow-x-auto">
          <TabsTrigger value="storyboard" className="flex-none px-2.5 text-xs">
            <ClapperboardIcon className="size-3.5" />
            <span>分镜与成片</span>
          </TabsTrigger>
          <TabsTrigger value="files" className="flex-none px-2.5 text-xs">
            <FileTextIcon className="size-3.5" />
            <span>剧本与文件</span>
            {files.length > 0 ? (
              <Badge variant="secondary" className="h-4 px-1.5 text-[10px] tabular-nums">
                {files.length}
              </Badge>
            ) : null}
          </TabsTrigger>
          <TabsTrigger value="assets" className="flex-none px-2.5 text-xs">
            <ImagesIcon className="size-3.5" />
            <span>素材与生成</span>
          </TabsTrigger>
          <TabsTrigger value="tasks" className="flex-none px-2.5 text-xs">
            <ListChecksIcon className="size-3.5" />
            <span>任务中心</span>
            {activeTaskCount > 0 ? (
              <Badge variant="default" className="h-4 px-1.5 text-[10px] tabular-nums">
                {activeTaskCount}
              </Badge>
            ) : null}
          </TabsTrigger>
        </TabsList>

        <div className="flex shrink-0 items-center gap-1">
          {!hideFullscreenToggle ? (
            <Button
              type="button"
              variant="ghost"
              size="icon-xs"
              onClick={() => setCanvasFullscreen(!canvasFullscreen)}
              aria-label={canvasFullscreen ? "退出全屏画布" : "全屏展开画布"}
            >
              {canvasFullscreen ? <Minimize2Icon /> : <Maximize2Icon />}
            </Button>
          ) : null}
          <Button
            type="button"
            variant="ghost"
            size="icon-xs"
            onClick={() => {
              setCanvasFullscreen(false);
              setCanvasOpen(false);
            }}
            aria-label="收起工作台"
          >
            <XIcon />
          </Button>
        </div>
      </div>

      <TabsContent value="storyboard" keepMounted className="min-h-0 flex-1 overflow-hidden">
        <div className="h-full min-h-0">
          <StoryboardPanel />
        </div>
      </TabsContent>

      <TabsContent value="files" keepMounted className="min-h-0 flex-1 overflow-hidden">
        <div className="h-full min-h-0">
          <FilesPanel />
        </div>
      </TabsContent>

      <TabsContent value="assets" keepMounted className="min-h-0 flex-1 overflow-hidden">
        <div className="h-full min-h-0">
          <AssetsGeneratePanel />
        </div>
      </TabsContent>

      <TabsContent value="tasks" keepMounted className="min-h-0 flex-1 overflow-hidden">
        <div className="h-full min-h-0">
          <TasksPanel />
        </div>
      </TabsContent>
    </Tabs>
  );
}
