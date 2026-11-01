// Arti (the Tor Project's Tor in Rust) linked into Kilowallet iOS: tor-engine/kilombino-tor/src/lib.rs.
#ifndef KILOMBINO_TOR_H
#define KILOMBINO_TOR_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Starts Arti: a SOCKS proxy on 127.0.0.1:port, cache and state under dir, log appended to
/// log_path. NULL once the port listens (it then connects to Tor on its own thread), else why it
/// could not start (free with kilombino_tor_free). Blocks briefly: never on the main thread.
char *kilombino_tor_start(const char *dir, const char *log_path, uint16_t port);

/// Asks Arti to stop; returns at once.
void kilombino_tor_stop(void);

/// 0: not running. 1: running, still connecting. 2: the SOCKS port carries traffic.
int32_t kilombino_tor_state(void);

void kilombino_tor_free(char *s);

#ifdef __cplusplus
}
#endif

#endif
