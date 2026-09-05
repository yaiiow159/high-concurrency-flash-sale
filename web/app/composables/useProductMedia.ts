import { errorMessage, useApi } from '~/composables/useApi'
import type { ProductImageView, UploadAuthorization } from '~/types/api'

/**
 * 商品圖片上傳（ADR-0027）。
 *
 * 三步：算雜湊 → 要授權 → 直傳物件儲存 → 回報掛載。
 *
 * **位元組不經過應用伺服器**——那條請求執行緒是秒殺熱路徑要用的。
 * 這也是為什麼雜湊在瀏覽器算：伺服器要驗證雜湊就得讀完整個檔案，
 * 而那正是我們在避免的事。算錯的後果是「同一張圖存了兩份」，
 * 是浪費不是錯誤。
 */
export function useProductMedia() {
  const { request } = useApi()

  /** 用 Web Crypto 算 SHA-256。同一份內容永遠得到同一個鍵。 */
  async function sha256Of(file: File): Promise<string> {
    const buffer = await file.arrayBuffer()
    const digest = await crypto.subtle.digest('SHA-256', buffer)
    return [...new Uint8Array(digest)]
      .map((byte) => byte.toString(16).padStart(2, '0'))
      .join('')
  }

  async function upload(productId: number, file: File): Promise<ProductImageView> {
    const sha256 = await sha256Of(file)

    const auth = await request<UploadAuthorization>(
      '/api/v1/admin/products/images/authorize',
      {
        method: 'POST',
        authenticated: true,
        body: { sha256, contentType: file.type, byteSize: file.size },
      })

    // 同一張圖上傳過就跳過傳輸——內容雜湊命名讓重複上傳變成零成本
    if (!auth.alreadyUploaded && auth.uploadUrl) {
      const response = await fetch(auth.uploadUrl, {
        method: 'PUT',
        // Content-Type 必須與簽章時一致，否則儲存端會拒絕——
        // 而錯誤訊息只會說簽章不符，看不出是標頭的問題
        headers: { 'Content-Type': file.type },
        body: file,
      })
      if (!response.ok) {
        throw new Error(`上傳失敗（${response.status}）`)
      }
    }

    return await request<ProductImageView>(
      `/api/v1/admin/products/${productId}/images`,
      {
        method: 'POST',
        authenticated: true,
        body: { objectKey: auth.objectKey, contentType: file.type, byteSize: file.size },
      })
  }

  async function remove(productId: number, imageId: number): Promise<void> {
    await request<void>(`/api/v1/admin/products/${productId}/images/${imageId}`,
      { method: 'DELETE', authenticated: true })
  }

  async function listImages(productId: number): Promise<ProductImageView[]> {
    return await request<ProductImageView[]>(`/api/v1/catalog/products/${productId}/images`)
  }

  return { upload, remove, listImages, errorMessage }
}
