import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class Server {

    static Map<String, List<Long>> traffic    = new ConcurrentHashMap<>();
    static Map<String, List<Long>> intervals  = new ConcurrentHashMap<>();
    static Map<String, Integer>    requestCount = new ConcurrentHashMap<>();

    static Set<String>          blocked     = ConcurrentHashMap.newKeySet();
    static Map<String, Long>    blockedTime = new ConcurrentHashMap<>();
    static Map<String, Integer> attackScore = new ConcurrentHashMap<>();

    static int totalRequests = 0;

    public static void main(String[] args) throws Exception {

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);

        server.createContext("/",               new MainHandler());
        server.createContext("/admin",          new AdminHandler());
        server.createContext("/admin/unblock",  new UnblockHandler());   // NEW
        server.createContext("/api/stats",      new ApiHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("Server running on port 8080...");
    }

    // ===================== MAIN WEBSITE HANDLER =====================
    static class MainHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {

            String ip = t.getRemoteAddress().getAddress().getHostAddress()
                    .replace("0:0:0:0:0:0:0:1", "127.0.0.1");
            long now = System.currentTimeMillis();

            if (blocked.contains(ip)) {
                long unblockTime = blockedTime.getOrDefault(ip, 0L);
                if (now > unblockTime) { blocked.remove(ip); }
                else { send(t, "<h1>403 - Access Denied</h1>", 403); return; }
            }

            traffic.putIfAbsent(ip, new ArrayList<>());
            List<Long> times = traffic.get(ip);
            times.add(now);
            times.removeIf(ti -> now - ti > 10000);

            int rps = times.size();
            totalRequests++;
            requestCount.put(ip, requestCount.getOrDefault(ip, 0) + 1);
            int total = requestCount.get(ip);

            intervals.putIfAbsent(ip, new ArrayList<>());
            List<Long> intList = intervals.get(ip);

            if (times.size() > 1) {
                long last = times.get(times.size() - 1);
                long prev = times.get(times.size() - 2);
                intList.add(last - prev);
            }
            if (intList.size() > 10) intList.remove(0);

            boolean samePattern = false;
            if (intList.size() >= 7) {
                long first = intList.get(0);
                samePattern = true;
                for (long i : intList) {
                    if (Math.abs(i - first) > 10) { samePattern = false; break; }
                }
            }

            log("IP: " + ip + " | RPS: " + rps + " | Total: " + total);

            if (samePattern && rps > 25 && total > 100) {
                applyBlock(ip, now);
                log("BLOCKED: " + ip);
            }

            String path = t.getRequestURI().getPath();
            if (path.equals("/style.css"))  { sendFile(t, "web/style.css");  return; }
            if (path.equals("/script.js"))  { sendFile(t, "web/script.js");  return; }
            sendFile(t, "web/index.html");
        }
    }

    // ===================== BLOCK LOGIC =====================
    static void applyBlock(String ip, long now) throws IOException {
        attackScore.put(ip, attackScore.getOrDefault(ip, 0) + 1);
        int score = attackScore.get(ip);
        long duration = score == 1 ? 30_000 : score == 2 ? 120_000 : score == 3 ? 300_000 : 86_400_000;
        blocked.add(ip);
        blockedTime.put(ip, now + duration);
        log("ATTACK LEVEL " + score + " | BLOCKED " + ip);
    }

    // ===================== UNBLOCK HANDLER (NEW) =====================
    static class UnblockHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String query = t.getRequestURI().getQuery();
            if (query != null && query.startsWith("ip=")) {
                String ip = query.substring(3);
                blocked.remove(ip);
                blockedTime.remove(ip);
                log("UNBLOCKED: " + ip + " (Manual - Admin Panel)");
            }
            t.getResponseHeaders().add("Location", "/admin");
            t.sendResponseHeaders(302, -1);
            t.close();
        }
    }

    // ===================== ADMIN PANEL (FULLY REDESIGNED) =====================
    static class AdminHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {

            // --- Read & escape logs ---
            String rawLogs;
            try { rawLogs = Files.readString(Paths.get("logs/log.txt")); }
            catch (Exception e) { rawLogs = "No logs yet."; }

            String safeLogs = rawLogs
                .replace("\\", "\\\\")
                .replace("`",  "\\`")
                .replace("$",  "\\$");

            long now = System.currentTimeMillis();

            // --- Blocked IPs table rows ---
            StringBuilder blockedRows = new StringBuilder();
            for (String ip : blocked) {
                long rem = (blockedTime.getOrDefault(ip, 0L) - now) / 1000;
                int   sc = attackScore.getOrDefault(ip, 0);
                blockedRows
                    .append("<tr class='brow'>")
                    .append("<td><span class='iptag'>").append(ip).append("</span></td>")
                    .append("<td><span class='badge red'>BLOCKED</span></td>")
                    .append("<td class='timer'>").append(rem > 0 ? rem + "s" : "Expired").append("</td>")
                    .append("<td><span class='lvl").append(Math.min(sc, 4)).append("'>LVL ").append(sc).append("</span></td>")
                    .append("<td><a href='/admin/unblock?ip=").append(ip)
                    .append("' class='ubtn' onclick=\"return confirm('Unblock ").append(ip).append("?')\">&#x1F513; UNBLOCK</a></td>")
                    .append("</tr>\n");
            }

            // --- All IPs table rows ---
            StringBuilder allRows = new StringBuilder();
            for (Map.Entry<String, Integer> e : requestCount.entrySet()) {
                String  pip  = e.getKey();
                boolean isB  = blocked.contains(pip);
                int     sc   = attackScore.getOrDefault(pip, 0);
                int     rps  = traffic.getOrDefault(pip, new ArrayList<>()).size();
                allRows
                    .append("<tr>")
                    .append("<td><span class='iptag'>").append(pip).append("</span></td>")
                    .append("<td>").append(e.getValue()).append("</td>")
                    .append("<td>").append(rps).append("/10s</td>")
                    .append("<td>").append(isB
                        ? "<span class='badge red'>BLOCKED</span>"
                        : "<span class='badge green'>ACTIVE</span>").append("</td>")
                    .append("<td>").append(sc > 0
                        ? "<span class='lvl" + Math.min(sc,4) + "'>LVL " + sc + "</span>"
                        : "<span style='color:#475569'>-</span>").append("</td>")
                    .append("</tr>\n");
            }

            // --- Chart data ---
            List<Map.Entry<String,Integer>> sorted = new ArrayList<>(requestCount.entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());
            StringBuilder cLabels = new StringBuilder("[");
            StringBuilder cData   = new StringBuilder("[");
            StringBuilder cColors = new StringBuilder("[");
            int cnt = 0;
            for (Map.Entry<String,Integer> e : sorted) {
                if (cnt++ >= 9) break;
                cLabels.append("'").append(e.getKey()).append("',");
                cData.append(e.getValue()).append(",");
                cColors.append(blocked.contains(e.getKey())
                    ? "'rgba(255,68,68,0.85)',"
                    : "'rgba(0,245,255,0.75)',");
            }
            cLabels.append("]"); cData.append("]"); cColors.append("]");

            // --- Map IP data ---
            StringBuilder mapIps = new StringBuilder("[");
            for (String ip : traffic.keySet()) {
                mapIps.append("{ip:'").append(ip)
                      .append("',blocked:").append(blocked.contains(ip))
                      .append(",score:").append(attackScore.getOrDefault(ip, 0))
                      .append("},");
            }
            mapIps.append("]");

            // --- Derived stats ---
            String  topIp    = sorted.isEmpty() ? "N/A" : sorted.get(0).getKey();
            boolean underAtk = !blocked.isEmpty();
            String  atkBanner = underAtk
                ? "<div class='alert-bar'>&#x1F6A8; ATTACK DETECTED &mdash; " + blocked.size()
                  + " IP(S) BLOCKED &mdash; LATEST: " + blocked.iterator().next() + "</div>"
                : "";

            // --- Attack level stats for dashboard panel ---
            String lvlStats = buildLevelStats();

            // ===================== HTML =====================
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html lang='en'><head>")
                .append("<meta charset='UTF-8'>")
                .append("<meta name='viewport' content='width=device-width,initial-scale=1'>")
                .append("<title>DDoS Defense Panel</title>")
                .append("<link rel='stylesheet' href='https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'/>")
                .append("<script src='https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'></script>")
                .append("<script src='https://cdnjs.cloudflare.com/ajax/libs/Chart.js/4.4.0/chart.umd.min.js'></script>")
                .append("<style>")

                // ── Reset & base ──
                .append(":root{")
                .append("--c:#00f5ff;--g:#00ff88;--r:#ff4444;--o:#ff8800;--y:#ffd700;")
                .append("--bg:#030712;--bg2:#070d1a;--bg3:#0a1020;--bd:#1e3a5f;}")
                .append("*{margin:0;padding:0;box-sizing:border-box;}")
                .append("body{background:var(--bg);color:#cbd5e1;font-family:Consolas,'Courier New',monospace;overflow:hidden;height:100vh;}")

                // ── Animations ──
                .append("@keyframes blink{0%,100%{opacity:1}50%{opacity:0}}")
                .append("@keyframes glow{0%,100%{box-shadow:0 0 6px var(--c)}50%{box-shadow:0 0 20px var(--c),0 0 40px var(--c)}}")
                .append("@keyframes slideX{0%{background-position:0%}100%{background-position:200%}}")
                .append("@keyframes fadeIn{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:translateY(0)}}")
                .append("@keyframes spin{to{transform:rotate(360deg)}}")

                // ── Alert banner ──
                .append(".alert-bar{background:linear-gradient(90deg,#7f1d1d,#dc2626,#7f1d1d);background-size:200%;")
                .append("animation:slideX 2s linear infinite;color:#fff;text-align:center;")
                .append("padding:7px 12px;font-size:12px;font-weight:700;letter-spacing:2px;")
                .append("border-bottom:2px solid var(--r);}")

                // ── Header ──
                .append("header{display:flex;align-items:center;justify-content:space-between;")
                .append("padding:10px 20px;background:var(--bg2);border-bottom:1px solid var(--bd);height:46px;}")
                .append(".logo{font-size:17px;font-weight:700;color:var(--c);text-shadow:0 0 12px var(--c);letter-spacing:3px;}")
                .append(".hstatus{display:flex;align-items:center;gap:10px;}")
                .append(".pill{padding:3px 10px;border-radius:20px;font-size:11px;border:1px solid;}")
                .append(".pill-ok{border-color:var(--g);color:var(--g);}")
                .append(".pill-atk{border-color:var(--r);color:var(--r);animation:blink 1s infinite;}")
                .append("#clock{color:#64748b;font-size:11px;min-width:90px;text-align:right;}")

                // ── Layout ──
                .append(".layout{display:flex;height:calc(100vh - 46px);}")

                // ── Sidebar ──
                .append(".sidebar{width:195px;background:var(--bg2);border-right:1px solid var(--bd);")
                .append("display:flex;flex-direction:column;padding:8px 0;flex-shrink:0;}")
                .append(".nav{display:flex;align-items:center;gap:9px;padding:11px 15px;cursor:pointer;")
                .append("border-left:3px solid transparent;transition:all .2s;font-size:12px;color:#475569;}")
                .append(".nav:hover{background:rgba(0,245,255,.07);color:var(--c);border-left-color:var(--c);}")
                .append(".nav.active{background:rgba(0,245,255,.12);color:var(--c);border-left-color:var(--c);}")
                .append(".nav .ni{font-size:15px;}")
                .append(".ndiv{height:1px;background:var(--bd);margin:6px 14px;}")
                .append(".sidebar-footer{margin-top:auto;padding:10px 15px;font-size:10px;color:#334155;border-top:1px solid var(--bd);}")

                // ── Main content ──
                .append(".main{flex:1;overflow-y:auto;padding:18px;background:var(--bg);}")
                .append(".sec{display:none;animation:fadeIn .25s ease;}")
                .append(".sec.active{display:block;}")

                // ── Stat cards ──
                .append(".cards{display:grid;grid-template-columns:repeat(4,1fr);gap:14px;margin-bottom:18px;}")
                .append(".card{background:var(--bg2);border:1px solid var(--bd);border-radius:10px;padding:15px;")
                .append("position:relative;overflow:hidden;transition:transform .2s,border-color .2s;}")
                .append(".card:hover{transform:translateY(-2px);}")
                .append(".card::after{content:'';position:absolute;top:0;left:0;right:0;height:2px;}")
                .append(".cc::after{background:var(--c);box-shadow:0 0 8px var(--c);}")
                .append(".cg::after{background:var(--g);box-shadow:0 0 8px var(--g);}")
                .append(".cr::after{background:var(--r);box-shadow:0 0 8px var(--r);}")
                .append(".co::after{background:var(--o);box-shadow:0 0 8px var(--o);}")
                .append(".clabel{font-size:10px;color:#475569;letter-spacing:2px;text-transform:uppercase;margin-bottom:7px;}")
                .append(".cval{font-size:26px;font-weight:700;}")
                .append(".cc .cval{color:var(--c);text-shadow:0 0 10px rgba(0,245,255,.4);}")
                .append(".cg .cval{color:var(--g);text-shadow:0 0 10px rgba(0,255,136,.4);}")
                .append(".cr .cval{color:var(--r);text-shadow:0 0 10px rgba(255,68,68,.4);}")
                .append(".co .cval{color:var(--o);}")
                .append(".csub{font-size:10px;color:#475569;margin-top:3px;}")

                // ── Row grid ──
                .append(".row2{display:grid;grid-template-columns:3fr 2fr;gap:14px;margin-bottom:18px;}")
                .append(".panel{background:var(--bg2);border:1px solid var(--bd);border-radius:10px;padding:15px;}")
                .append(".ptitle{font-size:11px;letter-spacing:2px;color:var(--c);text-transform:uppercase;")
                .append("margin-bottom:12px;padding-bottom:8px;border-bottom:1px solid var(--bd);")
                .append("display:flex;align-items:center;gap:8px;}")

                // ── Tables ──
                .append("table{width:100%;border-collapse:collapse;font-size:12px;}")
                .append("th{padding:8px 11px;text-align:left;color:var(--c);font-size:10px;letter-spacing:1px;")
                .append("border-bottom:1px solid var(--bd);white-space:nowrap;}")
                .append("td{padding:8px 11px;border-bottom:1px solid rgba(30,58,95,.4);}")
                .append("tr:hover td{background:rgba(0,245,255,.025);}")
                .append(".brow td{background:rgba(255,68,68,.04);}")
                .append(".brow:hover td{background:rgba(255,68,68,.08) !important;}")

                // ── Badges / tags ──
                .append(".badge{padding:2px 8px;border-radius:4px;font-size:10px;font-weight:700;}")
                .append(".badge.red{background:rgba(255,68,68,.15);color:var(--r);border:1px solid rgba(255,68,68,.4);}")
                .append(".badge.green{background:rgba(0,255,136,.12);color:var(--g);border:1px solid rgba(0,255,136,.4);}")
                .append(".iptag{background:rgba(0,245,255,.08);color:var(--c);padding:2px 8px;")
                .append("border-radius:4px;font-size:11px;border:1px solid rgba(0,245,255,.25);}")

                // ── Attack levels ──
                .append(".lvl1{color:var(--y);font-weight:700;}")
                .append(".lvl2{color:var(--o);font-weight:700;}")
                .append(".lvl3{color:var(--r);font-weight:700;}")
                .append(".lvl4{color:#ff2222;font-weight:700;text-shadow:0 0 8px red;animation:blink .6s infinite;}")

                // ── Unblock button ──
                .append(".ubtn{background:rgba(0,255,136,.12);color:var(--g);border:1px solid rgba(0,255,136,.4);")
                .append("padding:4px 11px;border-radius:4px;cursor:pointer;font-size:11px;")
                .append("text-decoration:none;transition:all .2s;font-family:Consolas,monospace;}")
                .append(".ubtn:hover{background:var(--g);color:#030712;}")

                // ── Timer ──
                .append(".timer{color:var(--o);font-variant-numeric:tabular-nums;font-size:12px;}")

                // ── Map ──
                .append("#mapLeaflet{height:420px;border-radius:8px;border:1px solid var(--bd);}")
                .append(".leaflet-container{background:#050d1a !important;}")
                .append(".leaflet-popup-content-wrapper{background:var(--bg2) !important;color:#e2e8f0 !important;")
                .append("border:1px solid var(--bd) !important;font-family:Consolas,monospace !important;font-size:12px !important;}")
                .append(".leaflet-popup-tip{background:var(--bg2) !important;}")
                .append(".map-legend{display:flex;gap:16px;font-size:11px;margin-bottom:10px;color:#94a3b8;}")
                .append(".map-legend span{display:flex;align-items:center;gap:5px;}")

                // ── Logs ──
                .append(".lctrl{display:flex;gap:8px;margin-bottom:10px;}")
                .append(".lsearch{flex:1;background:var(--bg3);border:1px solid var(--bd);color:#e2e8f0;")
                .append("padding:7px 12px;border-radius:6px;font-family:Consolas,monospace;font-size:12px;outline:none;}")
                .append(".lsearch:focus{border-color:var(--c);}")
                .append(".lbtn{background:var(--bg3);border:1px solid var(--bd);color:var(--c);")
                .append("padding:7px 13px;border-radius:6px;cursor:pointer;font-family:Consolas,monospace;")
                .append("font-size:11px;transition:all .2s;white-space:nowrap;}")
                .append(".lbtn:hover{background:rgba(0,245,255,.1);border-color:var(--c);}")
                .append("#logBox{background:var(--bg3);border:1px solid var(--bd);border-radius:8px;")
                .append("height:490px;overflow-y:auto;padding:11px;font-size:11.5px;line-height:1.8;}")
                .append(".ln{color:#334155;}")
                .append(".lb{color:var(--r);font-weight:700;}")
                .append(".la{color:var(--o);font-weight:700;}")
                .append(".lu{color:var(--g);font-weight:700;}")
                .append(".lh{color:#fb923c;}")
                .append(".lt{color:#1e3a5f;}")
                .append(".lip{color:var(--c);}")
                .append(".lrps{color:var(--o);}")
                .append(".log-empty{text-align:center;padding:60px;color:#334155;font-size:14px;}")

                // ── Progress bars ──
                .append(".pbar-wrap{margin-bottom:12px;}")
                .append(".pbar-top{display:flex;justify-content:space-between;margin-bottom:4px;font-size:11px;}")
                .append(".pbar-bg{background:rgba(255,255,255,.05);border-radius:4px;height:5px;overflow:hidden;}")
                .append(".pbar-fill{height:100%;border-radius:4px;transition:width .6s ease;}")

                // ── Scrollbar ──
                .append("::-webkit-scrollbar{width:5px;height:5px;}")
                .append("::-webkit-scrollbar-track{background:var(--bg);}")
                .append("::-webkit-scrollbar-thumb{background:#1e3a5f;border-radius:3px;}")
                .append("::-webkit-scrollbar-thumb:hover{background:#2d5080;}")

                // ── Leaflet zoom override ──
                .append(".leaflet-bar a{background:var(--bg2) !important;color:var(--c) !important;")
                .append("border-color:var(--bd) !important;}")

                .append("</style></head><body>")

                // ── Alert banner ──
                .append(atkBanner)

                // ── Header ──
                .append("<header>")
                .append("<div class='logo'>&#x1F6E1; DDOS DEFENSE PANEL</div>")
                .append("<div class='hstatus'>")
                .append(underAtk
                    ? "<span class='pill pill-atk'>&#x26A0; UNDER ATTACK</span>"
                    : "<span class='pill pill-ok'>&#x2705; SYSTEM SECURE</span>")
                .append("<span class='pill pill-ok'>&#x1F4E1; PORT :8080</span>")
                .append("</div>")
                .append("<div id='clock'></div>")
                .append("</header>")

                // ── Layout wrapper ──
                .append("<div class='layout'>")

                // ── Sidebar ──
                .append("<div class='sidebar'>")
                .append("<div class='nav active' onclick=\"nav('dash',this)\">")
                .append("<span class='ni'>&#x1F4CA;</span> DASHBOARD</div>")
                .append("<div class='nav' onclick=\"nav('ips',this)\">")
                .append("<span class='ni'>&#x1F310;</span> ALL IPS</div>")
                .append("<div class='ndiv'></div>")
                .append("<div class='nav' onclick=\"nav('attacks',this)\">")
                .append("<span class='ni'>&#x1F6A8;</span> ATTACKS")
                .append(blocked.isEmpty() ? "" : " <span style='background:var(--r);color:white;font-size:10px;padding:1px 6px;border-radius:10px;'>" + blocked.size() + "</span>")
                .append("</div>")
                .append("<div class='nav' onclick=\"nav('mapSec',this)\">")
                .append("<span class='ni'>&#x1F5FA;</span> LIVE MAP</div>")
                .append("<div class='ndiv'></div>")
                .append("<div class='nav' onclick=\"nav('logs',this)\">")
                .append("<span class='ni'>&#x1F4DC;</span> LOGS</div>")
                .append("<div class='sidebar-footer'>v2.0 &bull; AUTO-REFRESH 10s</div>")
                .append("</div>")

                // ── Main area ──
                .append("<div class='main'>")

                // ════════ DASHBOARD ════════
                .append("<div id='dash' class='sec active'>")
                .append("<div class='cards'>")
                .append("<div class='card cc'><div class='clabel'>TOTAL REQUESTS</div>")
                .append("<div class='cval'>").append(totalRequests).append("</div>")
                .append("<div class='csub'>All-time server hits</div></div>")

                .append("<div class='card cg'><div class='clabel'>ACTIVE IPS</div>")
                .append("<div class='cval'>").append(traffic.size()).append("</div>")
                .append("<div class='csub'>Unique visitors tracked</div></div>")

                .append("<div class='card cr'><div class='clabel'>BLOCKED IPS</div>")
                .append("<div class='cval'>").append(blocked.size()).append("</div>")
                .append("<div class='csub'>Currently blocked</div></div>")

                .append("<div class='card co'><div class='clabel'>TOP REQUESTER</div>")
                .append("<div class='cval' style='font-size:13px;margin-top:6px;'>").append(topIp).append("</div>")
                .append("<div class='csub'>Highest request count</div></div>")
                .append("</div>") // .cards

                .append("<div class='row2'>")

                // Chart panel
                .append("<div class='panel'>")
                .append("<div class='ptitle'>&#x1F4CA; TOP IPS BY REQUEST COUNT</div>")
                .append("<canvas id='ipChart' height='110'></canvas>")
                .append("</div>")

                // Attack level panel
                .append("<div class='panel'>")
                .append("<div class='ptitle'>&#x1F6E1; ATTACK LEVEL BREAKDOWN</div>")
                .append(lvlStats)
                .append("</div>")

                .append("</div>") // .row2
                .append("</div>") // #dash

                // ════════ ALL IPS ════════
                .append("<div id='ips' class='sec'>")
                .append("<div class='panel'>")
                .append("<div class='ptitle'>&#x1F310; ALL TRACKED IPS")
                .append(" <span style='color:#475569;font-size:10px;margin-left:5px;'>(").append(requestCount.size()).append(" total)</span></div>")
                .append(requestCount.isEmpty()
                    ? "<div class='log-empty'>No traffic recorded yet.</div>"
                    : "<table><tr><th>IP ADDRESS</th><th>TOTAL REQS</th><th>CURRENT RPS</th><th>STATUS</th><th>ATK LEVEL</th></tr>"
                      + allRows + "</table>")
                .append("</div></div>")

                // ════════ ATTACKS ════════
                .append("<div id='attacks' class='sec'>")
                .append("<div class='panel'>")
                .append("<div class='ptitle'>&#x1F6A8; BLOCKED IPS")
                .append(blocked.isEmpty()
                    ? ""
                    : " <span style='color:var(--r);font-size:10px;animation:blink 1s infinite;'>&#x25CF; LIVE (" + blocked.size() + ")</span>")
                .append("</div>")
                .append(blocked.isEmpty()
                    ? "<div class='log-empty'>&#x2705; No active blocks &mdash; system is secure.</div>"
                    : "<table><tr><th>IP ADDRESS</th><th>STATUS</th><th>TIME LEFT</th><th>LEVEL</th><th>ACTION</th></tr>"
                      + blockedRows + "</table>")
                .append("</div></div>")

                // ════════ MAP ════════
                .append("<div id='mapSec' class='sec'>")
                .append("<div class='panel'>")
                .append("<div class='ptitle'>&#x1F5FA; LIVE TRAFFIC MAP &mdash; INDIA FOCUS</div>")
                .append("<div class='map-legend'>")
                .append("<span><span style='width:10px;height:10px;border-radius:50%;background:var(--g);display:inline-block;'></span> Active IP</span>")
                .append("<span><span style='width:10px;height:10px;border-radius:50%;background:var(--r);display:inline-block;'></span> Blocked IP</span>")
                .append("<span style='color:#334155;font-size:10px;'>* Positions are approximate (India bounds). Integrate a GeoIP API for exact coords.</span>")
                .append("</div>")
                .append("<div id='mapLeaflet'></div>")
                .append("</div></div>")

                // ════════ LOGS ════════
                .append("<div id='logs' class='sec'>")
                .append("<div class='panel'>")
                .append("<div class='ptitle'>&#x1F4DC; SYSTEM LOGS</div>")
                .append("<div class='lctrl'>")
                .append("<input class='lsearch' id='lsearch' placeholder='&#x1F50D; Filter logs (ip, BLOCKED, RPS...)' oninput='filterLogs()'>")
                .append("<button class='lbtn' onclick='location.reload()'>&#x21BB; REFRESH</button>")
                .append("<button class='lbtn' id='asBtn' onclick='toggleAS()'>&#x23EC; AUTO-SCROLL ON</button>")
                .append("<button class='lbtn' onclick='clearFilter()'>&#x2715; CLEAR</button>")
                .append("</div>")
                .append("<div id='logBox'></div>")
                .append("</div></div>")

                .append("</div></div>") // .main .layout

                // ════════ SCRIPTS ════════
                .append("<script>")

                // Clock
                .append("function updateClock(){")
                .append("let d=new Date();")
                .append("document.getElementById('clock').textContent=")
                .append("d.toLocaleTimeString('en-IN',{hour12:false})+' IST';}")
                .append("setInterval(updateClock,1000);updateClock();")

                // Navigation
                .append("let mapInit=false;")
                .append("function nav(id,el){")
                .append("document.querySelectorAll('.sec').forEach(s=>s.classList.remove('active'));")
                .append("document.querySelectorAll('.nav').forEach(n=>n.classList.remove('active'));")
                .append("document.getElementById(id).classList.add('active');")
                .append("el.classList.add('active');")
                .append("if(id==='mapSec'&&!mapInit){setTimeout(initMap,100);}") 
                .append("if(id==='logs'){setTimeout(renderLogs,50);}}")

                // Chart
                .append("let cLabels=").append(cLabels).append(";")
                .append("let cData=").append(cData).append(";")
                .append("let cColors=").append(cColors).append(";")
                .append("if(document.getElementById('ipChart')){")
                .append("new Chart(document.getElementById('ipChart'),{type:'bar',")
                .append("data:{labels:cLabels,datasets:[{label:'Requests',data:cData,")
                .append("backgroundColor:cColors,borderColor:cColors,borderWidth:1,borderRadius:5,borderSkipped:false}]},")
                .append("options:{responsive:true,animation:{duration:800},plugins:{legend:{display:false},")
                .append("tooltip:{backgroundColor:'#0a1020',borderColor:'#1e3a5f',borderWidth:1,")
                .append("titleColor:'#00f5ff',bodyColor:'#cbd5e1',callbacks:{label:(c)=>' Requests: '+c.raw}}},")
                .append("scales:{x:{ticks:{color:'#475569',font:{family:'Consolas',size:10}},")
                .append("grid:{color:'rgba(30,58,95,.4)'}},")
                .append("y:{ticks:{color:'#475569',font:{family:'Consolas',size:10}},")
                .append("grid:{color:'rgba(30,58,95,.4)'}}}}}); }")

                // Logs system
                .append("let rawLogs=`").append(safeLogs).append("`;")
                .append("let autoS=true;")
                .append("function toggleAS(){autoS=!autoS;")
                .append("document.getElementById('asBtn').textContent='⬇ AUTO-SCROLL '+(autoS?'ON':'OFF');}")
                .append("function clearFilter(){document.getElementById('lsearch').value='';renderLogs();}")
                .append("function colorLog(line){")
                .append("if(!line.trim())return '';")
                .append("let cls='ln';")
                .append("if(line.includes('UNBLOCK'))cls='lu';")
                .append("else if(line.includes('ATTACK LEVEL'))cls='la';")
                .append("else if(line.includes('BLOCKED'))cls='lb';")
                .append("else{let m=line.match(/RPS:\\s*(\\d+)/);if(m&&parseInt(m[1])>10)cls='lh';}")
                .append("let c=line")
                .append(".replace(/(\\[\\d+:\\d+:\\d+\\])/g,'<span class=\"lt\">$1</span>')")
                .append(".replace(/\\b(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})\\b/g,'<span class=\"lip\">$1</span>')")
                .append(".replace(/(RPS:\\s*\\d+)/g,'<span class=\"lrps\">$1</span>');")
                .append("return '<div class=\"'+cls+'\">'+c+'</div>';}")
                .append("function renderLogs(){")
                .append("let box=document.getElementById('logBox');if(!box)return;")
                .append("let f=document.getElementById('lsearch').value.toLowerCase();")
                .append("let lines=rawLogs.split('\\n').filter(l=>l.trim());")
                .append("if(f)lines=lines.filter(l=>l.toLowerCase().includes(f));")
                .append("if(!lines.length){box.innerHTML='<div class=\"log-empty\">No matching logs found.</div>';return;}")
                .append("box.innerHTML=lines.map(colorLog).join('');")
                .append("if(autoS)box.scrollTop=box.scrollHeight;}")
                .append("function filterLogs(){renderLogs();}")

                // Map
                .append("let mapIps=").append(mapIps).append(";")
                .append("function initMap(){")
                .append("if(mapInit)return;mapInit=true;")
                .append("let lmap=L.map('mapLeaflet').setView([22.5,82.5],5);")
                // CartoDB dark tiles – show country + state boundaries naturally
                .append("L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png',{")
                .append("attribution:'&copy; OpenStreetMap contributors &copy; CARTO',")
                .append("subdomains:'abcd',maxZoom:18}).addTo(lmap);")
                // Try to load India states GeoJSON overlay
                .append("fetch('https://raw.githubusercontent.com/geohacker/india/master/india.geo.json')")
                .append(".then(r=>r.json()).then(data=>{")
                .append("L.geoJSON(data,{style:{")
                .append("color:'#00f5ff',weight:0.8,opacity:0.5,")
                .append("fillColor:'#0a1929',fillOpacity:0.25}}).addTo(lmap);")
                .append("}).catch(()=>{});")
                // Place IP dots
                .append("mapIps.forEach(ip=>{")
                .append("if(!ip||!ip.ip)return;")
                .append("let lat=8+Math.random()*29;")    // India lat  8–37
                .append("let lng=68+Math.random()*29;")   // India lng 68–97
                .append("let col=ip.blocked?'#ff4444':'#00ff88';")
                .append("let rad=ip.blocked?11:7;")
                .append("let m=L.circleMarker([lat,lng],{")
                .append("color:col,fillColor:col,fillOpacity:.85,radius:rad,weight:2});")
                .append("m.bindPopup('<b style=\"color:'+col+'\">'+ip.ip+'</b>'")
                .append("+'<br>Status: '+(ip.blocked?'&#x1F534; BLOCKED':'&#x1F7E2; ACTIVE')")
                .append("+'<br>Attack Score: '+ip.score);")
                .append("m.addTo(lmap);")
                .append("if(ip.blocked){let on=true;setInterval(()=>{")
                .append("m.setStyle({fillOpacity:(on=!on)?0.85:0.15});},600);}});}")

                // Auto-refresh stats every 10s
                .append("setInterval(()=>{")
                .append("fetch('/api/stats').then(r=>r.json()).then(d=>{")
                .append("}).catch(()=>{});},10000);")

                // Countdown timers in attack table (update every second)
                .append("setInterval(()=>{")
                .append("document.querySelectorAll('.timer').forEach(el=>{")
                .append("let v=parseInt(el.textContent);")
                .append("if(!isNaN(v)&&v>0)el.textContent=(v-1)+'s';")
                .append("else if(!isNaN(v))el.textContent='Expired';});},1000);")

                .append("</script></body></html>");

            send(t, html.toString(), 200);
        }
    }

    // Helper: build attack level progress bars
    static String buildLevelStats() {
        String[] labels = {"LVL 1 — 30s ban", "LVL 2 — 2 min ban", "LVL 3 — 5 min ban", "LVL 4 — 24h ban"};
        String[] colors = {"#ffd700",          "#ff8800",            "#ff4444",            "#ff0000"};
        long total = attackScore.values().stream().mapToLong(Integer::longValue).count();

        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            final int lvl = i;
            long count = attackScore.values().stream().filter(s -> s == lvl).count();
            long pct   = total == 0 ? 0 : Math.min(count * 100 / (total == 0 ? 1 : total), 100);
            sb.append("<div class='pbar-wrap'>")
              .append("<div class='pbar-top'>")
              .append("<span style='color:").append(colors[i-1]).append(";font-size:11px;'>").append(labels[i-1]).append("</span>")
              .append("<span style='color:#94a3b8;font-size:11px;'>").append(count).append(" IPs</span>")
              .append("</div>")
              .append("<div class='pbar-bg'><div class='pbar-fill' style='width:").append(pct)
              .append("%;background:").append(colors[i-1]).append(";'></div></div>")
              .append("</div>");
        }
        return sb.toString();
    }

    // ===================== API HANDLER (enhanced) =====================
    static class ApiHandler implements HttpHandler {
        public void handle(HttpExchange t) throws IOException {
            String topIp = requestCount.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse("N/A");
            String json = "{" +
                "\"requests\":" + totalRequests + "," +
                "\"blocked\":"  + blocked.size() + "," +
                "\"activeIps\":" + traffic.size() + "," +
                "\"topIp\":\"" + topIp + "\"" +
                "}";
            byte[] data = json.getBytes("UTF-8");
            t.getResponseHeaders().add("Content-Type", "application/json");
            t.sendResponseHeaders(200, data.length);
            t.getResponseBody().write(data);
            t.getResponseBody().close();
        }
    }

    // ===================== HELPERS =====================
    static void sendFile(HttpExchange t, String file) throws IOException {
        byte[] data = Files.readAllBytes(Paths.get(file));
        t.sendResponseHeaders(200, data.length);
        t.getResponseBody().write(data);
        t.getResponseBody().close();
    }

    static void send(HttpExchange t, String msg, int code) throws IOException {
        byte[] res = msg.getBytes("UTF-8");
        t.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
        t.sendResponseHeaders(code, res.length);
        t.getResponseBody().write(res);
        t.getResponseBody().close();
    }

    static void log(String msg) throws IOException {
        String time = new java.text.SimpleDateFormat("HH:mm:ss")
                .format(new java.util.Date());
        String finalMsg = "[" + time + "] " + msg;
        Files.write(Paths.get("logs/log.txt"), (finalMsg + "\n").getBytes(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
