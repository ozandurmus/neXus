declare const process: { env: Record<string, string | undefined> };
declare module "node:fs" {
  export function existsSync(path: string): boolean;
  export function readFileSync(path: string, encoding: string): string;
  export function statSync(path: string): { mode: number };
  export function unlinkSync(path: string): void;
}
declare module "node:fs/promises" {
  export function writeFile(path: string, data: string, options: { mode: number; flag: string }): Promise<void>;
}
declare module "node:crypto" {
  export function createHash(algorithm: string): { update(value: string): { digest(encoding: string): string } };
}
