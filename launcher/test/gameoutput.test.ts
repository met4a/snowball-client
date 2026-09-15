import { describe, expect, it } from 'vitest';
import { ActivityLog } from '../src/core/logging/Activity.js';
import { formatRecord, GameActivityInterpreter, GameOutputParser, type GameRecord } from '../src/core/process/GameOutput.js';

const XML = [
  '<log4j:Event logger="net.fabricmc.loader.impl.FabricLoaderImpl" timestamp="1789445269000" level="INFO" thread="main">',
  '  <log4j:Message><![CDATA[Loading Minecraft 1.21.11 with Fabric Loader 0.19.5]]></log4j:Message>',
  '</log4j:Event>',
  '',
  '<log4j:Event logger="FabricLoader" timestamp="1789445269001" level="INFO" thread="main">',
  '  <log4j:Message><![CDATA[Loading 3 mods:',
  '\t- fabric-api 0.141.6',
  '\t- snowballclient 1.1.0',
  '\t- sodium 0.8.14]]></log4j:Message>',
  '</log4j:Event>',
  '<log4j:Event logger="net.minecraft.Util" timestamp="1789445269500" level="ERROR" thread="Render thread"><log4j:Message><![CDATA[Out of memory & more]]></log4j:Message><log4j:Throwable><![CDATA[java.lang.OutOfMemoryError: Java heap space',
  '\tat net.minecraft.Foo.bar(Foo.java:1)]]></log4j:Throwable></log4j:Event>',
  '[14:27:45] [Render thread/INFO]: Sound engine started',
  'Exception in thread "main" something',
];

function parseAll(lines: string[]): GameRecord[] {
  const parser = new GameOutputParser();
  return lines.flatMap((line) => parser.push(line, line.startsWith('Exception') ? 'stderr' : 'stdout', 1000));
}

describe('game output parsing', () => {
  it('reassembles Log4j XML events and passes plain lines through', () => {
    const records = parseAll(XML);
    expect(records).toHaveLength(5);
    expect(records[0]).toMatchObject({ level: 'INFO', thread: 'main', message: 'Loading Minecraft 1.21.11 with Fabric Loader 0.19.5' });
    expect(records[1].message.split('\n')).toHaveLength(4);
    expect(records[2]).toMatchObject({ level: 'ERROR', message: 'Out of memory & more' });
    expect(records[2].throwable).toMatch(/^java\.lang\.OutOfMemoryError/);
    expect(records[3]).toMatchObject({ raw: '[14:27:45] [Render thread/INFO]: Sound engine started', level: 'INFO', thread: 'Render thread', message: 'Sound engine started' });
    expect(records[4]).toMatchObject({ raw: 'Exception in thread "main" something', level: 'ERROR' });
    expect(formatRecord(records[0])).toMatch(/^\[\d{2}:\d{2}:\d{2}\] \[main\/INFO\]: Loading Minecraft 1\.21\.11/);
    expect(formatRecord(records[3])).toBe('[14:27:45] [Render thread/INFO]: Sound engine started');
  });

  it('turns records into readable activity', () => {
    const interpreter = new GameActivityInterpreter({ minecraftVersion: '1.21.11', loader: 'fabric' });
    const messages = parseAll(XML).flatMap((r) => interpreter.interpret(r)).map((l) => `${l.level}: ${l.message}`);
    expect(messages).toEqual([
      'info: Loading Minecraft 1.21.11 with Fabric Loader 0.19.5',
      'info: Loading 3 mods',
      'success: Snowball Client 1.1.0 loaded',
      'error: Minecraft ran out of memory. Give this instance more memory in Edit > Java & Memory.',
      'success: Minecraft is ready',
      'warn: Minecraft reported an error: Exception in thread "main" something',
    ]);
  });

  it('explains world joins, incompatible mods and throttles repeated errors', () => {
    const interpreter = new GameActivityInterpreter({ minecraftVersion: '26.2', loader: 'fabric' });
    const record = (message: string, extra: Partial<GameRecord> = {}): GameRecord => ({ level: 'INFO', logger: '', thread: 'Render thread', time: 0, message, ...extra });
    const run = (r: GameRecord) => interpreter.interpret(r).map((l) => l.message);
    expect(run(record('Setting user: Met4a'))).toEqual(['Loading Minecraft 26.2']);
    expect(run(record('Starting integrated minecraft server version 26.2', { thread: 'Server thread' }))).toEqual(['Opening singleplayer world']);
    expect(run(record('Met4a joined the game', { thread: 'Server thread' }))).toEqual(['Joined the world as Met4a']);
    expect(run(record('Incompatible mods found!\n - Mod iris requires sodium 0.8.x\n - Remove optifabric', { level: 'ERROR' }))).toEqual(["Some mods can't run together: Mod iris requires sodium 0.8.x; Remove optifabric"]);
    expect(run(record('Texture missing', { level: 'ERROR', time: 10_000 }))).toEqual(['Minecraft reported an error: Texture missing']);
    expect(run(record('Texture missing again', { level: 'ERROR', time: 11_000 }))).toEqual([]);
    expect(run(record('Config broke', { level: 'ERROR', logger: 'SnowballClient/Config', time: 20_000 }))).toEqual(['Snowball Client reported an error: Config broke']);
    expect(run(record('Stopping!'))).toEqual(['Closing Minecraft']);
  });

  it('keeps an activity timeline without repeating a stage and its step', () => {
    const activity = new ActivityLog();
    activity.add('a', 'info', 'Checking core files');
    activity.add('a', 'info', 'Checking core files...');
    activity.add('a', 'success', 'Core files verified');
    expect(activity.recent('a').map((e) => e.message)).toEqual(['Checking core files', 'Core files verified']);
    expect(activity.recent('b')).toEqual([]);
  });
});
