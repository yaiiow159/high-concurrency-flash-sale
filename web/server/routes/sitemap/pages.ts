/** 靜態頁面與類目。數量少，一份就夠。 */
export default defineEventHandler(async (event) => {
  const site = siteUrl(event)
  const config = useRuntimeConfig(event)

  const urls: { loc: string, priority: string }[] = [
    { loc: `${site}/`, priority: '1.0' },
    { loc: `${site}/products`, priority: '0.9' },
    { loc: `${site}/search`, priority: '0.5' },
  ]

  try {
    const response = await $fetch<{ data: { categoryId: number, children?: unknown[] }[] }>(
      `${config.apiBase}/api/v1/catalog/categories`)
    const walk = (nodes: { categoryId: number, children?: unknown[] }[]) => {
      for (const node of nodes) {
        urls.push({ loc: `${site}/products?category=${node.categoryId}`, priority: '0.7' })
        walk((node.children ?? []) as { categoryId: number, children?: unknown[] }[])
      }
    }
    walk(response.data ?? [])
  } catch {
    // 類目取不到就只出靜態頁，不要整份失敗
  }

  setHeader(event, 'content-type', 'application/xml; charset=utf-8')
  return `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${urls.map((u) => `  <url><loc>${xmlEscape(u.loc)}</loc><priority>${u.priority}</priority></url>`).join('\n')}
</urlset>`
})
