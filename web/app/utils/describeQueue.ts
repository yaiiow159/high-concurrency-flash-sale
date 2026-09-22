import type { OrderQueue } from '~/types/api'

/** 排隊提示。等待秒數為 -1 代表算不出來，此時只說「時間待估」而不是「約 0 秒」。 */
export function describeQueue(queue: OrderQueue | null | undefined): string | null {
  if (!queue) {
    return null
  }
  const ahead = `前面約 ${queue.ahead.toLocaleString()} 筆`
  if (queue.estimatedWaitSeconds < 0) {
    return `${ahead}，時間待估`
  }
  return queue.estimatedWaitSeconds < 60
    ? `${ahead}，約 ${queue.estimatedWaitSeconds} 秒`
    : `${ahead}，約 ${Math.ceil(queue.estimatedWaitSeconds / 60)} 分鐘`
}
