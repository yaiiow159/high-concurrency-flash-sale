import type { Config } from 'tailwindcss'

/**
 * 設計 token 一律從 CSS 變數取，而不是在這裡寫死色碼。
 * 主題只需要在 main.css 覆寫一組變數，元件不必各自處理。
 */
export default <Partial<Config>>{
  content: ['./app/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        ground: 'var(--ground)',
        surface: 'var(--surface)',
        sunken: 'var(--surface-sunken)',
        line: 'var(--line)',
        'line-strong': 'var(--line-strong)',
        ink: 'var(--ink)',
        'ink-muted': 'var(--ink-muted)',
        'ink-faint': 'var(--ink-faint)',
        'ink-inverse': 'var(--ink-inverse)',
        accent: 'var(--accent)',
        'accent-hover': 'var(--accent-hover)',
        'accent-soft': 'var(--accent-soft)',
        'on-accent': 'var(--on-accent)',
        cta: 'var(--cta)',
        'cta-hover': 'var(--cta-hover)',
        'cta-active': 'var(--cta-active)',
        danger: 'var(--danger)',
        'danger-soft': 'var(--danger-soft)',
        ok: 'var(--ok)',
        'ok-soft': 'var(--ok-soft)',
        star: 'var(--star)',
      },
      borderRadius: {
        DEFAULT: 'var(--radius)',
        sm: 'var(--radius-sm)',
      },
      fontFamily: {
        /* Manrope 只負責拉丁字母與數字；中文由系統字體接手，順序不可對調 */
        sans: [
          'Manrope',
          '"PingFang TC"', '"Noto Sans TC"', '"Microsoft JhengHei"',
          'system-ui', 'sans-serif',
        ],
        mono: [
          'ui-monospace', '"SF Mono"', 'Menlo', 'Consolas',
          '"PingFang TC"', '"Microsoft JhengHei"', 'monospace',
        ],
      },
      maxWidth: {
        content: '75rem',
        prose: '40rem',
      },
      boxShadow: {
        rest: 'var(--rest)',
        lift: 'var(--lift)',
      },
    },
  },
}
