import { createServer } from 'node:http';
import { config, Core, Provider, Journal, runJob, sleep } from './runtime.mjs';

let settings;
try { settings = config(); } catch (error) { console.error(error.message); process.exit(1); }
if (process.argv.includes('--check')) { console.log('Runtime configuration structure valid; no channel request performed.'); process.exit(0); }
const journal = new Journal(settings.journalPath, settings.dailyLimit);
const core = new Core(settings), provider = new Provider(settings, journal);
const stop = new AbortController(); let lastPoll = Date.now(), active = false;
const server = createServer((request, response) => {
  if (request.url !== '/health') { response.writeHead(404); response.end(); return; }
  const healthy = !stop.signal.aborted && (active || Date.now() - lastPoll < 60000);
  response.writeHead(healthy ? 200 : 503, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify({ status: healthy ? 'UP' : 'DOWN', version: '1.0.0' }));
});
server.listen(8081, '0.0.0.0');
for (const event of ['SIGTERM', 'SIGINT']) process.on(event, () => { stop.abort(); server.close(); });
while (!stop.signal.aborted) {
  try {
    const jobs = await core.post('/internal-api/design/v1/ai-jobs/claims', { workerId: settings.workerId, maxJobs: 1 }, stop.signal);
    if (!Array.isArray(jobs) || jobs.length > 1) throw Error('CLAIM_CONTRACT');
    lastPoll = Date.now();
    for (const job of jobs) {
      active = true;
      try { await runJob(job, core, provider, settings, { signal: stop.signal }); console.log(JSON.stringify({ event: 'job_completed', jobId: job.jobId })); }
      catch { console.error(JSON.stringify({ event: 'job_attempt_incomplete', jobId: /^[0-9]+$/.test(job.jobId) ? job.jobId : 'invalid' })); }
      finally { active = false; }
    }
  } catch { if (!stop.signal.aborted) console.error('{"event":"core_poll_failed"}'); }
  if (!stop.signal.aborted) await sleep(3000);
}
