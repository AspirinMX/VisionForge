import type {
  StoryboardCamera,
  StoryboardDocument,
  StoryboardReferenceRole,
  StoryboardShot,
  StoryboardShotSize,
  StoryboardShotStatus,
} from "@/lib/api/types";

export const STORYBOARD_SHOT_SIZES: StoryboardShotSize[] = [
  "EXTREME_WIDE",
  "WIDE",
  "FULL",
  "MEDIUM",
  "CLOSE",
  "EXTREME_CLOSE",
];

export const STORYBOARD_CAMERAS: StoryboardCamera[] = [
  "STATIC",
  "PUSH",
  "PULL",
  "PAN",
  "TRACK",
  "FOLLOW",
  "CRANE",
];

export const STORYBOARD_SHOT_STATUSES: StoryboardShotStatus[] = [
  "DRAFT",
  "FIRST_FRAME_GENERATING",
  "FIRST_FRAME_READY",
  "VIDEO_GENERATING",
  "VIDEO_CANDIDATES",
  "APPROVED",
  "LOCKED",
  "FAILED",
];

export const STORYBOARD_REFERENCE_ROLES: StoryboardReferenceRole[] = [
  "CHARACTER",
  "PRODUCT",
  "SCENE",
  "STYLE",
];

export const SHOT_SIZE_LABEL: Record<StoryboardShotSize, string> = {
  EXTREME_WIDE: "大远景",
  WIDE: "远景",
  FULL: "全景",
  MEDIUM: "中景",
  CLOSE: "近景",
  EXTREME_CLOSE: "特写",
};

export const CAMERA_LABEL: Record<StoryboardCamera, string> = {
  STATIC: "固定机位",
  PUSH: "推镜头",
  PULL: "拉镜头",
  PAN: "摇镜头",
  TRACK: "移镜头",
  FOLLOW: "跟拍",
  CRANE: "升降",
};

export const SHOT_STATUS_LABEL: Record<StoryboardShotStatus, string> = {
  DRAFT: "草稿",
  FIRST_FRAME_GENERATING: "首帧生成中",
  FIRST_FRAME_READY: "首帧已就绪",
  VIDEO_GENERATING: "视频生成中",
  VIDEO_CANDIDATES: "已有视频候选",
  APPROVED: "已确认",
  LOCKED: "已锁定",
  FAILED: "失败",
};

export const REFERENCE_ROLE_LABEL: Record<StoryboardReferenceRole, string> = {
  CHARACTER: "角色设定",
  PRODUCT: "产品主体",
  SCENE: "场景环境",
  STYLE: "风格参考",
};

const SHOT_ID_REGEX = /^[A-Za-z0-9_-]{1,64}$/;

export const DEFAULT_COMPOSITION_SPEC = {
  width: 1280,
  height: 720,
  fps: 30,
  videoCodec: "h264",
  preset: "veryfast",
  crf: 23,
};

export const DEFAULT_SCRIPT_TEMPLATE = `# 短片项目标题

## 创作简报
- 用途 / 受众 / 渠道：
- 画幅 / 目标时长 / 语言：16:9 / 15s / zh-CN
- 风格设定：
- 品牌与不可改动要求：

## 故事梗概
一句话概述核心事件与情绪起伏。

## 角色与场景
- 主角外观与服装细节：
- 核心场景光线与质感：

## 分场剧本
### 场次 1：场景标题
- 画面与可见动作：
- 对白 / 旁白：
- 声音与环境音提示：

## 制作备注
`;

export function createBlankStoryboard(title = "新分镜方案"): StoryboardDocument {
  return {
    schemaVersion: 1,
    title,
    aspectRatio: "16:9",
    language: "zh-CN",
    style: "自然电影感，统一主体外观与光线风格",
    shots: [
      {
        id: "shot-1",
        order: 1,
        sceneId: "scene-1",
        durationSeconds: 5,
        visual: "开场建立镜头，展示主角与核心环境关系",
        firstFramePrompt: "第一帧画面构图、主体姿态、光线与景别描述",
        videoPrompt: "随后发生的动作变化、运镜轨迹与节奏描述",
        shotSize: "MEDIUM",
        camera: "STATIC",
        dialogue: "",
        voiceOver: "",
        sound: "环境底噪与轻柔配乐",
        references: [],
        firstFrame: null,
        videoCandidates: [],
        selectedVideo: null,
        usedRange: null,
        status: "DRAFT",
      },
    ],
  };
}

export function parseStoryboardJson(content: string): {
  document: StoryboardDocument | null;
  error: string | null;
} {
  if (!content || !content.trim()) {
    return { document: null, error: "分镜内容不能为空" };
  }
  try {
    const parsed = JSON.parse(content) as Partial<StoryboardDocument>;
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { document: null, error: "分镜根节点必须是 JSON 对象" };
    }
    const normalized: StoryboardDocument = {
      schemaVersion: Number(parsed.schemaVersion ?? 1),
      title: typeof parsed.title === "string" ? parsed.title : "",
      aspectRatio:
        typeof parsed.aspectRatio === "string" ? parsed.aspectRatio : "16:9",
      language: typeof parsed.language === "string" ? parsed.language : "zh-CN",
      style: typeof parsed.style === "string" ? parsed.style : "",
      shots: Array.isArray(parsed.shots)
        ? parsed.shots.map((shot) => ({
            ...shot,
            references: Array.isArray(shot.references) ? shot.references : [],
            videoCandidates: Array.isArray(shot.videoCandidates)
              ? shot.videoCandidates
              : [],
          }))
        : [],
    };
    return { document: normalized, error: null };
  } catch (err) {
    return {
      document: null,
      error: `JSON 解析失败：${err instanceof Error ? err.message : "格式错误"}`,
    };
  }
}

export function validateStoryboardDocument(doc: StoryboardDocument): string[] {
  const errors: string[] = [];

  if (doc.schemaVersion !== 1) {
    errors.push("schemaVersion 必须为 1");
  }
  if (!doc.title || !doc.title.trim()) {
    errors.push("分镜标题不能为空");
  }
  if (!Array.isArray(doc.shots) || doc.shots.length === 0) {
    errors.push("分镜至少需要一个镜头");
    return errors;
  }
  if (doc.shots.length > 200) {
    errors.push("分镜镜头数量不能超过 200");
  }

  const ids = new Set<string>();
  const orders = new Set<number>();

  for (const shot of doc.shots) {
    if (!shot.id || !SHOT_ID_REGEX.test(shot.id)) {
      errors.push(`镜头 ID「${shot.id || "空"}」必须是 1-64 位字母、数字、下划线或连字符`);
    } else if (ids.has(shot.id)) {
      errors.push(`镜头 ID 重复：${shot.id}`);
    } else {
      ids.add(shot.id);
    }

    if (!Number.isInteger(shot.order) || shot.order < 1) {
      errors.push(`镜头 ${shot.id} 的序号 order 必须为正整数`);
    } else if (orders.has(shot.order)) {
      errors.push(`镜头序号 order 重复：${shot.order}`);
    } else {
      orders.add(shot.order);
    }

    if (
      !Number.isInteger(shot.durationSeconds) ||
      shot.durationSeconds < 1 ||
      shot.durationSeconds > 60
    ) {
      errors.push(`镜头 ${shot.id} 的时长必须在 1 到 60 秒之间`);
    }

    if (!shot.firstFramePrompt?.trim()) {
      errors.push(`镜头 ${shot.id} 缺少首帧提示词 (firstFramePrompt)`);
    } else if (shot.firstFramePrompt.length > 8000) {
      errors.push(`镜头 ${shot.id} 的 firstFramePrompt 超过 8000 字符`);
    }

    if (!shot.videoPrompt?.trim()) {
      errors.push(`镜头 ${shot.id} 缺少视频提示词 (videoPrompt)`);
    } else if (shot.videoPrompt.length > 8000) {
      errors.push(`镜头 ${shot.id} 的 videoPrompt 超过 8000 字符`);
    }

    if (!STORYBOARD_SHOT_SIZES.includes(shot.shotSize)) {
      errors.push(`镜头 ${shot.id} 景别不合法`);
    }
    if (!STORYBOARD_CAMERAS.includes(shot.camera)) {
      errors.push(`镜头 ${shot.id} 运镜不合法`);
    }
    if (!STORYBOARD_SHOT_STATUSES.includes(shot.status)) {
      errors.push(`镜头 ${shot.id} 状态不合法`);
    }

    if (shot.selectedVideo && shot.videoCandidates?.length) {
      const exists = shot.videoCandidates.some(
        (candidate) => candidate.taskId === shot.selectedVideo?.taskId
      );
      if (!exists) {
        errors.push(`镜头 ${shot.id} 的 selectedVideo 不在 videoCandidates 中`);
      }
    }

    if (shot.usedRange) {
      const { startSeconds, endSeconds } = shot.usedRange;
      if (
        typeof startSeconds !== "number" ||
        typeof endSeconds !== "number" ||
        Number.isNaN(startSeconds) ||
        Number.isNaN(endSeconds) ||
        startSeconds < 0 ||
        endSeconds <= startSeconds ||
        endSeconds > shot.durationSeconds
      ) {
        errors.push(
          `镜头 ${shot.id} 的裁剪区间 usedRange 必须满足 0 <= start < end <= ${shot.durationSeconds}`
        );
      }
    }
  }

  return errors;
}

export function sortShotsByOrder(shots: StoryboardShot[]): StoryboardShot[] {
  return [...shots].sort((a, b) => a.order - b.order);
}
