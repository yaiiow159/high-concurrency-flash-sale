/**
 * sitemap 索引。
 *
 * 五萬個商品放不進單一檔案（協定上限就是五萬筆），所以切成分片再用索引串起來。
 * 分片數由後端回的總數算出來，不寫死。
 */
export default defineEventHandler(async (event) => {
  const site = siteUrl(event)
  const config = useRuntimeConfig(event)

  let total = 0
  try {
    const response = await $fetch<{ data: { total: number } }>(
      `${config.apiBase}/api/v1/catalog/sitemap`, { query: { page: 0, size: 1 } })
    total = response.data.total
  } catch {
    // 後端掛了仍然要回一份合法的索引：至少讓靜態頁面被收錄。
    // 回 500 會讓爬蟲把整個 sitemap 標記為失效
    total = 0
  }

  const chunks = Math.max(1, Math.ceil(total / SITEMAP_CHUNK_SIZE))
  const entries = [
    `${site}/sitemap/pages`,
    ...Array.from({ length: chunks }, (_, i) => `${site}/sitemap/products/${i}`),
  ]

  setHeader(event, 'content-type', 'application/xml; charset=utf-8')
  return `<?xml version="1.0" encoding="UTF-8"?>
<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${entries.map((loc) => `  <sitemap><loc>${xmlEscape(loc)}</loc></sitemap>`).join('\n')}
</sitemapindex>`
})
