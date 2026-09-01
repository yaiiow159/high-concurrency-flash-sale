import { backendUrl } from '../../utils/backend'

/** 註冊直接轉發；此步驟不涉及令牌，沒有需要保護的東西。 */
export default defineEventHandler(async (event) => {
  return await $fetch(backendUrl('/api/v1/auth/register'), {
    method: 'POST',
    body: await readBody(event),
  })
})
