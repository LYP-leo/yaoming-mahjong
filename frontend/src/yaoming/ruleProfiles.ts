import type { RuleId, RuleIdentity } from './types'

export const ruleChoices: { id: RuleId; name: string }[] = [
  { id: 'yaoming-3p', name: '三人要命麻将' },
  { id: 'yaoming-4p', name: '四人要命麻将 · 实验性' },
]
export const isRuleId = (id: unknown): id is RuleId => id === 'yaoming-3p' || id === 'yaoming-4p'
export class UnsupportedRuleError extends Error {
  constructor() { super('当前版本不支持此规则，请更新页面后重试。'); this.name = 'UnsupportedRuleError' }
}
export function ruleIdOf(value?: RuleIdentity | null): RuleId {
  const id: unknown = value?.ruleId
  // Records created before named profiles always used the three-player rules.
  // Occupancy and an untrusted capacity field must never choose another ruleset.
  if (id == null || id === '') return 'yaoming-3p'
  if (!isRuleId(id)) throw new UnsupportedRuleError()
  return id
}
export const capacityOf = (value?: RuleIdentity | null): 3 | 4 => ruleIdOf(value) === 'yaoming-4p' ? 4 : 3
export const minimumFanOf = (value?: RuleIdentity | null): 3 | 4 => ruleIdOf(value) === 'yaoming-4p' ? 3 : 4
export const playerCountLabel = (count: number) => count === 4 ? '四人' : '三人'
export function ruleNameOf(value?: RuleIdentity | null) {
  const id = ruleIdOf(value)
  return value?.ruleName || ruleChoices[id === 'yaoming-4p' ? 1 : 0].name
}
export const seatWinds = (count: number): string[] => ['东', '南', '西', '北'].slice(0, count)
export const windAt = (seat: number, dealer: number, count: number) => seatWinds(count)[((seat - dealer) % count + count) % count]
