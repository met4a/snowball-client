import type { Rule } from './rules.js';

export interface ManifestVersion {
  id: string;
  type: 'release' | 'snapshot' | 'old_beta' | 'old_alpha' | string;
  url: string;
  time: string;
  releaseTime: string;
  sha1?: string;
}

export interface VersionManifest {
  latest: { release: string; snapshot: string };
  versions: ManifestVersion[];
}

export interface Artifact {
  path?: string;
  url: string;
  sha1?: string;
  size?: number;
}

export interface Library {
  name: string;
  /** Maven repository base (Fabric/Quilt style libraries). */
  url?: string;
  sha1?: string;
  size?: number;
  downloads?: {
    artifact?: Artifact;
    classifiers?: Record<string, Artifact>;
  };
  natives?: Partial<Record<'windows' | 'osx' | 'linux', string>>;
  extract?: { exclude?: string[] };
  rules?: Rule[];
}

export type ArgumentValue = string | { rules?: Rule[]; value: string | string[] };

export interface VersionJson {
  id: string;
  inheritsFrom?: string;
  /** Id of the version whose client jar is used (set when merging loader profiles). */
  jar?: string;
  type?: string;
  mainClass: string;
  minecraftArguments?: string;
  arguments?: { game?: ArgumentValue[]; jvm?: ArgumentValue[] };
  assetIndex?: { id: string; url: string; sha1: string; size: number; totalSize?: number };
  assets?: string;
  downloads?: { client?: Artifact; server?: Artifact; client_mappings?: Artifact };
  libraries: Library[];
  javaVersion?: { component: string; majorVersion: number };
  logging?: { client?: { argument: string; file: { id: string; url: string; sha1: string; size: number }; type: string } };
  releaseTime?: string;
  time?: string;
}

export interface AssetIndex {
  objects: Record<string, { hash: string; size: number }>;
  map_to_resources?: boolean;
  virtual?: boolean;
}
