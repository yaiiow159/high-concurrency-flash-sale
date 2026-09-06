import type { ProductRatingView, ProductView } from '~/types/api'

/**
 * 頁面的 SEO 標記。
 *
 * 分享到 LINE / FB 時預覽卡片靠 og:*，Google 的價格與星等靠 JSON-LD，
 * 兩套要分別給——沒有哪一個能取代另一個。
 */
export function useSeo() {
  const route = useRoute()
  const config = useRuntimeConfig()

  const siteName = '閃購'

  /** 對外網址。SSR 時從設定取，客戶端用當下的 origin。 */
  function site(): string {
    const configured = config.public.siteUrl
    if (configured) {
      return String(configured).replace(/\/$/, '')
    }
    return import.meta.client ? window.location.origin : 'http://localhost:5173'
  }

  function canonical(path?: string): string {
    return `${site()}${path ?? route.path}`
  }

  /**
   * 一般頁面。
   *
   * `noindex` 給個人化頁面用——它們對爬蟲沒有價值，
   * 而被收錄等於把「我的訂單」這種路徑公開在搜尋結果上。
   */
  function seo(options: {
    title: string
    description?: string
    image?: string | null
    path?: string
    noindex?: boolean
  }) {
    const url = canonical(options.path)
    useSeoMeta({
      title: options.title,
      description: options.description,
      ogTitle: options.title,
      ogDescription: options.description,
      ogType: 'website',
      ogUrl: url,
      ogSiteName: siteName,
      ogImage: options.image ?? undefined,
      twitterCard: options.image ? 'summary_large_image' : 'summary',
      twitterTitle: options.title,
      twitterDescription: options.description,
      twitterImage: options.image ?? undefined,
      robots: options.noindex ? 'noindex, nofollow' : undefined,
    })
    useHead({ link: [{ rel: 'canonical', href: url }] })
  }

  /** 把物件塞進 `<script type="application/ld+json">`。 */
  function jsonLd(data: Record<string, unknown>) {
    useHead({
      script: [{
        type: 'application/ld+json',
        // 用 innerHTML 而非 children：Nuxt 對後者會做 HTML 跳脫，
        // 而跳脫過的 JSON 解析不出來，Google 會直接忽略整段
        innerHTML: JSON.stringify(data),
      }],
    })
  }

  /**
   * 商品的結構化資料。
   *
   * `offers` 與 `aggregateRating` 是 rich snippet 會用到的兩塊——
   * 少了它們搜尋結果就只有標題，沒有價格也沒有星等。
   */
  function productJsonLd(product: ProductView, rating: ProductRatingView | null,
                         image: string | null) {
    const data: Record<string, unknown> = {
      '@context': 'https://schema.org',
      '@type': 'Product',
      name: product.name,
      description: product.description || undefined,
      brand: product.brand ? { '@type': 'Brand', name: product.brand } : undefined,
      image: image ? [image] : undefined,
      url: canonical(`/products/${product.productId}`),
      sku: String(product.productId),
    }

    if (product.lowestPrice) {
      const purchasable = product.skus?.some((sku) => sku.purchasable) ?? false
      data.offers = {
        '@type': 'AggregateOffer',
        priceCurrency: 'TWD',
        lowPrice: product.lowestPrice,
        offerCount: product.skus?.length ?? 1,
        // 缺貨也要如實標。標成有貨會讓使用者點進來撲空，
        // 而 Google 會因為落地頁與標記不符降低這個站的信任度
        availability: purchasable
          ? 'https://schema.org/InStock'
          : 'https://schema.org/OutOfStock',
      }
    }

    // 沒有評價就整段不給。給一個 0 顆星的 aggregateRating 會被判定為無效標記
    if (rating && rating.count > 0) {
      data.aggregateRating = {
        '@type': 'AggregateRating',
        ratingValue: rating.average,
        reviewCount: rating.count,
      }
    }

    jsonLd(data)
  }

  /** 麵包屑。搜尋結果上會把它畫成路徑，而不是一長串網址。 */
  function breadcrumbJsonLd(items: { name: string, path: string }[]) {
    jsonLd({
      '@context': 'https://schema.org',
      '@type': 'BreadcrumbList',
      itemListElement: items.map((item, index) => ({
        '@type': 'ListItem',
        position: index + 1,
        name: item.name,
        item: canonical(item.path),
      })),
    })
  }

  return { seo, jsonLd, productJsonLd, breadcrumbJsonLd, canonical, site }
}
