### 1. The 5-Minute Black Hole on a Dying Candidate

* **Timing & Sequence:**
  * **t = 0s:** Tunnel is on Home server. A transient 5.1s packet stall causes an in-tunnel HTTP probe to time out.
  * **t = 5.1s:** `consecutiveFailures = 1`. `FAILURES_BEFORE_SWAP = 1` triggers `Decision.LeaveCurrent`. Watchdog measures candidates and swaps to Candidate B. Core reloads; `millisSinceLastSwitch = 0`.
  * **t = 25.1s:** Candidate B (an overloaded public node) crashes or gets IP-blocked. Watchdog sends an in-tunnel probe (`PROBE_INTERVAL_MILLIS = 20_000L`). Probe times out at **t = 30.1s**.
  * **t = 30.1s:** `consecutiveFailures = 1`. `decide()` evaluates `millisSinceLastSwitch` = 25,000ms < `SWITCH_COOLDOWN_MILLIS` (300,000ms). Policy returns `Decision.Hold`.
  * **t = 35.1s, 45.1s, ... 300.1s:** Watchdog switches to `SUSPECT_PROBE_INTERVAL_MILLIS` (5,000L), probing every 10s (5s wait + 5s timeout). Every probe fails. For 54 consecutive failed probes, `decide()` returns `Decision.Hold`.
  * **t = 305.1s:** `millisSinceLastSwitch` reaches 300,000ms. Policy finally returns `Decision.LeaveCurrent`.
* **Policy Step-by-Step:**
  1. Hair-trigger `FAILURES_BEFORE_SWAP = 1` abandons Home on a single glitch.
  2. Candidate B passes an instantaneous pre-swap check but dies immediately after.
  3. `SWITCH_COOLDOWN_MILLIS` (5 minutes) unconditionally locks the tunnel to Candidate B despite `consecutiveFailures` climbing into the dozens.
* **User Experience:** Total connectivity blackout for 4 minutes and 35 seconds. The user cannot load a single webpage, while the watchdog frantically probes a dead server every 5 seconds refusing to switch.
* **Likelihood:** **High.** Public VPN subscription nodes crash, throttle, or get blocked under load constantly.
* **Harm:** **Critical.** The user is stranded with zero internet for 5 minutes when healthy candidates are available.

---

### 2. Permanent Home Server Exile via Routine Commute Drops

* **Timing & Sequence:**
  * **t = 0m:** User enters an elevator. Cellular drops for 6s. In-tunnel probe times out. `FAILURES_BEFORE_SWAP = 1` -> `LeaveCurrent` to Candidate B (`failbacksSoFar = 0`).
  * **t = 5m:** User exits elevator. Direct probe to Home passes 5 consecutive checks (`PROBES_BEFORE_FAILBACK = 5`, ~100s). Cooldown (`SWITCH_COOLDOWN_MILLIS = 300_000L`) expires. `decide()` returns `Decision.ReturnHome` (`failbacksSoFar = 1`).
  * **t = 45m:** User enters an underground parking garage. Cellular drops for 6s. Probe times out -> `LeaveCurrent` to Candidate C (`failbacksSoFar = 1`).
  * **t = 50m:** Home server answers 5 consecutive direct probes. Cooldown expires. `decide()` returns `Decision.ReturnHome` (`failbacksSoFar = 2`).
  * **t = 2h:** User drives through a tunnel. Cellular drops for 6s. Probe times out -> `LeaveCurrent` to Candidate D (`failbacksSoFar = 2`).
  * **t = 2h 2m:** Home server answers 5 consecutive direct probes (`consecutiveHomeRecoveries = 5`).
  * **t = 2h 2m:** `decide()` evaluates `hasHome && consecutiveHomeRecoveries >= 5`. Because `failbacksSoFar >= MAX_FAILBACKS` (2 >= 2), it returns `Decision.AbandonHome`.
* **Policy Step-by-Step:**
  1. `FAILURES_BEFORE_SWAP = 1` treats routine mobile coverage gaps as server failures.
  2. Each round trip increments `failbacksSoFar`.
  3. On the 3rd minor signal drop, `MAX_FAILBACKS = 2` permanently discards the user’s selected Home server.
* **User Experience:** The user is permanently exiled to a random fallback candidate server for the rest of their session/day, losing their dedicated IP, port forwarding, or local geolocated routing, despite their Home server being 100% operational.
* **Likelihood:** **Very High.** Anyone commuting through subways, basements, or elevators hits three 6-second signal drops within a couple of hours.
* **Harm:** **High.** Silent, irreversible loss of user server configuration.

---

### 3. Bufferbloat & Download Saturation Kill-Loop

* **Timing & Sequence:**
  * **t = 0s:** User starts a large 5 GB file download or 4K video stream over Home server.
  * **t = 20s:** Link saturation and bufferbloat push latency to 5.5s.
  * **t = 25s:** In-tunnel probe HTTP GET reaches its 5.0s timeout and fails (`consecutiveFailures = 1`).
  * **t = 25.1s:** `decide()` sees `consecutiveFailures >= FAILURES_BEFORE_SWAP (1)`. Returns `Decision.LeaveCurrent`.
  * **t = 25.5s:** Core reloads. **All active TCP sockets are killed.** Download aborts with `ECONNRESET`.
  * **t = 26s:** Tunnel connects to Candidate B. Download manager retries download, saturating link again.
  * **t = 5m 26s:** Cooldown expires. Another probe times out under bufferbloat -> `LeaveCurrent` to Candidate C, killing the download again.
* **Policy Step-by-Step:**
  1. Setting `FAILURES_BEFORE_SWAP = 1` with an in-tunnel probe confuses congested queues with dead servers.
  2. `LeaveCurrent` reloads the core, dropping all in-flight connections.
* **User Experience:** Large file downloads, torrents, or video streams repeatedly fail and corrupt. User cannot saturate their connection without triggering a self-inflicted disconnect.
* **Likelihood:** **High.** Saturating consumer mobile or broadband connections easily pushes latency past 5 seconds.
* **Harm:** **High.** Breaks long-running TCP transfers entirely.

---

### 4. Android Doze / Standby Wakeup False Failover

* **Timing & Sequence:**
  * **t = 0s:** Screen turns off; phone enters deep sleep (Doze). OS suspends network interfaces and coroutines.
  * **t = 1200s (20m later):** User picks up phone and presses power button.
  * **t = 1200.1s:** Watchdog coroutine resumes mid-probe or immediately fires a probe before cellular baseband / Wi-Fi re-authenticates (which takes 1–3s).
  * **t = 1205.1s:** Probe times out due to waking network stack (`consecutiveFailures = 1`).
  * **t = 1205.2s:** `decide()` checks `millisSinceLastSwitch`. Wall-clock `System.currentTimeMillis()` shows 1205s > `SWITCH_COOLDOWN_MILLIS` (300s). `afterHandover` is `false` (no interface change broadcast yet).
  * **t = 1205.2s:** `decide()` returns `Decision.LeaveCurrent`.
  * **t = 1206s:** Sing-box core is torn down and reloaded on Candidate B, dropping background syncs and notifications that were just starting to flow.
* **Policy Step-by-Step:**
  1. Probing immediately after Doze unfreeze catches the cellular modem during baseband wake-up.
  2. Cooldown check passes because sleep time counted towards `millisSinceLastSwitch`.
  3. `FAILURES_BEFORE_SWAP = 1` instantly swaps the server on screen unlock.
* **User Experience:** Every time the user wakes their phone after leaving it on a desk, active VPN connections drop, background notifications stall, and the server switches to a random candidate.
* **Likelihood:** **Very High.** Standard Android power management behavior.
* **Harm:** **High.** Unnecessary failover and connection resets on every device wake.

---

### 5. Marginal Wi-Fi Handover Thrashing (`HANDOVER_COOLDOWN_MILLIS = 30s`)

* **Timing & Sequence:**
  * **t = 0s:** User walks near a known Wi-Fi hotspot (e.g., edge of home/work network). Phone associates with Wi-Fi.
  * **t = 3s:** `HANDOVER_SETTLE_MILLIS = 3_000L` expires. Watchdog probes Wi-Fi through tunnel. Wi-Fi has weak RSSI; probe times out at **t = 8s**.
  * **t = 8s:** `consecutiveFailures = 1`, `afterHandover = true`. `millisSinceLastSwitch >= HANDOVER_COOLDOWN_MILLIS (30s)`. `decide()` returns `Decision.LeaveCurrent`. Core reloads to Candidate B; all sockets killed.
  * **t = 15s:** Phone loses weak Wi-Fi signal, drops to LTE. Handover triggered.
  * **t = 18s:** `HANDOVER_SETTLE_MILLIS = 3_000L` expires. Watchdog probes LTE. LTE data bearer is still negotiating; probe times out at **t = 23s**.
  * **t = 38s:** Next probe on LTE fails again at **t = 43s**.
  * **t = 43s:** `afterHandover = true`. `millisSinceLastSwitch` = 35s > `HANDOVER_COOLDOWN_MILLIS` (30s). `decide()` returns `Decision.LeaveCurrent`. Core reloads to Candidate C.
* **Policy Step-by-Step:**
  1. `HANDOVER_COOLDOWN_MILLIS = 30_000L` lowers the swap barrier from 5 minutes to 30 seconds.
  2. `HANDOVER_SETTLE_MILLIS = 3_000L` is too short for marginal Wi-Fi / cellular radio bearer attachment.
  3. A single failure (`FAILURES_BEFORE_SWAP = 1`) triggers rapid back-to-back core reloads across interface transitions.
* **User Experience:** VoIP calls drop, web requests fail, and the VPN client aggressively thrashes through servers while walking down a street or near Wi-Fi boundaries.
* **Likelihood:** **Very High.** Occurs whenever walking past saved Wi-Fi networks or in marginal reception.
* **Harm:** **Medium-High.** Continuous connection resets during common mobile transitions.

---

### 6. Protocol-Obfuscated Firewalls: Direct-Probe Asymmetry Trap

* **Timing & Sequence:**
  * **t = 0s:** User is in a censored region (e.g., GFW) or behind a strict enterprise firewall where direct IPs and standard proxy ports are blocked, but CDN/WebSocket/VLESS routing works inside the tunnel.
  * **t = 10s:** A transient glitch causes a single in-tunnel probe failure -> `LeaveCurrent` to Candidate B.
  * **t = 30s:** Home server is operational via CDN, but watchdog probes Home *directly in parallel* using a direct TCP/TLS handshake against the server's raw port.
  * **t = 35s, 55s, 75s...:** The local firewall drops all direct SYN packets to the Home server's port. Direct probes consistently time out.
  * **t = infinity:** `consecutiveHomeRecoveries` never increments past 0. `decide()` never returns `Decision.ReturnHome`.
* **Policy Step-by-Step:**
  1. The watchdog uses an in-tunnel probe for the active server, but an out-of-tunnel direct TCP/TLS handshake for the Home server.
  2. In restrictive environments, the direct path is blocked by design while the proxied transport is unblocked.
  3. The policy permanently traps the user on the fallback server.
* **User Experience:** A single 5-second hiccup permanently disconnects the user from their primary obfuscated proxy for the remainder of the session.
* **Likelihood:** **High.** A primary use-case for sing-box is bypassing DPI firewalls that block direct server handshakes.
* **Harm:** **High.** Complete failure of the failback mechanism in censored environments.

---

### 7. Direct-Probe False Positive Flap & Abandonment Storm

* **Timing & Sequence:**
  * **t = 0s:** User on Candidate B after a failover. Home server host machine is up (TCP port 443 answers SYN), but its upstream internet routing or sing-box daemon is dead.
  * **t = 100s:** Direct TCP/TLS handshake against Home server's port succeeds 5 times in a row (`PROBES_BEFORE_FAILBACK = 5`).
  * **t = 300s:** Cooldown expires. `decide()` returns `Decision.ReturnHome`. Core reloads to Home.
  * **t = 305s:** First in-tunnel HTTP probe fails immediately (`consecutiveFailures = 1`).
  * **t = 305.1s:** `decide()` returns `Decision.LeaveCurrent` (`failbacksSoFar = 1`). Core reloads to Candidate C.
  * **t = 405s:** Direct TCP probe to Home succeeds 5 times again. Cooldown expires at **t = 605s** -> `Decision.ReturnHome`. Core reloads to Home.
  * **t = 610s:** In-tunnel probe fails immediately -> `Decision.LeaveCurrent` (`failbacksSoFar = 2`). Core reloads to Candidate D.
  * **t = 710s:** Direct probe succeeds 5 times again. `decide()` evaluates `failbacksSoFar >= MAX_FAILBACKS (2)` and permanently executes `Decision.AbandonHome`.
* **Policy Step-by-Step:**
  1. Direct probe tests L4 port reachability, not end-to-end proxy transit.
  2. Tunnel switches to broken Home server, immediately fails in-tunnel probe, and leaves.
  3. Consumes `MAX_FAILBACKS` in rapid succession, reloading core 4 times and dropping connections each time.
* **User Experience:** Tunnel flips back and forth, tearing down active connections twice over 10 minutes, before permanently abandoning the Home server.
* **Likelihood:** **Medium-High.** Common VPS failure mode (WAN gateway down or proxy process hung while OS networking stack remains responsive).
* **Harm:** **High.** Repeated connection tearing followed by permanent abandonment of Home.

---

### 8. Captive Portal 200 OK False-Health Blackout

* **Timing & Sequence:**
  * **t = 0s:** User connects to a public Wi-Fi hotspot (hotel, airport, cafe) requiring a splash-page login.
  * **t = 3s:** `HANDOVER_SETTLE_MILLIS = 3_000L` expires. Watchdog sends HTTP GET probe through tunnel.
  * **t = 4s:** The captive portal intercepts HTTP GET traffic and returns `HTTP/1.1 200 OK` with its login HTML page.
  * **t = 4.1s:** Watchdog's HTTP GET check evaluates to `true` (received 200 OK). `consecutiveFailures` resets to 0.
  * **t = 4.1s – infinity:** `decide()` continually returns `Decision.Hold`.
* **Policy Step-by-Step:**
  1. The in-tunnel HTTP probe receives an intercepted HTTP 200 response from the captive portal.
  2. The policy marks the server as completely healthy.
  3. No failover is attempted; real user traffic (TLS/HTTPS) is dropped or rejected by the captive portal.
* **User Experience:** The VPN shows "Connected & Healthy", but no apps or websites work. Automatic failover never activates because the captive portal's spoofed response satisfies the probe.
* **Likelihood:** **High.** Standard behavior for intercepting captive portals in public venues.
* **Harm:** **Medium-High.** False-positive health state creates a silent dead-end for user traffic.

---

### 9. Worse-Candidate Latch (No QoS/Latency Awareness)

* **Timing & Sequence:**
  * **t = 0s:** User is connected to their chosen Home server (20ms latency, local country, unblocked banking/streaming).
  * **t = 20s:** A 5.1s packet stall fails one probe -> `FAILURES_BEFORE_SWAP = 1` triggers `LeaveCurrent`.
  * **t = 25s:** Watchdog picks a random responding candidate from the subscription: Candidate Z located overseas (450ms latency, datacenter IP blocked by banks and Netflix).
  * **t = 45s, 65s, 85s...:** Candidate Z returns minimal 200 OK responses to the 20-second watchdog probes without failing.
  * **t = 100s:** Direct probe to Home server suffers a single dropped packet on probe #4, resetting `consecutiveHomeRecoveries` back to 0.
  * **t = infinity:** Candidate Z never fails a probe, so `consecutiveFailures` remains 0. Home server fails to achieve 5 consecutive clean direct probes due to minor packet jitter.
* **Policy Step-by-Step:**
  1. Candidate selection selects randomly among responding nodes with no latency, bandwidth, or geolocation scoring.
  2. The policy has no concept of candidate degradation; as long as Candidate Z returns 1 byte every 20s, it is never abandoned.
  3. A single dropped packet on the direct probe resets the Home recovery counter.
* **User Experience:** The user is locked onto a high-latency, geoblocked server indefinitely. Banking apps block access and streaming fails, with no automatic mechanism to switch to a better candidate.
* **Likelihood:** **High.** Multi-node subscriptions contain nodes across diverse geographic regions with varying IP reputation.
* **Harm:** **Medium-High.** Severe quality-of-service degradation with no recovery path.

---

### 10. Multi-Node Subscription Measurement Storm & Local Socket Exhaustion

* **Timing & Sequence:**
  * **t = 0s:** User on a commercial subscription containing 150 candidate nodes. Current server drops 1 probe.
  * **t = 5.1s:** `FAILURES_BEFORE_SWAP = 1` triggers departure.
  * **t = 5.2s:** Watchdog initiates simultaneous out-of-tunnel probes against ALL 150 candidate servers.
  * **t = 5.5s:** Firing 150 concurrent TCP/TLS handshakes exhausts Android OS file descriptors/local socket limits on the cellular interface and causes radio buffer contention.
  * **t = 10.5s:** All 150 candidate probes time out or fail locally due to socket exhaustion.
  * **t = 10.6s:** Watchdog evaluates candidate pool: *"every candidate is also dead it logs and moves nothing."*
  * **t = 15.6s:** 5 seconds later (`SUSPECT_PROBE_INTERVAL_MILLIS`), watchdog probes the dead current server, fails, and fires another 150 concurrent candidate probes.
* **Policy Step-by-Step:**
  1. The policy requires measuring *all* candidate servers fresh upon departure.
  2. Large candidate lists cause self-inflicted network and resource exhaustion.
  3. When all candidates fail locally, the watchdog refuses to move, looping every 5 seconds.
* **User Experience:** The phone experiences high battery drain and CPU spikes, while remaining stuck on a dead server with no failover occurring.
* **Likelihood:** **Medium-High.** Standard subscription profiles for sing-box/Clash regularly provide 50–200 nodes.
* **Harm:** **Medium-High.** Complete denial-of-service of the failover engine combined with battery depletion.

---

### 11. NTP / Timezone Step Indefinite Failover Paralysis

* **Timing & Sequence:**
  * **t = 0s:** Tunnel moves to Candidate B. `lastSwitchTime` is recorded using wall-clock time (`System.currentTimeMillis()`).
  * **t = 30s:** Device crosses cell towers or connects to Wi-Fi; NTP sync or timezone adjustment steps the system clock back by 15 minutes (-900s).
  * **t = 40s:** Candidate B dies completely.
  * **t = 60s:** Probe fails (`consecutiveFailures = 1`).
  * **t = 60.1s:** `decide()` calculates `millisSinceLastSwitch = System.currentTimeMillis() - lastSwitchTime = -880_000ms`.
  * **t = 60.1s:** `cooledDown` (`millisSinceLastSwitch >= SWITCH_COOLDOWN_MILLIS`) evaluates to `false` (-880,000ms >= 300,000ms is false).
  * **t = 60.1s – 940s:** Policy returns `Decision.Hold` for the next 15 minutes while `millisSinceLastSwitch` slowly crawls back from negative numbers.
* **Policy Step-by-Step:**
  1. Relying on wall-clock time rather than monotonic time (`SystemClock.elapsedRealtime()`) allows clock steps to corrupt cooldown math.
  2. Negative time deltas ensure `millisSinceLastSwitch >= cooldown` fails continuously.
  3. All failover decisions are blocked until real time catches up to the skewed timestamp.
* **User Experience:** Complete connectivity loss for up to 15+ minutes with zero failover response after an automatic clock/time-zone adjustment.
* **Likelihood:** **Low-Medium.** Common during international travel, roaming, or network time sync updates.
* **Harm:** **Critical.** Extended, unrecoverable failover paralysis.

---

### 12. Subscription Refresh Mid-Flight Stale Pointer Orphan

* **Timing & Sequence:**
  * **t = 0s:** Tunnel fails over from Home (Server ID #101) to Candidate B. `hasHome = true`.
  * **t = 60s:** An automated background task refreshes the user's subscription profile. The server list is re-parsed, assigning new model instances and UUIDs to all nodes (Server #101 becomes ID #201 or object reference changes).
  * **t = 80s:** Parallel Home probing worker attempts to probe the old Home reference (pointing to a discarded object, stale IP, or deleted config).
  * **t = 80s – infinity:** Direct probes to the stale reference fail or throw exceptions. `consecutiveHomeRecoveries` never reaches `PROBES_BEFORE_FAILBACK (5)`.
* **Policy Step-by-Step:**
  1. Home server state is retained across failover.
  2. External subscription updates mutate or invalidate the retained Home node configuration.
  3. Recovery condition is never satisfied; tunnel remains on fallback server permanently.
* **User Experience:** The user is permanently stuck on a fallback server after a background subscription sync, never returning to their designated Home server.
* **Likelihood:** **Medium.** Background subscription auto-updates are standard in modern proxy clients.
* **Harm:** **Medium.** Silent permanent desynchronization from the user's preferred configuration.
