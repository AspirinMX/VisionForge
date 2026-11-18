"use client";

import { useMemo, useState } from "react";
import { PencilIcon, PlusIcon, RefreshCwIcon, StarIcon } from "lucide-react";
import { toast } from "sonner";

import { ModelFormSheet } from "@/components/admin/model-form-sheet";
import { ApiErrorAlert } from "@/components/common/api-error-alert";
import { AdminRoleGuard } from "@/components/common/auth-guard";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { Switch } from "@/components/ui/switch";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { useAsyncData } from "@/hooks/use-async-data";
import { adminModelsApi } from "@/lib/api/endpoints";
import { notifyApiError } from "@/lib/api/feedback";
import type { ModelCapabilityType, ModelResponse } from "@/lib/api/types";
import {
  EXECUTION_MODE_LABEL,
  MEDIA_PROTOCOL_LABEL,
  MODEL_CAPABILITY_LABEL,
} from "@/lib/format";

type CapabilityFilter = "ALL" | ModelCapabilityType;

function ModelsAdminContent() {
  const { data, loading, error, reload } = useAsyncData(
    () => adminModelsApi.list(),
    "admin-models",
    { errorMessage: "加载模型配置列表失败" }
  );

  const [filter, setFilter] = useState<CapabilityFilter>("ALL");
  const [sheetOpen, setSheetOpen] = useState(false);
  const [editingModel, setEditingModel] = useState<ModelResponse | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);

  const models = useMemo(() => data ?? [], [data]);

  const filteredModels = useMemo(() => {
    if (filter === "ALL") {
      return models;
    }
    return models.filter((item) => item.capabilityType === filter);
  }, [models, filter]);

  const handleCreateClick = () => {
    setEditingModel(null);
    setSheetOpen(true);
  };

  const handleEditClick = (model: ModelResponse) => {
    setEditingModel(model);
    setSheetOpen(true);
  };

  const handleSetDefault = async (model: ModelResponse) => {
    if (!model.enabled || model.defaultModel || busyId) {
      return;
    }
    setBusyId(model.id);
    try {
      await adminModelsApi.setDefault(model.id);
      toast.success(`已将「${model.displayName}」设为默认模型`);
      reload();
    } catch (err) {
      notifyApiError(err, "设置默认模型失败");
    } finally {
      setBusyId(null);
    }
  };

  const handleToggleEnabled = async (
    model: ModelResponse,
    nextEnabled: boolean
  ) => {
    if (busyId) {
      return;
    }
    setBusyId(model.id);
    try {
      await adminModelsApi.update(model.id, { enabled: nextEnabled });
      toast.success(
        `模型「${model.displayName}」已${nextEnabled ? "启用" : "停用"}`
      );
      reload();
    } catch (err) {
      notifyApiError(err, "更新模型状态失败");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="flex flex-1 flex-col overflow-hidden">
      <PageHeader
        title="模型配置"
        actions={
          <div className="flex items-center gap-2">
            <Button
              type="button"
              variant="ghost"
              size="icon-sm"
              onClick={reload}
              disabled={loading}
              aria-label="刷新模型列表"
            >
              <RefreshCwIcon
                className={loading ? "animate-spin motion-reduce:animate-none" : ""}
              />
            </Button>
            <Button type="button" size="sm" onClick={handleCreateClick}>
              <PlusIcon />
              <span>新增模型</span>
            </Button>
          </div>
        }
      />

      <div className="flex-1 overflow-y-auto p-4 md:p-6">
        <div className="mx-auto w-full max-w-7xl space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <Tabs
              value={filter}
              onValueChange={(value) => setFilter(value as CapabilityFilter)}
            >
              <TabsList>
                <TabsTrigger value="ALL">全部</TabsTrigger>
                <TabsTrigger value="CHAT">主对话</TabsTrigger>
                <TabsTrigger value="IMAGE">图片</TabsTrigger>
                <TabsTrigger value="VIDEO">视频</TabsTrigger>
              </TabsList>
            </Tabs>
          </div>

          <ApiErrorAlert error={error} title="模型目录加载失败" />

          {loading && !data ? (
            <Skeleton className="h-72 w-full rounded-2xl" />
          ) : filteredModels.length === 0 ? (
            <div className="flex flex-col items-center justify-center gap-3 rounded-2xl border border-dashed p-10 text-center">
              <p className="text-sm text-muted-foreground">
                当前分类下暂无已配置的模型路由。
              </p>
              <Button type="button" size="sm" onClick={handleCreateClick}>
                <PlusIcon />
                <span>创建首个模型配置</span>
              </Button>
            </div>
          ) : (
            <div className="rounded-2xl border bg-card">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>模型名称与 ID</TableHead>
                    <TableHead>能力类型</TableHead>
                    <TableHead>协议与执行方式</TableHead>
                    <TableHead className="text-right">单价</TableHead>
                    <TableHead>配置属性</TableHead>
                    <TableHead>启用</TableHead>
                    <TableHead className="text-right">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {filteredModels.map((model) => (
                    <TableRow key={model.id}>
                      <TableCell>
                        <div className="flex items-center gap-2">
                          <span className="font-medium">
                            {model.displayName}
                          </span>
                          {model.defaultModel ? (
                            <Badge variant="default" className="gap-1">
                              <StarIcon className="size-3" />
                              <span>默认</span>
                            </Badge>
                          ) : null}
                        </div>
                        <p className="mt-0.5 font-mono text-xs text-muted-foreground">
                          {model.modelId}
                        </p>
                      </TableCell>
                      <TableCell>
                        <Badge variant="outline">
                          {MODEL_CAPABILITY_LABEL[model.capabilityType]}
                        </Badge>
                      </TableCell>
                      <TableCell>
                        <div className="text-sm">
                          {MEDIA_PROTOCOL_LABEL[model.protocol]}
                        </div>
                        <div className="text-xs text-muted-foreground">
                          {EXECUTION_MODE_LABEL[model.executionMode]}
                        </div>
                      </TableCell>
                      <TableCell className="text-right font-mono text-sm tabular-nums">
                        {model.priceUnits}
                      </TableCell>
                      <TableCell>
                        <div className="flex flex-wrap gap-1.5">
                          <Badge variant="secondary">
                            {model.apiKeyConfigured
                              ? "Key 已配置"
                              : "未配置 Key"}
                          </Badge>
                          {model.supportsImageInput ? (
                            <Badge variant="outline">支持图片输入</Badge>
                          ) : null}
                        </div>
                      </TableCell>
                      <TableCell>
                        <Switch
                          checked={model.enabled}
                          onCheckedChange={(checked) =>
                            void handleToggleEnabled(model, checked)
                          }
                          disabled={busyId === model.id}
                          aria-label={`切换 ${model.displayName} 启用状态`}
                        />
                      </TableCell>
                      <TableCell className="text-right">
                        <div className="flex justify-end gap-1.5">
                          {!model.defaultModel && model.enabled ? (
                            <Button
                              type="button"
                              variant="outline"
                              size="xs"
                              onClick={() => void handleSetDefault(model)}
                              disabled={busyId === model.id}
                            >
                              设为默认
                            </Button>
                          ) : null}
                          <Button
                            type="button"
                            variant="ghost"
                            size="xs"
                            onClick={() => handleEditClick(model)}
                            disabled={busyId === model.id}
                          >
                            <PencilIcon />
                            <span>编辑</span>
                          </Button>
                        </div>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          )}
        </div>
      </div>

      <ModelFormSheet
        open={sheetOpen}
        onOpenChange={setSheetOpen}
        editingModel={editingModel}
        onSaved={() => {
          reload();
        }}
      />
    </div>
  );
}

export default function AdminModelsPage() {
  return (
    <AdminRoleGuard>
      <ModelsAdminContent />
    </AdminRoleGuard>
  );
}
