/**
 * CPU 取樣器。獨立行程執行，避免阻塞壓測工具的事件迴圈
 * （execSync 會擋住 Node 單執行緒的事件迴圈，而 autocannon 就跑在上面）。
 *
 * 取樣時間由參數決定、時間到自己寫檔結束。**不靠訊號通知**：
 * Windows 不支援 SIGTERM 的優雅處理，kill() 會直接砍掉行程，
 * 註冊在 process.on('SIGTERM') 的寫檔永遠不會執行。
 *
 * 容器：docker stats 的 CPUPerc 是單核百分比，20 核滿載顯示 2000%。
 * 原生行程：Get-Process 的 CPU 是累計秒數，兩點差除以牆鐘時間才是佔用核心數。
 *
 * 用法：node sampler.mjs <輸出檔> <取樣秒數>
 */
import { execSync } from 'child_process'
import { writeFileSync } from 'fs'

const outPath = process.argv[2] ?? 'sample.json'
const durationSecs = Number(process.argv[3] ?? 20)
const CORES = 20

function sampleContainers() {
  try {
    return execSync('docker stats --no-stream --format "{{.Name}}|{{.CPUPerc}}"', { encoding: 'utf8', timeout: 20000 })
      .trim().split('\n').filter(Boolean)
      .map(line => { const [name, cpu] = line.split('|'); return { name, cpu: parseFloat(cpu) || 0 } })
  } catch { return [] }
}

function sampleProcesses() {
  try {
    const out = execSync(
      'powershell -NoProfile -Command "Get-Process java,node -ErrorAction SilentlyContinue | Select-Object Id,ProcessName,CPU | ConvertTo-Json -Compress"',
      { encoding: 'utf8', timeout: 20000 })
    const parsed = JSON.parse(out)
    return Array.isArray(parsed) ? parsed : [parsed]
  } catch { return [] }
}

const procBefore = sampleProcesses()
const wallStart = Date.now()
const containerSamples = []

while ((Date.now() - wallStart) / 1000 < durationSecs) {
  containerSamples.push(sampleContainers())
}

const wallSecs = (Date.now() - wallStart) / 1000
const procAfter = sampleProcesses()

const byName = {}
for (const sample of containerSamples) {
  for (const { name, cpu } of sample) (byName[name] ??= []).push(cpu)
}
const containers = Object.entries(byName)
  .map(([name, v]) => ({ name, cpuPct: +(v.reduce((a, b) => a + b, 0) / v.length).toFixed(1) }))
  .filter(c => c.cpuPct >= 1)
  .sort((a, b) => b.cpuPct - a.cpuPct)

const processes = procBefore
  .map(before => {
    const after = procAfter.find(p => p.Id === before.Id)
    if (!after) return null
    const cores = (after.CPU - before.CPU) / wallSecs
    return { pid: before.Id, name: before.ProcessName, cores: +cores.toFixed(2), pctOfMachine: +(cores / CORES * 100).toFixed(1) }
  })
  .filter(p => p && p.cores >= 0.05)
  .sort((a, b) => b.cores - a.cores)

writeFileSync(outPath, JSON.stringify({
  containers, processes,
  containerCpuTotal: +containers.reduce((s, c) => s + c.cpuPct, 0).toFixed(1),
  nativeCoresTotal: +processes.reduce((s, p) => s + p.cores, 0).toFixed(2),
  samples: containerSamples.length,
  wallSecs: +wallSecs.toFixed(1),
}, null, 2))
