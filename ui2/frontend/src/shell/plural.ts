/** `1 interface`, `2 interfaces`: a count with its noun, the noun pluralised unless `n` is exactly 1 (UI review 2026-10-18, P0-5B). */
export function plural(n: number, one: string, many: string = `${one}s`): string {
  return `${n} ${n === 1 ? one : many}`;
}
