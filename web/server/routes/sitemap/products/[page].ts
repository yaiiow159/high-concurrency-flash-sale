/**
 * 商品分片。每片最多 SITEMAP_CHUNK_SIZE 筆。
 *
 * 網址刻意不帶 `.xml`：Nitro 的路由參數不能與同一段裡的字面後綴共存，
 * `[page].xml.ts` 會產生一個叫 `page.xml` 的參數，於是永遠讀不到頁碼。
 * sitemap 不需要副檔名，content-type 才是判準。
 */
export default defineEventHandler(async (event) => {
  const site = siteUrl(event)
  const config = useRuntimeConfig(event)
  const page = Number(getRouterParam(event, 'page') ?? 0)

  if (!Number.isInteger(page) || page < 0) {
    throw createError({ statusCode: 404, statusMessage: 'Not Found' })
  }

  let ids: number[] = []
  try {
    const response = await $fetch<{ data: { productIds: number[] } }>(
      `${config.apiBase}/api/v1/catalog/sitemap`,
      { query: { page, size: SITEMAP_CHUNK_SIZE } })
    ids = response.data.productIds ?? []
  } catch {
    ids = []
  }

  setHeader(event, 'content-type', 'application/xml; charset=utf-8')
  return `<?xml version="1.0" encoding="UTF-8"?>
<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">
${ids.map((id) => `  <url><loc>${xmlEscape(`${site}/products/${id}`)}</loc><priority>0.8</priority></url>`).join('\n')}
</urlset>`
})
