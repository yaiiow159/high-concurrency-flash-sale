export interface SeckillReminder {
  activityId: number
  productName: string
  seckillPrice: number
  startAt: string
  endAt: string
  /** 活動頁的完整網址，提醒跳出來時要能一鍵回到這一頁 */
  url: string
}

/** 開賣前幾分鐘提醒。要留時間登入與領搶購資格，太接近開賣就來不及了。 */
const REMIND_BEFORE_MINUTES = 10

/** iCalendar 的 UTC 時間格式：20260921T120000Z */
function stamp(iso: string | number): string {
  return new Date(iso).toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '')
}

/** 逗號、分號、反斜線與換行在 iCalendar 的文字欄位裡都要跳脫，否則會被當成分隔符。 */
function escapeText(value: string): string {
  return value.replace(/\\/g, '\\\\').replace(/([,;])/g, '\\$1').replace(/\r?\n/g, '\\n')
}

/**
 * 產生開賣提醒的 .ics。走行事曆而不是站內通知：
 * 它不需要後端排程，而且提醒會出現在使用者本來就在看的地方。
 */
export function buildSeckillIcs(reminder: SeckillReminder, now: number): string {
  const title = `【限時搶購】${reminder.productName}`
  const description = `NT$ ${reminder.seckillPrice.toLocaleString()} 開賣。`
    + `先登入並領好搶購資格，開賣時只剩一顆按鈕要按。\n${reminder.url}`
  return [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//FlashSale//Seckill Reminder//ZH',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    'BEGIN:VEVENT',
    `UID:seckill-${reminder.activityId}@flashsale`,
    `DTSTAMP:${stamp(now)}`,
    `DTSTART:${stamp(reminder.startAt)}`,
    `DTEND:${stamp(reminder.endAt)}`,
    `SUMMARY:${escapeText(title)}`,
    `DESCRIPTION:${escapeText(description)}`,
    `URL:${reminder.url}`,
    'BEGIN:VALARM',
    'ACTION:DISPLAY',
    `DESCRIPTION:${escapeText(title)}`,
    `TRIGGER:-PT${REMIND_BEFORE_MINUTES}M`,
    'END:VALARM',
    'END:VEVENT',
    'END:VCALENDAR',
  ].join('\r\n')
}
