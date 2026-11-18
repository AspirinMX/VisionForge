import { Badge } from "@/components/ui/badge";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

export default function HomePage() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-background p-6">
      <Card className="w-full max-w-md">
        <CardHeader>
          <div className="flex items-center justify-between gap-2">
            <CardTitle>VisionForge · 幻影工坊</CardTitle>
            <Badge variant="secondary">Phase 0 就绪</Badge>
          </div>
          <CardDescription>
            前端基础底座已完成初始化，等待进入 Phase 1 鉴权与工作台壳层建设。
          </CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          已接入 shadcn（preset b27GcrRo）、同源 API 代理、令牌刷新队列、SSE 流式读取器及统一错误处理。
        </CardContent>
      </Card>
    </main>
  );
}
