---
name: short-film
description: 编写或修改视频剧本、分镜，或把短片与广告从创意做到成片。含镜头连续性、首帧与运镜、生成与合成步骤。
---

# 短片创作

按用户要求完成相应步骤。用户只要剧本时，交付剧本。用户只要分镜时，交付分镜。用户要成片时，默认依次完成下列五步。

## 1. 剧本

从需求提取主体、事件、场景和目标时长，将意图写成能看见的动作与变化。用简洁的 Markdown 保存为 `SCRIPT`。已有剧本时先读实际文件。剧本和分镜沿用用户的语言与创作设定。

## 2. 分镜

依据剧本写出所有镜头。稳定的 `id` 贯穿制作，`order` 决定顺序，`durationSeconds` 是每镜整数秒数，总时长应符合需求。每镜明确景别、构图、主体朝向、动作起点、终点和切镜衔接。跨镜头重复使用具体的主体、场景、时间和光线描述，必要时使用可复用的参考素材。

- `firstFramePrompt` 描述首帧可见的状态与构图，把这一镜的关键变化留给视频。若要表现从暗到亮，首帧先保留暗态，视频描述点亮的过程。
- `videoPrompt` 写动作发生的先后、速度、终点和运镜。`camera` 与描述一致，例如 `PUSH` 推近、`PULL` 拉远、`STATIC` 固定机位。`shotSize` 对应实际景别。
- 通过 `save_storyboard` 保存结构化对象。文件内字段是 `id`，生成工具外层参数是 `shotId`。新镜头用 `DRAFT`，让工具写回首帧、候选与选片的真实引用。修改现有分镜前读取原文并提供 `fileId` 和 `baseVersion`，保留未修改镜头及其已有引用。
- 按工具 schema 填字段。工具返回字段路径时就地修复，保留原有创作内容。

## 3. 首帧图

使用 `generate_image`，提供 `storyboardFileId`、`shotId` 和该镜 `firstFramePrompt`。用 `await_generation` 等到首帧交付，必要时跨镜共用已授权的参考素材。

## 4. 分镜视频

使用 `generate_video`，提供同一镜头的 `storyboardFileId`、`shotId`、`videoPrompt`，以及 `inputs` 中 `role=FIRST_FRAME` 的实际首帧资产。每次生成一个候选，用 `await_generation` 等待交付。重做单个镜头时保留其他镜头与旧候选。

## 5. 合成

所有镜头都有选中的可用视频时，调用 `compose_video` 并传入 `storyboardFileId`，随后给出任务 ID 和当前状态。需要核实交付时调用 `get_composition`。素材时长不足时，根据需求重新生成或征得用户同意调整剪辑区间。
