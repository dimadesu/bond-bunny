# Stale virtual connection on re-registration — analysis (not a confirmed bug)

**Status:** Analyzed, **believed not to occur in practice.** No reproduced
symptom, no crash log, no bug report. This was found by static code reading
while auditing commit `6b6c327` ("Keep working socket when a network rebind
fails"). It is recorded here so the analysis isn't lost, **not** because a fix
is planned. On-device testing across network handoffs has not surfaced the
symptom described below.

## The theoretical gap

On the native side (`srtla_send.c`, in the sibling `srtla` fork), Android hands
pre-bound sockets to native code via `srtla_set_network_socket()` →
`add_virtual_connection()`. Each network type maps to a single fixed virtual IP
(e.g. cellular → `10.0.2.1`).

Two things line up to create a latent gap:

1. **`add_virtual_connection()` never removes an existing mapping.** It
   unconditionally prepends a new `virtual_conn_t` node for the virtual IP. A
   second registration for the same virtual IP leaves the previous node — and
   its socket fd — in the list, leaked (never freed, never closed).

2. **A live `conn_t` is never told a new socket exists.** `open_socket()` only
   re-fetches `vc->socket_fd` when `c->fd < 0`. Connection lookup
   (`conn_find_by_src()`) is keyed on the virtual IP's fixed sockaddr bytes, so
   the *same* `conn_t` survives a "replacement". The timeout path in
   `connection_housekeeping()` resets stats and re-sends REG2 on the **same**
   fd — it never sets `c->fd = -1`. So the conn stays pinned to the old fd,
   while the freshly registered replacement socket sits unused in the leaked
   node.

### When it could theoretically trigger

Only when the **same** virtual IP is registered a second time while its
`conn_t` is still alive and was never reset to `fd < 0` — i.e. the
`isReplacement == true` path in
[`SrtlaSender.handleDedicatedNetworkAvailable()`](../srtla-lib/src/main/java/com/dimadesu/bondbunny/SrtlaSender.java#L403)
succeeds mid-stream:

- Cellular tower / IP handoff producing a new bound socket (same `10.0.2.1`).
- Wi-Fi drop and reconnect while the stream keeps running.

### Symptom to watch for (if it ever does happen)

- One bonded link goes quiet after a network blip and **never recovers on its
  own** — bitrate drops to ~0 even though that network is healthy at the OS
  level — until a full stream disconnect/reconnect.
- Distinguished from ordinary flakiness, which recovers by itself in seconds.

If this is ever observed, the two log-only probes below turn it from
theoretical into confirmed.

## Why no fix was implemented

The obvious fix (a `remove_virtual_connection()` that closes the stale fd and
invalidates the pinned `conn_t`) was drafted on the `native-theoretical-fix`
branch of the `srtla` fork, **but it is not safe as written**:

- `add_virtual_connection()` runs on the Android `NetworkCallback` thread, not
  the srtla loop thread.
- There is **no mutex** over `conns`, `virtual_connections`, or `active_fds`
  (only `java_owned_fds` is locked).
- Tearing down the conn list, mutating `active_fds`, and `close()`-ing an fd
  from that off-loop thread races the main loop's `select()`/`sendto()`/
  `recvfrom()` — a use-after-close / close-of-in-use hazard.

That would trade a rare, silent, self-limiting gap for an intermittent crash —
harder to diagnose than the thing it fixes. A correct fix would do the teardown
**on the srtla loop thread** (record the new socket at registration; let
`update_conns()` / housekeeping, which already own `conns` and `active_fds`,
close the old fd and set `c->fd = -1` so `open_socket()` re-fetches). That is
non-trivial and unjustified without a reproduced symptom.

## If evidence ever appears — log-only probes first

These are behavior-neutral (no fd/list mutation) and safe to ship in a test
build:

1. In `add_virtual_connection()`, log a **warning** when
   `find_virtual_connection(virtual_ip)` is non-NULL before prepending — proves
   the precondition is actually hit.
2. In `open_socket()`'s virtual-IP branch, log the resolved fd vs. the newest
   `vc->socket_fd` for that virtual IP — divergence means a stale fd is pinned.

If those never fire across real tower/Wi-Fi handoffs, the gap is unreachable in
practice and this can be closed as a non-issue.

## References

- Audited commit: `6b6c327` — "Keep working socket when a network rebind fails"
- Related fix (the real one for the Samsung symptom): `ca5ff2b` — "Skip
  non-internet cellular networks when recreating sockets"
- Drafted (unsafe, do not merge) native fix: `native-theoretical-fix` branch in
  the `srtla` fork
