/**
 * 伺服器時鐘校正。
 *
 * 客戶端時鐘可能偏差數分鐘。直接用 `Date.now()` 倒數會造成兩種問題：
 * 時鐘快的使用者提早開始狂打 API，時鐘慢的則錯過開賣。
 *
 * 校正方式與 NTP 同理——扣掉往返時間的一半：
 *
 * ```
 *   t0  用戶端送出請求
 *   t1  伺服器產生回應（回應中的 serverTime）
 *   t2  用戶端收到回應
 *
 *   偏移 ≈ t1 − (t0 + t2) / 2
 * ```
 *
 * 若直接用 `t1 − t2`，會把整個往返延遲都算進偏移，
 * 在行動網路（RTT 可達數百毫秒）下誤差相當可觀。
 */

const offsetMillis = ref(0)
const calibrated = ref(false)

export function useServerTime() {
  /**
   * 以一次請求的時間點校正偏移。
   *
   * @param serverTimeIso 伺服器回應中的時間
   * @param sentAt        送出請求的本地時間
   * @param receivedAt    收到回應的本地時間
   */
  function calibrate(serverTimeIso: string, sentAt: number, receivedAt: number): void {
    const serverMillis = new Date(serverTimeIso).getTime()
    if (Number.isNaN(serverMillis)) {
      return
    }
    offsetMillis.value = serverMillis - (sentAt + receivedAt) / 2
    calibrated.value = true
  }

  /** 校正後的「現在」。未校正前退回本地時鐘，總比沒有數字好。 */
  function now(): number {
    return Date.now() + offsetMillis.value
  }

  return {
    calibrate,
    now,
    offsetMillis: readonly(offsetMillis),
    calibrated: readonly(calibrated),
  }
}
