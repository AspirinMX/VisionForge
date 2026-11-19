export const MODAL_VIEWPORT_CLASS =
  "fixed inset-0 z-50 flex items-center justify-center overflow-y-auto p-4"

export const MODAL_POPUP_MOTION = {
  initial: { opacity: 0, scale: 0.96 },
  animate: { opacity: 1, scale: 1 },
  exit: { opacity: 0, scale: 0.96 },
  transition: { duration: 0.16, ease: [0.16, 1, 0.3, 1] as const },
}
