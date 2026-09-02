import type { Config } from 'tailwindcss'

/**
 * 設計 token 一律從 CSS 變數取，而不是在這裡寫死色碼。
 *
 * 這樣深淺色只需要在 main.css 覆寫一組變數，
 * 所有元件不必寫 `dark:` 前綴——那些前綴一旦漏掉一個，
 * 就會出現「深色背景配深色文字」這種只在其中一個主題下才看得到的 bug。
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
      },
      borderRadius: {
        DEFAULT: 'var(--radius)',
        sm: 'var(--radius-sm)',
      },
      fontFamily: {
        /*
         * Archivo 只負責拉丁字母與數字；中文由系統字體接手。
         * 順序很重要：把中文字體放在後面，瀏覽器才會對拉丁字用 Archivo、
         * 對中文字回退到系統字體。
         */
        sans: [
          'Archivo',
          '"PingFang TC"', '"Noto Sans TC"', '"Microsoft JhengHei"',
          'system-ui', 'sans-serif',
        ],
        mono: [
          '"IBM Plex Mono"',
          '"PingFang TC"', '"Microsoft JhengHei"',
          'ui-monospace', 'monospace',
        ],
      },
      maxWidth: {
        content: '68rem',
        prose: '40rem',
      },
      boxShadow: {
        lift: 'var(--lift)',
      },
    },
  },
}
