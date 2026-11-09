// Icon path data: a 24×24 grid, drawn as 2px strokes with square caps and mitred corners (VOLT has no round
// edges). Each icon is a list of SVG path `d` strings; Icon.tsx draws them with stroke="currentColor", so
// an icon takes the text colour of wherever it sits.
//
// Adding an icon: add a key here — IconName (below) picks it up, and every use is type-checked.

export const icons = {
  // BLOCK 1: navigation
  home: ['M3 11 12 3l9 8', 'M5 9.5V21h5v-6h4v6h5V9.5'],
  folder: ['M3 5h7l2 3h9v12H3z'],
  issues: ['M8 6h13', 'M8 12h13', 'M8 18h13', 'M3 6h1', 'M3 12h1', 'M3 18h1'],
  dashboard: ['M3 3h8v10H3z', 'M13 3h8v6h-8z', 'M13 11h8v10h-8z', 'M3 15h8v6H3z'],
  board: ['M3 3h18v18H3z', 'M9 3v18', 'M15 3v18'],
  backlog: ['M3 4h18v5H3z', 'M3 12h18', 'M3 16h18', 'M3 20h12'],
  timeline: ['M3 5h10', 'M7 12h12', 'M5 19h9', 'M21 3v18'],
  reports: ['M3 21h18', 'M6 17v-5', 'M11 17V7', 'M16 17v-8', 'M21 17V4'],
  settings: ['M4 6h16', 'M4 12h16', 'M4 18h16', 'M9 3v6', 'M15 9v6', 'M7 15v6'],
  menu: ['M3 6h18', 'M3 12h18', 'M3 18h18'],

  // BLOCK 2: actions
  plus: ['M12 4v16', 'M4 12h16'],
  search: ['M10.5 3a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15z', 'm16 16 5 5'],
  filter: ['M3 4h18l-7 9v6l-4 2v-8z'],
  edit: ['M4 20h4L19 9l-4-4L4 16z', 'M13 7l4 4'],
  trash: ['M3 6h18', 'M8 6V3h8v3', 'M5 6l1 15h12l1-15', 'M10 10v7', 'M14 10v7'],
  link: [
    'M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1',
    'M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1',
  ],
  attach: [
    'M20 11.5 12 19.5a5 5 0 0 1-7-7l8.5-8.5a3.3 3.3 0 0 1 4.7 4.7L9.7 17.2a1.7 1.7 0 0 1-2.4-2.4L15 7',
  ],
  more: ['M5 12h.01', 'M12 12h.01', 'M19 12h.01'],
  close: ['M5 5l14 14', 'M19 5 5 19'],
  check: ['M4 12.5 9.5 18 20 6'],
  logout: ['M10 4H4v16h6', 'M15 8l4 4-4 4', 'M9 12h10'],
  drag: ['M9 5h.01', 'M15 5h.01', 'M9 12h.01', 'M15 12h.01', 'M9 19h.01', 'M15 19h.01'],

  // BLOCK 3: objects and states
  bell: ['M6 17V10a6 6 0 0 1 12 0v7l2 2H4z', 'M10 21h4'],
  help: [
    'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z',
    'M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .8-1 1.4V14',
    'M12 17.5h.01',
  ],
  user: ['M12 3a4 4 0 1 0 0 8 4 4 0 0 0 0-8z', 'M4 21v-2a5 5 0 0 1 5-5h6a5 5 0 0 1 5 5v2'],
  users: [
    'M9 4a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7z',
    'M2 20v-1.5A4.5 4.5 0 0 1 6.5 14h5a4.5 4.5 0 0 1 4.5 4.5V20',
    'M16 4.5a3.5 3.5 0 0 1 0 6.5',
    'M19 14a4.5 4.5 0 0 1 3 4.3V20',
  ],
  flag: ['M5 21V4', 'M5 4h12l-2 4 2 4H5'],
  star: ['M12 3l2.7 5.6 6.1.8-4.4 4.3 1 6.1L12 17l-5.4 2.8 1-6.1-4.4-4.3 6.1-.8z'],
  eye: ['M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z', 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z'],
  lock: ['M5 11h14v10H5z', 'M8 11V7a4 4 0 0 1 8 0v4'],
  clock: ['M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z', 'M12 7v5l3 3'],
  calendar: ['M3 5h18v16H3z', 'M3 10h18', 'M8 3v4', 'M16 3v4'],
  comment: ['M3 4h18v13H9l-5 4v-4H3z'],
  bolt: ['M13 2 4 14h7l-1 8 9-12h-7z'],

  // BLOCK 4: chevrons and arrows
  'chevron-down': ['m6 9 6 6 6-6'],
  'chevron-up': ['m6 15 6-6 6 6'],
  'chevron-left': ['m15 6-6 6 6 6'],
  'chevron-right': ['m9 6 6 6-6 6'],
  'arrow-right': ['M4 12h16', 'm14 6 6 6-6 6'],
  external: ['M14 4h6v6', 'M20 4 10 14', 'M18 14v6H4V6h6'],

  // BLOCK 5: priority glyphs (PriorityIcon colours them) and issue-type glyphs (TypeIcon puts them on a tile)
  'prio-highest': ['m6 13 6-6 6 6', 'm6 19 6-6 6 6'],
  'prio-high': ['m6 15 6-6 6 6'],
  'prio-medium': ['M5 9h14', 'M5 15h14'],
  'prio-low': ['m6 9 6 6 6-6'],
  'prio-lowest': ['m6 5 6 6 6-6', 'm6 11 6 6 6-6'],
  'type-story': ['M7 4h10v16l-5-4-5 4z'],
  'type-bug': [
    'M12 7a5 5 0 0 0-5 5v3a5 5 0 0 0 10 0v-3a5 5 0 0 0-5-5z',
    'M12 7V4',
    'M4 12h3',
    'M17 12h3',
    'M5 18l2-1.5',
    'M19 18l-2-1.5',
  ],
  'type-task': ['M5 12.5 10 17.5 19 7'],
  'type-epic': ['M13 2 4 14h7l-1 8 9-12h-7z'],
  'type-subtask': ['M7 4v9h11', 'm14 9 4 4-4 4'],
} as const satisfies Record<string, readonly string[]>

export type IconName = keyof typeof icons
