import Link from "next/link";
import { FileQuestionIcon } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

export default function NotFoundPage() {
  return (
    <main className="flex min-h-svh items-center justify-center bg-background p-4">
      <Card className="w-full max-w-md">
        <CardHeader>
          <div className="flex items-center gap-2 text-muted-foreground">
            <FileQuestionIcon className="size-5" />
            <CardTitle>资源不存在或无法访问</CardTitle>
          </div>
          <CardDescription>
            请求的页面、会话或文件不存在，或不属于当前登录账号。
          </CardDescription>
        </CardHeader>
        <CardContent className="text-sm text-muted-foreground">
          为确保账号间数据隔离，系统对不可见资源统一返回 404。请确认链接是否正确或切换回你的会话列表。
        </CardContent>
        <CardFooter className="flex flex-wrap gap-2">
          <Button nativeButton={false} render={<Link href="/conversations" />}>
            返回工作台
          </Button>
          <Button
            variant="outline"
            nativeButton={false}
            render={<Link href="/login" />}
          >
            前往登录
          </Button>
        </CardFooter>
      </Card>
    </main>
  );
}
