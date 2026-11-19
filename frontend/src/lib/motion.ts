/**
 * Global motion tokens shared by Motion components and CSS transitions.
 *
 * - MICRO: press, toggle, check, hover feedback.
 * - CONTROL: select, dropdown, tooltip and small floating controls.
 * - SURFACE: dialogs, alert dialogs, sheets and overlays.
 * - SPATIAL: layout continuity for panels, lists and canvas transitions.
 *
 * CSS counterparts live in `app/globals.css` as `--motion-*` duration variables.
 */

export const MICRO = {
  transition: { duration: 0.14, ease: "easeOut" },
} as const;

export const CONTROL = {
  enter: { duration: 0.18, ease: [0.16, 1, 0.3, 1] as const },
  exit: { duration: 0.12, ease: [0.4, 0, 1, 1] as const },
} as const;

export const SURFACE = {
  enter: { duration: 0.24, ease: [0.16, 1, 0.3, 1] as const },
  exit: { duration: 0.17, ease: [0.4, 0, 1, 1] as const },
} as const;

export const SPATIAL = {
  type: "spring",
  stiffness: 380,
  damping: 36,
  mass: 1,
} as const;
