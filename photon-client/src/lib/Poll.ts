// SpectrumJetson (photonvision-67): the dashboard's pollers (robot state, Rewind, tag contrast, the
// excluded tags) ran every second or two with no timeout. On a slow or dropped link each tick
// started another request while the last ones were still out, piling up on the Jetson.

/** Requests from a poller give up after this long, so a dropped link doesn't leave one hanging. */
export const POLL_TIMEOUT_MS = 10_000;

/** fn, skipped while its last call is still running (one request in flight, however many callers). */
export const oneAtATime = <A extends unknown[]>(fn: (...args: A) => Promise<void>) => {
  let running = false;
  return async (...args: A) => {
    if (running) return;
    running = true;
    try {
      await fn(...args);
    } finally {
      running = false;
    }
  };
};
