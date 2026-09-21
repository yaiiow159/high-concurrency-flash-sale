import { describe, expect, it } from 'vitest'
import { buildSeckillIcs } from '~/utils/seckillCalendar'

const reminder = {
  activityId: 42,
  productName: 'AirPods Pro, 第二代; 白',
  seckillPrice: 4990,
  startAt: '2026-10-01T12:00:00+08:00',
  endAt: '2026-10-01T13:00:00+08:00',
  url: 'https://shop.example/seckill/42',
}

describe('開賣提醒的 .ics', () => {
  const ics = buildSeckillIcs(reminder, Date.UTC(2026, 8, 21, 3, 0, 0))

  it('時間一律轉成 UTC，行事曆才不會因為時區設定而差八小時', () => {
    expect(ics).toContain('DTSTART:20261001T040000Z')
    expect(ics).toContain('DTEND:20261001T050000Z')
    expect(ics).toContain('DTSTAMP:20260921T030000Z')
  })

  it('開賣前十分鐘提醒', () => {
    expect(ics).toContain('TRIGGER:-PT10M')
  })

  it('品名裡的逗號與分號要跳脫', () => {
    expect(ics).toContain('SUMMARY:【限時搶購】AirPods Pro\\, 第二代\\; 白')
  })

  it('同一檔活動的 UID 固定——重複下載是更新同一個事件，不是多出一個', () => {
    expect(ics).toContain('UID:seckill-42@flashsale')
    expect(buildSeckillIcs(reminder, Date.now())).toContain('UID:seckill-42@flashsale')
  })

  it('帶著活動頁網址，並以 CRLF 換行', () => {
    expect(ics).toContain('URL:https://shop.example/seckill/42')
    expect(ics.split('\r\n')[0]).toBe('BEGIN:VCALENDAR')
    expect(ics.endsWith('END:VCALENDAR')).toBe(true)
  })
})
