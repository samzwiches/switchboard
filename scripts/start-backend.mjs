import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { parseEnv } from 'node:util';
import { createServer } from 'node:net';
import { execFileSync, spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { resolve, dirname } from 'node:path';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const backend = resolve(root, 'backend');
const readEnv = path => existsSync(path) ? parseEnv(readFileSync(path, 'utf8')) : {};
const local = { ...readEnv(resolve(backend, '.env')), ...readEnv(resolve(backend, '.dev.vars')) };
const env = { ...process.env, ...local };
// A local pointer can reuse a secret file without copying the key into this repository.
if (!env.OPENAI_API_KEY && env.SWITCHBOARD_SECRET_FILE) {
  env.OPENAI_API_KEY = readEnv(resolve(backend, env.SWITCHBOARD_SECRET_FILE)).OPENAI_API_KEY;
}
if (process.env.OPENAI_API_KEY) env.OPENAI_API_KEY = process.env.OPENAI_API_KEY;
const port = Number(process.env.PORT || local.PORT || 8787);
if (!Number.isInteger(port) || port < 1024 || port > 65535) {
  console.error('PORT must be a number from 1024 to 65535.');
  process.exit(1);
}
let lanIp = '';
try {
  const route = execFileSync('/sbin/route', ['-n', 'get', 'default'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] });
  const networkInterface = route.match(/interface:\s*(\S+)/)?.[1];
  if (networkInterface) lanIp = execFileSync('/usr/sbin/ipconfig', ['getifaddr', networkInterface], { encoding: 'utf8' }).trim();
} catch { /* Emulator development still works when the Mac is offline. */ }
if (!/^\d{1,3}(\.\d{1,3}){3}$/.test(lanIp)) lanIp = '';
const properties = resolve(root, 'local.properties');
let content = existsSync(properties) ? readFileSync(properties, 'utf8') : '';
for (const [name, value] of Object.entries({ SWITCHBOARD_DEBUG_PORT: port, SWITCHBOARD_DEBUG_LAN_URL: lanIp ? `http://${lanIp}:${port}` : '' })) {
  content = content.replace(new RegExp(`^${name}=.*(?:\\r?\\n|$)`, 'gm'), '');
  content = content.trimEnd() + `\n${name}=${value}\n`;
}
writeFileSync(properties, content);
console.log(`Emulator Backend URL: http://10.0.2.2:${port}`);
console.log(lanIp ? `Phone Backend URL: http://${lanIp}:${port} (same Wi-Fi as this Mac)` : 'No LAN address detected. Connect the Mac to Wi-Fi before building for a phone.');
console.log(`Health: http://127.0.0.1:${port}/health`);
console.log('Debug defaults refreshed in ignored local.properties. Rebuild the APK if the address changed.');
console.log(env.OPENAI_API_KEY ? 'Existing OpenAI key loaded for backend only.' : 'OpenAI key missing. Health checks work; configure backend/.env before asking Lucas a question.');
if (process.argv.includes('--configure-only')) process.exit(0);
const portAvailable = await new Promise((resolveAvailability) => {
  const probe = createServer();
  probe.once('error', () => resolveAvailability(false));
  probe.listen(port, '0.0.0.0', () => probe.close(() => resolveAvailability(true)));
});
if (!portAvailable) {
  let existing;
  try {
    const response = await fetch(`http://127.0.0.1:${port}/health`, { signal: AbortSignal.timeout(2000) });
    existing = await response.json();
  } catch { /* Report a port conflict without guessing another port. */ }
  if (existing?.ok === true && existing?.service === 'switchboard') {
    console.log(`Switchboard backend is already running on port ${port}.`);
    process.exit(0);
  }
  console.error(`Port ${port} is occupied. Stop that service or set PORT in backend/.env, then rerun this script and rebuild the debug APK.`);
  process.exit(1);
}
if (!existsSync(resolve(backend, 'node_modules/wrangler/bin/wrangler.js'))) {
  execFileSync('npm', ['ci', '--no-audit', '--no-fund'], { cwd: backend, stdio: 'inherit' });
}
const child = spawn(process.execPath, ['node_modules/wrangler/bin/wrangler.js', 'dev', '--ip', '0.0.0.0', '--port', String(port)], {
  cwd: backend, env: { ...env, WRANGLER_SEND_METRICS: 'false' }, stdio: 'inherit',
});
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => child.kill(signal));
child.on('exit', code => process.exit(code ?? 0));
