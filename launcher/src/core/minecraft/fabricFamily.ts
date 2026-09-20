/**
 * Fabric itself supports Minecraft 1.14 and newer. Legacy Fabric runs the same Fabric Loader on 1.3 to
 * 1.13.2 with its own intermediary names, so mods and the API for those versions are published separately.
 */
export function usesLegacyFabric(minecraftVersion: string): boolean {
  const m = /^1\.(\d+)(?:\.\d+)?$/.exec(minecraftVersion);
  return m !== null && Number(m[1]) >= 3 && Number(m[1]) <= 13;
}

export interface FabricApiInfo {
  /** Mod id in fabric.mod.json. */
  id: string;
  /** Modrinth project slug. */
  slug: string;
  name: string;
  /** Modrinth loader tag the API is published under. */
  modrinthLoader: string;
}

const FABRIC_API: FabricApiInfo = { id: 'fabric-api', slug: 'fabric-api', name: 'Fabric API', modrinthLoader: 'fabric' };
const LEGACY_FABRIC_API: FabricApiInfo = { id: 'legacy-fabric-api', slug: 'legacy-fabric-api', name: 'Legacy Fabric API', modrinthLoader: 'legacy-fabric' };

/** The API Fabric mods (and Snowball Client) need on a Minecraft version. */
export function fabricApiFor(minecraftVersion: string): FabricApiInfo {
  return usesLegacyFabric(minecraftVersion) ? LEGACY_FABRIC_API : FABRIC_API;
}

export function isFabricApi(id: string | null): boolean {
  return id === FABRIC_API.id || id === LEGACY_FABRIC_API.id;
}

/** Player-facing name of a Fabric API jar from its mod id. */
export function fabricApiName(id: string | null): string {
  return id === LEGACY_FABRIC_API.id ? LEGACY_FABRIC_API.name : FABRIC_API.name;
}
