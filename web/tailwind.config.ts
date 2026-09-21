import type { Config } from 'tailwindcss'

/**
 * 純 `var(--x)` 的顏色吃不到 `/40` 這種透明度修飾——Tailwind 不知道怎麼替它加 alpha，
 * 整個 class 會安靜地不產生。用 color-mix 把 alpha 接進去，`border-danger/40` 才會真的出現。
 */
function token(variable: string): string {
  return `color-mix(in srgb, var(${variable}) calc(<alpha-value> * 100%), transparent)`
}

/**
 * 設計 token 一律從 CSS 變數取，而不是在這裡寫死色碼。
 * 主題只需要在 main.css 覆寫一組變數，元件不必各自處理。
 */
export default <Partial<Config>>{
  content: ['./app/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        ground: token('--ground'),
        surface: token('--surface'),
        sunken: token('--surface-sunken'),
        line: token('--line'),
        'line-strong': token('--line-strong'),
        ink: token('--ink'),
        'ink-muted': token('--ink-muted'),
        'ink-faint': token('--ink-faint'),
        'ink-inverse': token('--ink-inverse'),
        accent: token('--accent'),
        'accent-hover': token('--accent-hover'),
        'accent-soft': token('--accent-soft'),
        'on-accent': token('--on-accent'),
        cta: token('--cta'),
        'cta-hover': token('--cta-hover'),
        'cta-active': token('--cta-active'),
        danger: token('--danger'),
        'danger-soft': token('--danger-soft'),
        ok: token('--ok'),
        'ok-soft': token('--ok-soft'),
        star: token('--star'),
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
