/**
 * robots.txt。
 *
 * 個人化頁面一律 Disallow：它們對爬蟲沒有價值，而且被收錄等於把
 * 「訂單」「購物車」這種路徑公開在搜尋結果上。
 */
export default defineEventHandler((event) => {
  const site = siteUrl(event)
  setHeader(event, 'content-type', 'text/plain; charset=utf-8')
  return [
    'User-agent: *',
    'Allow: /',
    'Disallow: /orders',
    'Disallow: /returns',
    'Disallow: /cart',
    'Disallow: /checkout',
    'Disallow: /member',
    'Disallow: /addresses',
    'Disallow: /notifications',
    'Disallow: /coupons',
    'Disallow: /reviews',
    'Disallow: /admin',
    '',
    `Sitemap: ${site}/sitemap.xml`,
    '',
  ].join('\n')
})
