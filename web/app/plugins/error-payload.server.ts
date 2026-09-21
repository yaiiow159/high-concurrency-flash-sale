/**
 * 錯誤頁的 payload 來自 h3 解析的 query，是沒有原型的物件；
 * Pinia 的 payload 外掛對它呼叫 `obj.hasOwnProperty` 會直接炸掉，
 * 結果是「錯誤頁自己 500」。序列化前攤平成一般物件。
 */
export default defineNuxtPlugin((nuxtApp) => {
  nuxtApp.hooks.hook('app:rendered', () => {
    const payload = nuxtApp.payload as Record<string, unknown>
    payload.error = withPrototype(payload.error)
  })
})

function withPrototype(value: unknown): unknown {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    return value
  }
  if (Object.getPrototypeOf(value) !== null) {
    const record = value as Record<string, unknown>
    if ('data' in record) {
      record.data = withPrototype(record.data)
    }
    return value
  }
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>).map(([key, inner]) => [key, withPrototype(inner)]))
}
