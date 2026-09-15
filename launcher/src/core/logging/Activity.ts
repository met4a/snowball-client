import { EventEmitter } from 'node:events';
import { getLogger } from './Logger.js';

const log = getLogger('activity');
const MAX_EVENTS = 400;

export type ActivityLevel = 'info' | 'success' | 'warn' | 'error';

export interface ActivityEvent {
  instanceId: string;
  time: number;
  level: ActivityLevel;
  message: string;
}

const same = (a: string, b: string) => a.replace(/[.\s]+$/, '').toLowerCase() === b.replace(/[.\s]+$/, '').toLowerCase();

/**
 * The readable per-instance timeline ("[Snowball] Starting Minecraft..."), kept apart from the technical
 * game log. Every event is also written to the launcher log file.
 */
export class ActivityLog extends EventEmitter {
  private readonly events = new Map<string, ActivityEvent[]>();

  add(instanceId: string, level: ActivityLevel, message: string): ActivityEvent {
    const list = this.events.get(instanceId) ?? [];
    const last = list[list.length - 1];
    const now = Date.now();
    // A progress stage and the step that reports it ("Checking core files" / "Checking core files...") show once.
    if (last && same(last.message, message) && now - last.time < 3000) return last;
    const event: ActivityEvent = { instanceId, time: now, level, message };
    list.push(event);
    if (list.length > MAX_EVENTS) list.splice(0, list.length - MAX_EVENTS);
    this.events.set(instanceId, list);
    const line = `[Snowball] ${message}`;
    if (level === 'error') log.error(line, { instanceId });
    else if (level === 'warn') log.warn(line, { instanceId });
    else log.info(line, { instanceId });
    this.emit('activity', event);
    return event;
  }

  reporter(instanceId: string): (level: ActivityLevel, message: string) => void {
    return (level, message) => void this.add(instanceId, level, message);
  }

  recent(instanceId: string): ActivityEvent[] {
    return [...(this.events.get(instanceId) ?? [])];
  }
}
