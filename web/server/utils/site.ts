import type { H3Event } from 'h3'

/**
 * 對外的網站網址。
 *
 * 設定優先，沒設就從請求標頭推。寫死在程式裡的話，
 * 同一份產物部署到測試環境會產生指向正式站的 sitemap。
 */
export function siteUrl(event: H3Event): string {
  const configured = useRuntimeConfig(event).public.siteUrl
  if (configured) {
    return String(configured).replace(/\/$/, '')
  }
  // getRequestURL 會把 x-forwarded-* 一起考慮進去，比自己拼標頭可靠
  const url = getRequestURL(event)
  return `${url.protocol}//${url.host}`
}

/** XML 的五個保留字元。少跳脫一個，整份 sitemap 就會被判定為格式錯誤而整份丟掉。 */
export function xmlEscape(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
}

/** 單一 sitemap 分片的筆數。協定上限是五萬，抓一萬是為了讓檔案不要太大。 */
export const SITEMAP_CHUNK_SIZE = 10_000
