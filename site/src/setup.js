// Installs FlipFix from the browser: WebUSB to the phone's USB debugging (ADB), then the same steps as the
// README's manual install: install the APK, grant WRITE_SECURE_SETTINGS, allow the notification listener.
// Nothing leaves the browser except what goes down the USB cable.
import { Adb, AdbDaemonTransport } from "@yume-chan/adb";
import { AdbDaemonWebUsbDevice, AdbDaemonWebUsbDeviceManager } from "@yume-chan/adb-daemon-webusb";
import AdbWebCredentialStore from "@yume-chan/adb-credential-web";
import { PackageManager } from "@yume-chan/android-bin";

const PACKAGE = "io.github.avengerdeveloper.flipfix";
const LISTENER = `${PACKAGE}/${PACKAGE}.FlipNotificationListener`;
const APK_URL = "FlipFix.apk";
const MIN_SDK = 33;
const ONE_UI_9 = 90000; // ro.build.version.oneui: 90000 is One UI 9.0
const CHUNK = 64 * 1024;
/** A device list closed sooner than this was never shown. */
const NO_LIST_MS = 300;

const $ = (id) => document.getElementById(id);
const connectButton = $("connect");
const panel = $("panel");
const statusBox = $("status");
const choiceBox = $("choice");

/** The release this page serves, from release.json (written when the page is published). */
let release = null;

function versionCodeOf(tag) {
  // Same rule as the release build: v1.2.3 -> (1*10000 + 2*100 + 3) * 100.
  const m = /^v?(\d+)\.(\d+)\.(\d+)/.exec(tag ?? "");
  return m ? (Number(m[1]) * 10000 + Number(m[2]) * 100 + Number(m[3])) * 100 : null;
}

async function loadRelease() {
  try {
    const response = await fetch("release.json", { cache: "no-cache" });
    if (!response.ok) return;
    release = await response.json();
    const size = release.size ? ` · ${(release.size / 1048576).toFixed(1)} MB` : "";
    $("version").textContent = `Version ${release.tag.replace(/^v/, "")}${size}`;
  } catch {
    // The page still works; it just can't show the version or skip an install that's already done.
  }
}

// ----- Progress panel -----

function task(name, state, detail = "") {
  const row = panel.querySelector(`[data-task="${name}"]`);
  row.dataset.state = state;
  row.querySelector(".detail").textContent = detail;
}

function resetTasks() {
  for (const row of panel.querySelectorAll("[data-task]")) {
    delete row.dataset.state;
    row.querySelector(".detail").textContent = "";
  }
}

/** Shows a message under the tasks. Parts are strings or [tag, text] pairs, e.g. ["code", "adb"]. */
function say(kind, ...parts) {
  const p = document.createElement("p");
  if (kind) p.className = kind;
  for (const part of parts) {
    if (typeof part === "string") p.append(part);
    else {
      const el = document.createElement(part[0]);
      el.textContent = part[1];
      p.append(el);
    }
  }
  statusBox.append(p);
}

function clearStatus() {
  statusBox.replaceChildren();
  choiceBox.replaceChildren();
  choiceBox.hidden = true;
}

/** Offers buttons and waits for one. Returns the chosen value. */
function ask(options) {
  return new Promise((resolve) => {
    choiceBox.replaceChildren();
    choiceBox.hidden = false;
    options.forEach(([label, value, primary], i) => {
      const b = document.createElement("button");
      b.type = "button";
      b.className = `button small ${primary ? "primary" : "quiet"}`;
      b.textContent = label;
      b.addEventListener("click", () => {
        choiceBox.hidden = true;
        choiceBox.replaceChildren();
        resolve(value);
      });
      choiceBox.append(b);
      if (i === 0) b.focus();
    });
  });
}

class Stop extends Error {}

// ----- Phone -----

/** Runs a shell command. Returns its output and exit code (null when the phone can't report one). */
async function shell(adb, command) {
  const shellProtocol = adb.subprocess.shellProtocol;
  if (shellProtocol) {
    const r = await shellProtocol.spawnWaitText(command);
    return { out: `${r.stdout}${r.stderr}`.trim(), code: r.exitCode };
  }
  const out = await adb.subprocess.noneProtocol.spawnWaitText(command);
  return { out: out.trim(), code: null };
}

async function connect() {
  const manager = AdbDaemonWebUsbDeviceManager.BROWSER;
  const asked = performance.now();
  const device = await manager.requestDevice();
  if (!device) {
    // Nobody closes a device list this fast: the browser never showed one (an app's built-in browser, say).
    if (performance.now() - asked < NO_LIST_MS) {
      throw new Stop("Your browser didn't show a list of devices, so it can't reach your phone. Open this page in Chrome or Edge on your computer.");
    }
    throw new Stop("No phone was picked. If your phone wasn't in the list, see “If something goes wrong” below.");
  }
  console.info("[FlipFix setup] picked", device.name);
  let connection;
  try {
    connection = await device.connect();
  } catch (e) {
    console.warn("[FlipFix setup] couldn't open the phone", e);
    if (e instanceof AdbDaemonWebUsbDevice.DeviceBusyError) {
      throw new Stop("Your phone is busy: another program on this computer is using its USB debugging (adb, Android Studio or scrcpy), or another tab is. Close it, unplug the phone and plug it back in, then try again.", { cause: e });
    }
    throw new Stop(`Your browser couldn't open your phone's USB debugging connection. Unplug the phone, plug it back in and try again.`, { cause: e });
  }
  console.info("[FlipFix setup] opened the phone's USB debugging interface; waiting for Allow");
  task("connect", "active", "Tap Allow on your phone…");
  // The question shows on the phone's main screen; if it hasn't been answered in a while, say where to look.
  const hint = setTimeout(() => task("connect", "active",
    "Still waiting. Open and unlock your phone, and tap Allow on “Allow USB debugging?”. No question? See “If something goes wrong” below."), 15000);
  try {
    const credentialStore = new AdbWebCredentialStore("FlipFix setup page");
    const transport = await AdbDaemonTransport.authenticate({ serial: device.serial, connection, credentialStore });
    return new Adb(transport);
  } finally {
    clearTimeout(hint);
  }
}

async function check(adb) {
  const [manufacturer, model, sdkText, oneUiText] = await Promise.all([
    adb.getProp("ro.product.manufacturer"),
    adb.getProp("ro.product.model"),
    adb.getProp("ro.build.version.sdk"),
    adb.getProp("ro.build.version.oneui"),
  ]);
  const sdk = Number(sdkText);
  const oneUi = Number(oneUiText);
  const oneUiName = oneUi ? `One UI ${Math.floor(oneUi / 10000)}.${Math.floor(oneUi / 100) % 100}` : "no One UI";
  const label = `${model || "Unknown phone"}, ${oneUiName}`;

  if (sdk && sdk < MIN_SDK) {
    task("check", "fail", label);
    throw new Stop("This phone's Android version is too old for FlipFix.");
  }
  // Every Z Flip is an SM-F7xx; carrier models in some countries have other names, so this only warns.
  const isFlip = /samsung/i.test(manufacturer) && /^SM-F7/i.test(model);
  const warnings = [];
  if (!isFlip) warnings.push(`This doesn't look like a Galaxy Z Flip (${label}). FlipFix only does something on a Z Flip's cover screen.`);
  else if (oneUi && oneUi < ONE_UI_9) warnings.push(`FlipFix is made for One UI 9, and this phone has ${oneUiName}. Earlier versions keep the cover video playing anyway.`);
  if (warnings.length) {
    task("check", "warn", label);
    say("", warnings.join(" "));
    const go = await ask([["Stop", false, true], ["Install anyway", true, false]]);
    clearStatus();
    if (!go) throw new Stop("Stopped. Nothing was installed.");
  }
  task("check", "done", label);
}

/** The version code of the FlipFix on the phone, or null if none. */
async function installedVersion(adb) {
  const { out } = await shell(adb, `pm list packages --show-versioncode ${PACKAGE}`);
  for (const line of out.split("\n")) {
    const m = /^package:(\S+) versionCode:(\d+)/.exec(line.trim());
    if (m && m[1] === PACKAGE) return Number(m[2]);
  }
  return null;
}

async function downloadApk() {
  const response = await fetch(APK_URL, { cache: "no-cache" });
  if (!response.ok) throw new Error(`Couldn't download FlipFix (${response.status}).`);
  return new Uint8Array(await response.arrayBuffer());
}

/** The APK as a stream, in small pieces, reporting how much has gone to the phone. */
function apkStream(bytes, onProgress) {
  let offset = 0;
  return new ReadableStream({
    pull(controller) {
      if (offset >= bytes.length) {
        controller.close();
        return;
      }
      const end = Math.min(offset + CHUNK, bytes.length);
      controller.enqueue(bytes.subarray(offset, end));
      offset = end;
      onProgress(offset / bytes.length);
    },
  });
}

async function install(adb) {
  const wanted = versionCodeOf(release?.tag);
  const installed = await installedVersion(adb);
  if (installed !== null && wanted !== null && installed >= wanted) {
    task("install", "done", installed === wanted ? "Already installed" : "A newer FlipFix is already installed; kept it");
    return;
  }
  task("install", "active", "Downloading…");
  const bytes = await downloadApk();
  const pm = new PackageManager(adb);
  const push = () => pm.installStream(bytes.length, apkStream(bytes, (f) => {
    task("install", "active", f < 1 ? `Sending to your phone… ${Math.round(f * 100)}%` : "Finishing on your phone…");
  }));
  try {
    await push();
  } catch (e) {
    const message = String(e?.message ?? e);
    if (!/INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match/i.test(message)) throw e;
    task("install", "warn", "A different FlipFix is installed");
    say("", "Your phone has a copy of FlipFix signed with another key, for example one you built yourself. To install this one, that copy has to be removed first, which clears its settings.");
    const remove = await ask([["Remove it and install this one", true, true], ["Stop", false, false]]);
    clearStatus();
    if (!remove) throw new Stop("Stopped. Your phone keeps its own copy of FlipFix.");
    task("install", "active", "Removing the other copy…");
    await pm.uninstall(PACKAGE);
    await push();
  }
  task("install", "done", release ? `Version ${release.tag.replace(/^v/, "")}` : "");
}

async function grant(adb) {
  task("grant", "active");
  const grantResult = await shell(adb, `pm grant ${PACKAGE} android.permission.WRITE_SECURE_SETTINGS`);
  const listenerResult = await shell(adb, `cmd notification allow_listener ${LISTENER}`);
  // Read both back rather than trusting exit codes.
  const dump = (await shell(adb, `dumpsys package ${PACKAGE}`)).out;
  const secureOk = /android\.permission\.WRITE_SECURE_SETTINGS: granted=true/.test(dump);
  const listeners = (await shell(adb, "settings get secure enabled_notification_listeners")).out;
  const listenerOk = listeners.includes(`${PACKAGE}/`);
  if (secureOk && listenerOk) {
    task("grant", "done", "Write secure settings, notification access");
    return;
  }
  task("grant", "fail");
  if (!secureOk) say("error", "Write secure settings wasn't granted. ", grantResult.out ? ["code", grantResult.out] : "");
  if (!listenerOk) say("error", "Notification access wasn't allowed. ", listenerResult.out ? ["code", listenerResult.out] : "");
  throw new Stop("FlipFix is installed but missing a permission. Try again, or turn notification access on in the FlipFix app.");
}

// ----- Flow -----

let running = false;

async function run() {
  if (running) return;
  running = true;
  connectButton.disabled = true;
  panel.hidden = false;
  resetTasks();
  clearStatus();
  let adb = null;
  let current = "connect";
  try {
    task("connect", "active", "Pick your phone in the browser's list…");
    adb = await connect();
    task("connect", "done");
    current = "check";
    task("check", "active");
    await check(adb);
    current = "install";
    await install(adb);
    current = "grant";
    await grant(adb);
    say("success", "FlipFix is installed and ready.");
    say("", "Now finish on your phone (step 5): turn USB debugging off and Auto Blocker back on, then open FlipFix once. You can unplug the phone.");
    connectButton.textContent = "Connect again";
  } catch (e) {
    console.error("[FlipFix setup] stopped at", current, e);
    task(current, "fail");
    say("error", e instanceof Stop ? e.message : describe(e));
    // The browser's own words, for a bug report.
    const cause = e instanceof Stop ? e.cause : e;
    if (cause) say("technical", `Details: ${cause.name ?? "Error"}: ${cause.message ?? cause}`);
    connectButton.textContent = "Try again";
  } finally {
    if (adb) await adb.close().catch(() => {});
    running = false;
    connectButton.disabled = false;
  }
}

function describe(e) {
  const message = String(e?.message ?? e);
  if (/disconnected|transfer|device was disconnected|NetworkError/i.test(message)) {
    return "The connection to your phone was lost. Check the cable, unplug the phone and plug it back in, then try again.";
  }
  if (/Failure \[|INSTALL_/.test(message)) return `Your phone refused the install: ${message}`;
  return `Something went wrong: ${message}`;
}

function start() {
  loadRelease();
  if (!AdbDaemonWebUsbDeviceManager.BROWSER || !window.isSecureContext) {
    $("unsupported").hidden = false;
    connectButton.disabled = true;
    return;
  }
  connectButton.addEventListener("click", run);
}

start();
