import { SURFACE } from "@/lib/motion"

export const MODAL_VIEWPORT_CLASS =
  "fixed inset-0 z-50 flex items-center justify-center overflow-y-auto p-4"

export const MODAL_POPUP_MOTION = {
  initial: { opacity: 0, scale: 0.97 },
  animate: { opacity: 1, scale: 1, transition: SURFACE.enter },
  exit: { opacity: 0, scale: 0.97, transition: SURFACE.exit },
}
