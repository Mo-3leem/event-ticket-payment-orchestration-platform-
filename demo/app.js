const STORAGE_KEY = "ticketpay.demo.v1";
const HOLD_TTL_MS = 10 * 60 * 1000;
const BOOKING_TTL_MS = 10 * 60 * 1000;
const MAX_SEATS = 10;
const SEAT_CODES = ["A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10"];

const ICONS = {
  ticket:
    '<path d="M3 9a2 2 0 0 0 0 6v3a1 1 0 0 0 1 1h16a1 1 0 0 0 1-1v-3a2 2 0 0 1 0-6V6a1 1 0 0 0-1-1H4a1 1 0 0 0-1 1v3Z"/><path d="M13 5v2M13 11v2M13 17v2"/>',
  calendar: '<rect x="3" y="4" width="18" height="18" rx="2"/><path d="M16 2v4M8 2v4M3 10h18"/>',
  pin: '<path d="M20 10c0 6-8 12-8 12S4 16 4 10a8 8 0 1 1 16 0Z"/><circle cx="12" cy="10" r="3"/>',
  arrow: '<path d="M5 12h14M13 6l6 6-6 6"/>',
  check: '<path d="m20 6-11 11-5-5"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  shield:
    '<path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z"/><path d="m9 12 2 2 4-4"/>',
  refresh: '<path d="M21 12a9 9 0 1 1-2.64-6.36"/><path d="M21 3v6h-6"/>',
  alert:
    '<path d="M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0Z"/><path d="M12 9v4M12 17h.01"/>',
  x: '<path d="M18 6 6 18M6 6l12 12"/>',
  copy:
    '<rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
  lock: '<rect x="4" y="10" width="16" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3"/>',
  card: '<rect x="2" y="5" width="20" height="14" rx="2"/><path d="M2 10h20"/>',
  rotate: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><path d="M3 3v5h5"/>',
  sparkle:
    '<path d="m12 3 1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8L12 3Z"/><path d="M19 15v4M17 17h4"/>',
  chevron: '<path d="m9 18 6-6-6-6"/>',
  receipt:
    '<path d="M4 2v20l2-1 2 1 2-1 2 1 2-1 2 1 2-1 2 1V2l-2 1-2-1-2 1-2-1-2 1-2-1-2 1-2-1Z"/><path d="M8 7h8M8 11h8M8 15h5"/>',
  activity: '<path d="M22 12h-4l-3 8L9 4l-3 8H2"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
  seat:
    '<path d="M5 18v-6a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v6"/><path d="M5 18h14M7 10V6a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2v4"/><path d="M5 21v-3M19 21v-3"/>',
  sliders:
    '<path d="M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3"/><path d="M2 14h4M10 8h4M18 16h4"/>',
  code: '<path d="m16 18 6-6-6-6M8 6l-6 6 6 6"/>',
};

const BOOKING_STATUS = {
  PENDING_PAYMENT: { label: "Pending payment", tone: "warn" },
  CONFIRMED: { label: "Confirmed", tone: "success" },
  EXPIRED: { label: "Expired", tone: "neutral" },
  CANCELLED: { label: "Cancelled", tone: "neutral" },
};

const PAYMENT_STATUS = {
  CREATED: { label: "Created", tone: "neutral" },
  AUTHORIZING: { label: "Authorizing", tone: "info" },
  SUCCEEDED: { label: "Succeeded", tone: "success" },
  FAILED: { label: "Failed", tone: "danger" },
  UNKNOWN: { label: "Unknown", tone: "warn" },
  REFUNDED: { label: "Refunded", tone: "info" },
};

const AUDIT_META = {
  DEMO_READY: { icon: "sparkle", tone: "neutral" },
  HOLD_CREATED: { icon: "clock", tone: "warn" },
  HOLD_RELEASED: { icon: "rotate", tone: "neutral" },
  HOLD_EXPIRED: { icon: "clock", tone: "neutral" },
  BOOKING_CREATED: { icon: "receipt", tone: "warn" },
  BOOKING_EXPIRED: { icon: "rotate", tone: "danger" },
  PAYMENT_AUTHORIZING: { icon: "card", tone: "info" },
  PAYMENT_SUCCEEDED: { icon: "check", tone: "ok" },
  PAYMENT_DECLINED: { icon: "x", tone: "danger" },
  PAYMENT_UNKNOWN: { icon: "alert", tone: "warn" },
  RECONCILIATION_SUCCESS: { icon: "refresh", tone: "ok" },
  RECONCILIATION_DECLINED: { icon: "refresh", tone: "danger" },
  REFUND_COMPLETED: { icon: "rotate", tone: "ok" },
  MANUAL_REVIEW: { icon: "shield", tone: "neutral" },
  IDEMPOTENT_REPLAY: { icon: "copy", tone: "info" },
};

const PAYMENT_NOTE = {
  BOOKING_EXPIRED_AT_CONFIRM: "Captured after booking expiry · refund required",
  INSUFFICIENT_FUNDS: "Declined · insufficient funds",
  TIMEOUT: "Provider timeout · outcome unknown",
  PROVIDER_DECLINED: "Provider decline confirmed by reconciliation",
  AMBIGUOUS_LOOKUP: "Lookup still ambiguous · no state change",
};

const app = document.getElementById("app");
const modal = document.getElementById("modal");
const modalCard = document.getElementById("modal-card");
const toastRegion = document.getElementById("toast-region");

let state = loadState();
let lastRoute = "";
let storageWarned = false;

const ui = {
  bookingsFilter: "all",
  opsFilter: "all",
  paymentInFlight: false,
};

function icon(name, className = "icon") {
  return `<svg class="${className}" viewBox="0 0 24 24" aria-hidden="true">${ICONS[name] || ICONS.info}</svg>`;
}

function esc(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function uid(prefix) {
  const random =
    typeof crypto !== "undefined" && crypto.randomUUID
      ? crypto.randomUUID().replaceAll("-", "").slice(0, 16)
      : Math.random().toString(16).slice(2).padEnd(16, "0").slice(0, 16);
  return `${prefix}_${random}`;
}

function randomRef(length = 8) {
  const alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  const values = new Uint32Array(length);
  if (typeof crypto !== "undefined" && crypto.getRandomValues) {
    crypto.getRandomValues(values);
  } else {
    values.forEach((_, index) => {
      values[index] = Math.floor(Math.random() * 0xffffffff);
    });
  }
  return Array.from(values, (value) => alphabet[value % alphabet.length]).join("");
}

function plusMinutes(minutes) {
  return new Date(Date.now() + minutes * 60 * 1000).toISOString();
}

function dateAt(daysAhead, hour, minute) {
  const date = new Date();
  date.setDate(date.getDate() + daysAhead);
  date.setHours(hour, minute, 0, 0);
  return date.toISOString();
}

function money(minor, currency = "EGP") {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency,
    currencyDisplay: "code",
    minimumFractionDigits: 2,
  }).format(Number(minor || 0) / 100);
}

function formatDateTime(iso) {
  if (!iso) return "—";
  return new Intl.DateTimeFormat("en-GB", {
    weekday: "short",
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(iso));
}

function formatDate(iso) {
  if (!iso) return "—";
  return new Intl.DateTimeFormat("en-GB", {
    weekday: "short",
    day: "numeric",
    month: "short",
  }).format(new Date(iso));
}

function formatTime(iso) {
  if (!iso) return "—";
  return new Intl.DateTimeFormat("en-GB", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).format(new Date(iso));
}

function durationFrom(iso) {
  return Math.max(0, Date.parse(iso) - Date.now());
}

function formatDuration(ms) {
  const total = Math.max(0, Math.ceil(ms / 1000));
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${String(minutes).padStart(2, "0")}:${String(seconds).padStart(2, "0")}`;
}

function shortId(id) {
  return String(id || "")
    .replace(/^[a-z]+_/i, "")
    .slice(0, 8)
    .toUpperCase();
}

function eventSeatId(eventKey, code) {
  return `es_${eventKey}_${code.toLowerCase()}`;
}

function buildEvent(options) {
  const seats = SEAT_CODES.map((code) => ({
    id: eventSeatId(options.key, code),
    seatCode: code,
    status: options.sold.includes(code) ? "SOLD" : "AVAILABLE",
    heldBy: null,
    holdExpiresAt: null,
    bookingId: null,
    paymentId: null,
  }));

  if (options.guestHold) {
    const seat = seats.find((candidate) => candidate.seatCode === options.guestHold);
    if (seat) {
      seat.status = "HELD";
      seat.heldBy = "guest";
      seat.holdExpiresAt = plusMinutes(options.guestHoldMinutes || 7);
    }
  }

  return {
    id: `evt_${options.key}_2026`,
    key: options.key,
    title: options.title,
    venueId: "venue_grand_hall",
    venueName: "Grand Hall",
    startsAt: dateAt(options.daysAhead, options.hour, options.minute),
    priceMinor: options.priceMinor,
    currency: "EGP",
    description: options.description,
    poster: options.poster,
    posterIndex: options.index,
    seats,
  };
}

function createInitialState() {
  const fresh = {
    version: 1,
    createdAt: new Date().toISOString(),
    user: { id: "demo_guest", name: "Demo guest", email: "guest@ticketpay.demo" },
    events: [
      buildEvent({
        key: "spring",
        title: "Spring Concert",
        daysAhead: 7,
        hour: 20,
        minute: 0,
        priceMinor: 2500,
        description: "A seeded live-music event at Grand Hall.",
        poster: "spring",
        index: "01",
        sold: ["A1", "A2", "A6"],
        guestHold: "A4",
        guestHoldMinutes: 7,
      }),
      buildEvent({
        key: "summer",
        title: "Summer Gala",
        daysAhead: 14,
        hour: 19,
        minute: 30,
        priceMinor: 3500,
        description: "A second seeded event sharing the same physical hall.",
        poster: "summer",
        index: "02",
        sold: ["A5", "A8", "A10"],
        guestHold: "A7",
        guestHoldMinutes: 9,
      }),
    ],
    holds: [],
    bookings: [],
    payments: [],
    refunds: [],
    audit: [],
    selections: {},
    replays: 0,
  };

  pushAuditTo(
    fresh,
    "DEMO_READY",
    "Demo state initialized",
    "Two seeded events, ten physical seats, and live guest holds.",
  );
  return fresh;
}

function normalizeState(candidate) {
  if (!candidate || typeof candidate !== "object") return createInitialState();
  if (candidate.version !== 1) return createInitialState();
  if (!Array.isArray(candidate.events) || candidate.events.length !== 2) return createInitialState();
  if (!Array.isArray(candidate.holds)) candidate.holds = [];
  if (!Array.isArray(candidate.bookings)) candidate.bookings = [];
  if (!Array.isArray(candidate.payments)) candidate.payments = [];
  if (!Array.isArray(candidate.refunds)) candidate.refunds = [];
  if (!Array.isArray(candidate.audit)) candidate.audit = [];
  if (!candidate.selections || typeof candidate.selections !== "object") candidate.selections = {};
  if (!Number.isFinite(candidate.replays)) candidate.replays = 0;
  if (!candidate.user) candidate.user = { id: "demo_guest", name: "Demo guest" };
  return candidate;
}

function loadState() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return createInitialState();
    const candidate = normalizeState(JSON.parse(raw));
    const age = Date.now() - Date.parse(candidate.createdAt);
    if (!Number.isFinite(age) || age > 45 * 24 * 60 * 60 * 1000) return createInitialState();
    return candidate;
  } catch {
    return createInitialState();
  }
}

function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    if (!storageWarned) {
      storageWarned = true;
      toast("Browser storage is unavailable. This page will reset on refresh.", "warn");
    }
  }
}

function pushAuditTo(target, type, title, detail, options = {}) {
  const meta = AUDIT_META[type] || AUDIT_META.DEMO_READY;
  target.audit.unshift({
    id: uid("evt"),
    at: new Date().toISOString(),
    type,
    title,
    detail,
    tone: options.tone || meta.tone,
    icon: options.icon || meta.icon,
    bookingId: options.bookingId || null,
    paymentId: options.paymentId || null,
    eventId: options.eventId || null,
  });
  target.audit = target.audit.slice(0, 100);
}

function pushAudit(type, title, detail, options = {}) {
  pushAuditTo(state, type, title, detail, options);
}

function getEvent(eventId) {
  return state.events.find((event) => event.id === eventId);
}

function getBooking(bookingId) {
  return state.bookings.find((booking) => booking.id === bookingId);
}

function getPayment(paymentId) {
  return state.payments.find((payment) => payment.id === paymentId);
}

function paymentForBooking(bookingId) {
  return state.payments.find((payment) => payment.bookingId === bookingId);
}

function refundForPayment(paymentId) {
  return state.refunds.find((refund) => refund.paymentId === paymentId);
}

function seatsAvailable(event) {
  return event.seats.filter((seat) => seat.status === "AVAILABLE").length;
}

function activeHolds(eventId = null) {
  const now = Date.now();
  return state.holds.filter(
    (hold) =>
      Date.parse(hold.expiresAt) > now &&
      (!eventId || hold.eventId === eventId) &&
      Boolean(getEvent(hold.eventId)?.seats.find((seat) => seat.id === hold.eventSeatId)),
  );
}

function pendingBookings() {
  return state.bookings.filter((booking) => booking.status === "PENDING_PAYMENT");
}

function unresolvedPayments() {
  return state.payments.filter((payment) => payment.status === "UNKNOWN");
}

function refundRequired(payment) {
  return payment.status === "SUCCEEDED" && payment.note === "BOOKING_EXPIRED_AT_CONFIRM";
}

function statusBadge(kind, status) {
  const map = kind === "booking" ? BOOKING_STATUS : PAYMENT_STATUS;
  const meta = map[status] || { label: String(status || "Unknown"), tone: "neutral" };
  return `<span class="status status--${meta.tone}">${esc(meta.label)}</span>`;
}

function noteText(note) {
  return PAYMENT_NOTE[note] || note || "";
}

function currentRoute() {
  const raw = window.location.hash.replace(/^#\/?/, "");
  const parts = raw.split("/").filter(Boolean);
  return { key: parts.join("/") || "home", parts };
}

function navigate(hash, { replace = false } = {}) {
  if (replace) {
    window.location.replace(hash);
    return;
  }
  if (window.location.hash === hash) {
    render();
    return;
  }
  window.location.hash = hash;
}

function expireState() {
  const now = Date.now();
  let changed = false;
  const expiredSeatIds = new Set();

  for (const hold of [...state.holds]) {
    if (Date.parse(hold.expiresAt) > now) continue;
    const event = getEvent(hold.eventId);
    const seat = event?.seats.find((candidate) => candidate.id === hold.eventSeatId);
    if (seat && !seat.bookingId && seat.status === "HELD") {
      seat.status = "AVAILABLE";
      seat.heldBy = null;
      seat.holdExpiresAt = null;
      expiredSeatIds.add(seat.id);
      pushAudit("HOLD_EXPIRED", `Hold expired for ${seat.seatCode}`, `${event.title} · seat returned to inventory.`, {
        tone: "neutral",
        eventId: event.id,
      });
    }
    state.holds = state.holds.filter((candidate) => candidate.id !== hold.id);
    changed = true;
  }

  for (const event of state.events) {
    for (const seat of event.seats) {
      if (
        seat.status === "HELD" &&
        !seat.bookingId &&
        seat.holdExpiresAt &&
        Date.parse(seat.holdExpiresAt) <= now
      ) {
        if (!expiredSeatIds.has(seat.id)) {
          pushAudit(
            "HOLD_EXPIRED",
            `Guest hold expired for ${seat.seatCode}`,
            `${event.title} · seat returned to inventory.`,
            { tone: "neutral", eventId: event.id },
          );
        }
        seat.status = "AVAILABLE";
        seat.heldBy = null;
        seat.holdExpiresAt = null;
        changed = true;
      }
    }
  }

  for (const booking of state.bookings) {
    if (booking.status !== "PENDING_PAYMENT") continue;
    if (Date.parse(booking.expiresAt) > now) continue;

    booking.status = "EXPIRED";
    booking.expiredAt = new Date().toISOString();
    const event = getEvent(booking.eventId);
    for (const item of booking.items) {
      const seat = event?.seats.find((candidate) => candidate.id === item.eventSeatId);
      if (seat && seat.status === "HELD") {
        seat.status = "AVAILABLE";
        seat.heldBy = null;
        seat.holdExpiresAt = null;
        seat.bookingId = null;
        seat.paymentId = null;
      }
    }

    const payment = paymentForBooking(booking.id);
    pushAudit(
      "BOOKING_EXPIRED",
      `Booking ${booking.reference} expired`,
      `${booking.items.length} seat${booking.items.length === 1 ? "" : "s"} released${payment ? ` · payment ${payment.status}` : ""}.`,
      {
        tone: "danger",
        bookingId: booking.id,
        paymentId: payment?.id,
        eventId: booking.eventId,
      },
    );
    changed = true;
  }

  if (changed) persist();
  return changed;
}

function updateNav() {
  const route = currentRoute();
  const active =
    route.parts[0] === "bookings" || route.parts[0] === "booking"
      ? "bookings"
      : route.parts[0] === "ops"
        ? "ops"
        : "events";

  document.querySelectorAll("[data-nav]").forEach((link) => {
    if (link.dataset.nav === active) link.setAttribute("aria-current", "page");
    else link.removeAttribute("aria-current");
  });

  const bookingCount = pendingBookings().length;
  const bookingBadge = document.getElementById("bookings-nav-count");
  if (bookingBadge) {
    bookingBadge.textContent = String(bookingCount);
    bookingBadge.hidden = bookingCount === 0;
  }

  const actionCount =
    unresolvedPayments().length + state.payments.filter((payment) => refundRequired(payment)).length;
  const opsBadge = document.getElementById("ops-nav-count");
  if (opsBadge) {
    opsBadge.textContent = String(actionCount);
    opsBadge.hidden = actionCount === 0;
  }
}

function render({ scrollTop = false, focusSelector = null } = {}) {
  expireState();
  updateNav();

  const route = currentRoute();
  lastRoute = route.key;

  let html = "";
  if (route.key === "home") {
    document.title = "TicketPay · Orchestration demo";
    html = renderHome();
  } else if (route.parts[0] === "event" && route.parts[1]) {
    const event = getEvent(route.parts[1]);
    document.title = event ? `${event.title} · TicketPay demo` : "Event not found · TicketPay";
    html = event ? renderEvent(event) : renderNotFound();
  } else if (route.parts[0] === "booking" && route.parts[1]) {
    const booking = getBooking(route.parts[1]);
    document.title = booking ? `Booking ${booking.reference} · TicketPay` : "Booking not found · TicketPay";
    html = booking ? renderBooking(booking) : renderNotFound();
  } else if (route.key === "bookings") {
    document.title = "Bookings · TicketPay demo";
    html = renderBookings();
  } else if (route.key === "ops") {
    document.title = "Payment operations · TicketPay demo";
    html = renderOps();
  } else {
    document.title = "Not found · TicketPay demo";
    html = renderNotFound();
  }

  app.innerHTML = html;
  updateCountdowns();

  if (scrollTop) window.scrollTo({ top: 0, left: 0, behavior: "auto" });
  if (focusSelector) {
    requestAnimationFrame(() => {
      document.querySelector(focusSelector)?.focus({ preventScroll: true });
    });
  }
}

function renderNotFound() {
  return `
    <div class="view shell section">
      <div class="empty-state">
        <div>
          <span class="empty-visual">${icon("alert")}</span>
          <h2>That route does not exist.</h2>
          <p>The demo only knows events, bookings, and the payment operations console.</p>
          <a class="btn btn--primary" href="#/">Back to events ${icon("arrow")}</a>
        </div>
      </div>
    </div>
  `;
}

function updateCountdowns() {
  document.querySelectorAll("[data-expires]").forEach((element) => {
    const ms = durationFrom(element.dataset.expires);
    element.textContent = ms > 0 ? formatDuration(ms) : "00:00";
    element.classList.toggle("countdown--warn", ms > 0 && ms <= 2 * 60 * 1000);
    element.classList.toggle("countdown--danger", ms > 0 && ms <= 30 * 1000);
  });
}

function renderTrace() {
  const holds = activeHolds().length;
  const pending = pendingBookings().length;
  const captured = state.payments.filter((payment) =>
    ["SUCCEEDED", "REFUNDED"].includes(payment.status),
  ).length;
  const open = unresolvedPayments().length;
  const lastEvent = state.audit[0];

  return `
    <aside class="trace-card" aria-label="Live orchestration trace">
      <div class="trace-top">
        <span class="trace-title">${icon("activity")}Orchestration trace</span>
        <span class="trace-live">live</span>
      </div>
      <div class="trace-flow">
        <div class="trace-step ${holds > 0 ? "trace-step--active" : ""}">
          <span class="trace-node">${icon("clock")}</span>
          <div class="trace-copy">
            <strong>Hold</strong>
            <span>10-minute TTL</span>
          </div>
          <span class="trace-value ${holds > 0 ? "trace-value--warn" : ""}">${holds} held</span>
        </div>
        <div class="trace-step ${pending > 0 ? "trace-step--active" : ""}">
          <span class="trace-node">${icon("receipt")}</span>
          <div class="trace-copy">
            <strong>Booking</strong>
            <span>pending → confirmed</span>
          </div>
          <span class="trace-value ${pending > 0 ? "trace-value--warn" : ""}">${pending} pending</span>
        </div>
        <div class="trace-step ${captured > 0 ? "trace-step--active" : ""}">
          <span class="trace-node">${icon("card")}</span>
          <div class="trace-copy">
            <strong>Payment</strong>
            <span>idempotent capture</span>
          </div>
          <span class="trace-value ${captured > 0 ? "trace-value--ok" : ""}">${captured} captured</span>
        </div>
        <div class="trace-step ${open > 0 ? "trace-step--active" : ""}">
          <span class="trace-node">${icon("refresh")}</span>
          <div class="trace-copy">
            <strong>Reconcile</strong>
            <span>UNKNOWN is not failed</span>
          </div>
          <span class="trace-value ${open > 0 ? "trace-value--danger" : "trace-value--ok"}">${open > 0 ? `${open} open` : "clear"}</span>
        </div>
      </div>
      <div class="trace-footer">
        <span>${lastEvent ? esc(lastEvent.title) : "Demo state ready"}</span>
        <strong>${lastEvent ? formatTime(lastEvent.at) : "now"}</strong>
      </div>
    </aside>
  `;
}

function renderSessionBanner() {
  const pending = pendingBookings();
  const unknown = unresolvedPayments();
  const refundsDue = state.payments.filter((payment) => refundRequired(payment));

  if (pending.length || unknown.length || refundsDue.length) {
    const firstBooking = pending[0];
    return `
      <div class="session-banner ${unknown.length || refundsDue.length ? "session-banner--warn" : ""}">
        <span class="session-icon">${icon(unknown.length || refundsDue.length ? "alert" : "clock")}</span>
        <div class="session-copy">
          <strong>${pending.length ? `${pending.length} booking${pending.length === 1 ? "" : "s"} in progress` : "Operator attention needed"}</strong>
          <span>${unknown.length ? `${unknown.length} payment${unknown.length === 1 ? "" : "s"} still UNKNOWN` : refundsDue.length ? `${refundsDue.length} captured payment${refundsDue.length === 1 ? "" : "s"} require a refund` : "Seats release automatically when the timer ends."}</span>
        </div>
        <div class="session-actions">
          ${firstBooking ? `<a class="btn btn--secondary btn--small" href="#/booking/${firstBooking.id}">Resume ${firstBooking.reference}</a>` : ""}
          ${unknown.length || refundsDue.length ? `<a class="btn btn--primary btn--small" href="#/ops">Open payment ops ${icon("arrow")}</a>` : ""}
        </div>
      </div>
    `;
  }

  return `
    <div class="callout">
      ${icon("sparkle")}
      <div>
        <strong>Try the full loop.</strong>
        Select seats, hold them for ten minutes, then run an approved, declined, or timed-out mock payment.
        <span class="muted">Everything stays in this browser.</span>
      </div>
      <a class="btn btn--quiet btn--small" href="#/ops">View payments ${icon("chevron")}</a>
    </div>
  `;
}

function renderHome() {
  const confirmed = state.bookings.filter((booking) => booking.status === "CONFIRMED").length;
  const holds = activeHolds().length;
  const openPayments = unresolvedPayments().length;
  const sold = state.events.reduce(
    (total, event) => total + event.seats.filter((seat) => seat.status === "SOLD").length,
    0,
  );

  return `
    <div class="view">
      <section class="hero shell">
        <div class="hero-copy">
          <span class="eyebrow">Event ticketing · payment orchestration</span>
          <h1>Every seat.<br />Every piaster.<br /><em>Accounted for.</em></h1>
          <p class="hero-lede">
            A working simulation of the Spring Boot backend: atomic seat holds, expiring bookings,
            idempotent mock payments, reconciliation for unknown outcomes, and one full refund per capture.
          </p>
          <div class="hero-actions">
            <a class="btn btn--primary" href="#/event/${state.events[0].id}">Choose seats ${icon("arrow")}</a>
            <a class="btn btn--secondary" href="#/ops">${icon("sliders")}Payment operations</a>
          </div>
          <div class="hero-notes">
            <span>${icon("shield")}No real charges</span>
            <span>${icon("lock")}No API calls</span>
            <span>${icon("refresh")}Refresh-safe state</span>
          </div>
        </div>
        ${renderTrace()}
      </section>

      <div class="shell">
        ${renderSessionBanner()}

        <div class="stats-strip" aria-label="Demo state summary">
          <div class="stat">
            <span class="stat-value">${confirmed}</span>
            <span class="stat-label">Confirmed bookings</span>
          </div>
          <div class="stat">
            <span class="stat-value">${holds}</span>
            <span class="stat-label">Active holds</span>
          </div>
          <div class="stat">
            <span class="stat-value">${openPayments}</span>
            <span class="stat-label">Unresolved payments</span>
          </div>
          <div class="stat">
            <span class="stat-value">${sold}</span>
            <span class="stat-label">Seats sold</span>
          </div>
        </div>
      </div>

      <section class="shell section">
        <div class="section-head">
          <div>
            <span class="eyebrow eyebrow--plain">Now playing</span>
            <h2>Two seeded events. One physical hall.</h2>
          </div>
          <p>Every event creates its own sellable event_seats, so the same A1 can be sold once per event.</p>
        </div>
        <div class="events-grid">
          ${state.events.map(renderEventCard).join("")}
        </div>
      </section>

      <section class="shell section">
        <div class="section-head">
          <div>
            <span class="eyebrow eyebrow--plain">What is simulated</span>
            <h2>Honest edges, visible by design.</h2>
          </div>
          <p>The interface mirrors the server contract without pretending a Java process is running.</p>
        </div>
        <div class="feature-grid">
          <article class="feature">
            <span class="feature-icon">${icon("clock")}</span>
            <h3>Time is real</h3>
            <p>Holds and bookings use ten-minute deadlines. Expired seats return to inventory while unresolved payments remain visible for reconciliation.</p>
          </article>
          <article class="feature">
            <span class="feature-icon">${icon("refresh")}</span>
            <h3>Unknown ≠ failed</h3>
            <p>A timed-out provider call becomes UNKNOWN. Reconciliation can confirm capture or decline; it never moves money by itself.</p>
          </article>
          <article class="feature">
            <span class="feature-icon">${icon("shield")}</span>
            <h3>Refunds do not re-sell</h3>
            <p>One full refund is allowed per captured payment. The booking stays confirmed and the seat stays sold, matching the backend.</p>
          </article>
        </div>
      </section>

      <section class="shell section">
        <div class="callout">
          ${icon("code")}
          <div>
            <strong>Backend mapping</strong>
            <span class="muted">This demo mirrors event, hold, booking, payment, reconciliation, and refund states. It is static HTML, CSS, and JavaScript with no server dependency.</span>
          </div>
          <button class="btn btn--quiet btn--small" type="button" data-action="about">Read the mapping ${icon("chevron")}</button>
        </div>
      </section>
    </div>
  `;
}

function renderEventCard(event) {
  const available = seatsAvailable(event);
  const ratio = Math.round((available / event.seats.length) * 100);
  return `
    <a class="event-card" href="#/event/${event.id}">
      <div class="poster poster--${event.poster}">
        <span class="poster-kicker">${esc(event.venueName)} · ${formatDate(event.startsAt)}</span>
        <span class="poster-index">${esc(event.posterIndex)}</span>
        <span class="poster-title">${esc(event.title)}</span>
        <span class="poster-tag">${event.seats.length} seats</span>
      </div>
      <div class="event-body">
        <div class="event-meta">
          <span>${icon("calendar")}${formatDateTime(event.startsAt)}</span>
          <span>${icon("pin")}${esc(event.venueName)}</span>
        </div>
        <div class="event-title-row">
          <h3>${esc(event.title)}</h3>
          <span class="event-price"><strong>${money(event.priceMinor, event.currency)}</strong><span>per seat</span></span>
        </div>
        <div class="availability">
          <div class="availability-copy">
            <strong>${available} of ${event.seats.length} available</strong>
            <span>${available <= 3 ? "Limited inventory" : "Live seeded inventory"}</span>
          </div>
          <div class="availability-bar ${available <= 3 ? "availability-bar--low" : ""}" aria-hidden="true">
            <span style="width:${Math.max(4, ratio)}%"></span>
          </div>
        </div>
        <div class="event-footer">
          <span class="muted mono">${esc(event.id)}</span>
          <span class="btn btn--secondary btn--small">Reserve seats ${icon("arrow")}</span>
        </div>
      </div>
    </a>
  `;
}

function renderEvent(event) {
  const selected = state.selections[event.id] || [];
  const holds = activeHolds(event.id);
  const available = seatsAvailable(event);

  return `
    <div class="view shell section">
      <div class="page-head">
        <div>
          <div class="breadcrumb">
            <a href="#/">Events</a>${icon("chevron")}<span>${esc(event.title)}</span>
          </div>
          <h1>${esc(event.title)}</h1>
          <p>${esc(event.description)}</p>
        </div>
        <div class="page-head-actions">
          <span class="badge">${icon("calendar")}${formatDateTime(event.startsAt)}</span>
          <span class="badge">${icon("pin")}${esc(event.venueName)}</span>
          <span class="badge">${money(event.priceMinor, event.currency)} / seat</span>
        </div>
      </div>

      <div class="event-layout">
        <section class="seat-panel" aria-labelledby="seat-heading">
          <div class="seat-panel-head">
            <div>
              <h2 id="seat-heading">Choose your seats</h2>
              <p>${available} available · max ${MAX_SEATS} per booking · hold expires in 10 minutes</p>
            </div>
            <span class="status status--info">${icon("seat")} Row A</span>
          </div>

          <div class="hall">
            <div class="stage">Stage</div>
            <div class="seat-row">
              ${event.seats.map((seat) => renderSeat(seat, event, selected, holds)).join("")}
            </div>
            <p class="seat-caption">Grand Hall · Row A · screen this way</p>
          </div>

          <div class="seat-legend" aria-label="Seat legend">
            <span><i class="legend-dot legend-dot--available"></i>Available</span>
            <span><i class="legend-dot legend-dot--selected"></i>Selected</span>
            <span><i class="legend-dot legend-dot--held"></i>Held</span>
            <span><i class="legend-dot legend-dot--sold"></i>Sold</span>
          </div>

          <div class="select-note">
            ${icon("info")}
            <span>Guest holds are already present. Your own holds are reserved for this browser session and count down live.</span>
          </div>
        </section>

        ${renderEventSummary(event, selected, holds)}
      </div>
    </div>
  `;
}

function renderSeat(seat, event, selected, holds) {
  const myHold = holds.find((hold) => hold.eventSeatId === seat.id);
  const isSelected = selected.includes(seat.seatCode);
  const isMyHold = seat.status === "HELD" && seat.heldBy === "you" && Boolean(myHold);
  const isBookingHold = seat.status === "HELD" && seat.heldBy === "booking";
  const isGuestHold = seat.status === "HELD" && !isMyHold && !isBookingHold;
  const isSold = seat.status === "SOLD";

  let modifier = "seat--available";
  let disabled = false;
  let label = `${seat.seatCode}: available`;

  if (isSold) {
    modifier = "seat--sold";
    disabled = true;
    label = `${seat.seatCode}: sold`;
  } else if (isGuestHold) {
    modifier = "seat--held";
    disabled = true;
    label = `${seat.seatCode}: held by another guest${seat.holdExpiresAt ? ` until ${formatTime(seat.holdExpiresAt)}` : ""}`;
  } else if (isMyHold) {
    modifier = "seat--yours";
    disabled = true;
    label = `${seat.seatCode}: held by you`;
  } else if (isBookingHold) {
    modifier = "seat--yours";
    disabled = true;
    label = `${seat.seatCode}: held by your pending booking`;
  } else if (isSelected) {
    modifier = "seat--selected";
    label = `${seat.seatCode}: selected`;
  }

  return `
    <button
      class="seat ${modifier}"
      type="button"
      data-action="toggle-seat"
      data-event-id="${event.id}"
      data-seat-id="${seat.id}"
      data-seat-code="${seat.seatCode}"
      aria-label="${esc(label)}"
      aria-pressed="${isSelected ? "true" : "false"}"
      ${disabled ? "disabled" : ""}
    ><span>${esc(seat.seatCode)}</span></button>
  `;
}

function renderEventSummary(event, selected, holds) {
  const selectedTotal = selected.length * event.priceMinor;
  const holdTotal = holds.reduce((total, hold) => {
    return total + (event.seats.find((seat) => seat.id === hold.eventSeatId) ? event.priceMinor : 0);
  }, 0);
  const selectedAtLimit = selected.length + holds.length >= MAX_SEATS;
  const canHold = selected.length > 0 && selected.length + holds.length <= MAX_SEATS;

  return `
    <aside class="summary-card" aria-labelledby="summary-heading">
      <div class="summary-head">
        <h2 id="summary-heading">Your selection</h2>
        <span>${selected.length + holds.length}/${MAX_SEATS}</span>
      </div>

      <div class="summary-event">
        <span class="summary-monogram">${event.poster === "spring" ? "SC" : "SG"}</span>
        <div>
          <strong>${esc(event.title)}</strong>
          <span>${formatDate(event.startsAt)} · ${esc(event.venueName)}</span>
        </div>
      </div>

      <div class="summary-section">
        <div class="summary-label">
          <span>Selected</span>
          <span>${selected.length} seat${selected.length === 1 ? "" : "s"}</span>
        </div>
        <div class="selection-list">
          ${
            selected.length
              ? selected
                  .map(
                    (code) => `
                      <span class="selection-row">
                        <span>${esc(code)}</span>
                        <span>${money(event.priceMinor, event.currency)}</span>
                      </span>
                    `,
                  )
                  .join("")
              : `<div class="empty-mini">Pick available seats from Row A.</div>`
          }
        </div>
        ${selectedAtLimit ? `<p class="pay-footnote">${icon("info")}Maximum ${MAX_SEATS} seats per booking.</p>` : ""}
      </div>

      ${
        holds.length
          ? `
            <div class="summary-section">
              <div class="summary-label"><span>Held by you</span><span>${holds.length} hold${holds.length === 1 ? "" : "s"}</span></div>
              <div class="hold-list">
                ${holds
                  .map((hold) => {
                    const seat = event.seats.find((candidate) => candidate.id === hold.eventSeatId);
                    return `
                      <div class="hold-row">
                        <span class="seat-chip seat-chip--held">${esc(seat?.seatCode || "?")}</span>
                        <div class="hold-row-copy">
                          <strong>Held</strong>
                          <span class="countdown" data-expires="${hold.expiresAt}">${formatDuration(durationFrom(hold.expiresAt))}</span>
                        </div>
                        <button class="icon-button" type="button" data-action="release-hold" data-hold-id="${hold.id}" aria-label="Release hold for ${esc(seat?.seatCode || "seat")}">
                          ${icon("x")}
                        </button>
                      </div>
                    `;
                  })
                  .join("")}
              </div>
            </div>
          `
          : ""
      }

      <div class="summary-total">
        <span>Face value</span>
        <strong>${money(selectedTotal + holdTotal, event.currency)}</strong>
      </div>

      <div class="summary-actions">
        ${
          selected.length
            ? `<button class="btn btn--primary btn--wide" type="button" data-action="hold-selected" data-event-id="${event.id}" ${canHold ? "" : "disabled"}>
                ${icon("clock")}Hold ${selected.length} seat${selected.length === 1 ? "" : "s"}
              </button>`
            : ""
        }
        ${
          holds.length
            ? `<button class="btn ${selected.length ? "btn--secondary" : "btn--primary"} btn--wide" type="button" data-action="create-booking" data-event-id="${event.id}">
                Continue with ${holds.length} held seat${holds.length === 1 ? "" : "s"} ${icon("arrow")}
              </button>`
            : ""
        }
        ${
          !selected.length && !holds.length
            ? `<button class="btn btn--primary btn--wide" type="button" disabled>${icon("seat")}Select a seat to begin</button>`
            : ""
        }
      </div>
      <p class="pay-footnote">${icon("lock")}Price is face value in minor units. No fees, taxes, or real charges are simulated.</p>
    </aside>
  `;
}

function renderBooking(booking) {
  const event = getEvent(booking.eventId);
  const payment = paymentForBooking(booking.id);
  const refund = payment ? refundForPayment(payment.id) : null;
  const isPending = booking.status === "PENDING_PAYMENT";
  const isConfirmed = booking.status === "CONFIRMED";
  const isExpired = booking.status === "EXPIRED";
  const stepTwoDone = payment && ["SUCCEEDED", "REFUNDED"].includes(payment.status);
  const stepThreeDone = isConfirmed;

  return `
    <div class="view shell section">
      <div class="page-head">
        <div>
          <div class="breadcrumb">
            <a href="#/bookings">Bookings</a>${icon("chevron")}<span>${esc(booking.reference)}</span>
          </div>
          <h1>Booking <span class="booking-ref">${esc(booking.reference)}</span></h1>
          <p>${esc(event?.title || "Event")} · ${booking.items.length} seat${booking.items.length === 1 ? "" : "s"} · ${money(booking.totalMinor, booking.currency)}</p>
        </div>
        <div class="page-head-actions">
          ${statusBadge("booking", booking.status)}
          ${payment ? statusBadge("payment", payment.status) : ""}
        </div>
      </div>

      <div class="booking-layout">
        <div class="booking-main">
          <section class="panel" aria-labelledby="booking-overview">
            <div class="stepper" aria-label="Booking progress">
              <span class="step step--done"><b>1</b>Seats held</span>
              <span class="step ${isPending ? "step--current" : stepTwoDone ? "step--done" : ""}"><b>2</b>Payment</span>
              <span class="step ${stepThreeDone ? "step--done" : ""}"><b>3</b>${payment?.status === "REFUNDED" ? "Refunded" : "Confirmed"}</span>
            </div>

            <div class="booking-event">
              <span class="booking-event-monogram">${event?.poster === "spring" ? "SC" : "SG"}</span>
              <div>
                <h3>${esc(event?.title || "Event")}</h3>
                <p>${event ? `${formatDateTime(event.startsAt)} · ${esc(event.venueName)}` : "Event unavailable"}</p>
              </div>
              <span class="reference">Reference <strong>${esc(booking.reference)}</strong></span>
            </div>

            <div class="detail-grid">
              <div class="detail"><span>Seats</span><strong>${booking.items.map((item) => esc(item.seatCode)).join(", ")}</strong></div>
              <div class="detail"><span>Amount</span><strong>${money(booking.totalMinor, booking.currency)}</strong></div>
              <div class="detail"><span>Created</span><strong>${formatDateTime(booking.createdAt)}</strong></div>
              <div class="detail">
                <span>${isPending ? "Expires" : "Settled"}</span>
                <strong>${isPending ? `<span class="countdown" data-expires="${booking.expiresAt}">${formatDuration(durationFrom(booking.expiresAt))}</span>` : formatDateTime(booking.confirmedAt || booking.expiredAt || booking.createdAt)}</strong>
              </div>
            </div>

            ${
              isPending
                ? `<div class="alert alert--warn">
                    <span class="alert-icon">${icon("clock")}</span>
                    <div class="alert-copy">
                      <strong>Booking expires in <span class="countdown" data-expires="${booking.expiresAt}">${formatDuration(durationFrom(booking.expiresAt))}</span></strong>
                      <p>If payment is not confirmed before the deadline, the booking expires and these seats return to inventory.</p>
                    </div>
                  </div>`
                : ""
            }

            ${isExpired ? renderExpiredBooking(event) : ""}
            ${renderPaymentArea(booking, payment, refund)}
          </section>

          ${renderTimeline(booking, payment, refund)}
        </div>

        <aside class="booking-side">
          ${renderBookingReceipt(booking, payment, refund, true)}
          ${renderTechMap()}
        </aside>
      </div>
    </div>
  `;
}

function renderExpiredBooking(event) {
  return `
    <div class="alert alert--danger">
      <span class="alert-icon">${icon("alert")}</span>
      <div class="alert-copy">
        <strong>This booking expired.</strong>
        <p>The 10-minute booking deadline passed. Any held seats were released. A payment that captured after expiry remains attached for operator review and refund.</p>
      </div>
      <div class="alert-actions">
        <a class="btn btn--secondary btn--small" href="#/event/${event?.id || ""}">Pick seats again ${icon("arrow")}</a>
      </div>
    </div>
  `;
}

function renderPaymentArea(booking, payment, refund) {
  if (booking.status === "CANCELLED") {
    return `
      <div class="alert">
        <span class="alert-icon">${icon("info")}</span>
        <div class="alert-copy">
          <strong>Booking cancelled</strong>
          <p>No payment can be started from a cancelled booking.</p>
        </div>
      </div>
    `;
  }

  if (!payment && booking.status === "PENDING_PAYMENT") {
    return `
      <div class="divider"></div>
      <div class="panel-head">
        <div>
          <h2>Simulate a payment</h2>
          <p>Choose one provider outcome. One payment is allowed per booking.</p>
        </div>
        <span class="status status--neutral">${icon("lock")}Mock provider</span>
      </div>

      <div class="key-box">
        <div>
          <span class="summary-label">Idempotency-Key</span>
          <code>${esc(booking.idempotencyKey)}</code>
        </div>
        <button class="icon-button" type="button" data-action="copy-key" data-key="${esc(booking.idempotencyKey)}" aria-label="Copy idempotency key">
          ${icon("copy")}
        </button>
      </div>
      <p class="pay-footnote">${icon("info")}The same key is reused on retries. The provider should return the stored result instead of creating a second charge.</p>

      <div class="payment-options" role="radiogroup" aria-label="Mock payment outcome">
        <label class="payment-option">
          <input type="radio" name="payment-outcome" value="success" checked />
          <div class="payment-option-copy">
            <strong>Approve</strong>
            <span>Provider accepts and the booking becomes confirmed.</span>
          </div>
          <span class="status status--success">Success</span>
        </label>
        <label class="payment-option">
          <input type="radio" name="payment-outcome" value="decline" />
          <div class="payment-option-copy">
            <strong>Decline</strong>
            <span>Provider declines. This booking cannot start a second payment.</span>
          </div>
          <span class="status status--danger">Failed</span>
        </label>
        <label class="payment-option">
          <input type="radio" name="payment-outcome" value="timeout" />
          <div class="payment-option-copy">
            <strong>Timeout</strong>
            <span>Provider times out. The outcome becomes UNKNOWN, not failed.</span>
          </div>
          <span class="status status--warn">Unknown</span>
        </label>
      </div>

      <div class="pay-actions">
        <button class="btn btn--primary" type="button" data-action="pay" data-booking-id="${booking.id}" ${durationFrom(booking.expiresAt) <= 0 ? "disabled" : ""}>
          ${icon("card")}Run mock payment
        </button>
        <span class="pay-footnote">${icon("shield")}No card details, network requests, or real charges.</span>
      </div>
    `;
  }

  if (!payment) {
    return "";
  }

  if (payment.status === "AUTHORIZING") {
    return `
      <div class="divider"></div>
      <div class="alert alert--warn">
        <span class="alert-icon">${icon("activity")}</span>
        <div class="alert-copy">
          <strong>Provider call in progress</strong>
          <p>The mock provider is deciding. Refreshing is safe; the result resumes from stored state.</p>
        </div>
      </div>
      <div class="pay-actions">
        <button class="btn btn--primary" type="button" disabled>${icon("card")}Authorizing…</button>
      </div>
    `;
  }

  if (payment.status === "FAILED") {
    return `
      <div class="divider"></div>
      <div class="alert alert--danger">
        <span class="alert-icon">${icon("x")}</span>
        <div class="alert-copy">
          <strong>Payment declined</strong>
          <p>${esc(noteText(payment.note))}. No second payment can be created for this booking; the seats release when the booking expires.</p>
        </div>
        <div class="alert-actions">
          <a class="btn btn--secondary btn--small" href="#/bookings">Back to bookings ${icon("arrow")}</a>
        </div>
      </div>
      ${renderReceipt(payment, refund)}
    `;
  }

  if (payment.status === "UNKNOWN") {
    return `
      <div class="divider"></div>
      <div class="alert alert--warn">
        <span class="alert-icon">${icon("alert")}</span>
        <div class="alert-copy">
          <strong>Payment outcome unknown</strong>
          <p>${esc(noteText(payment.note))}. This is not a failure. Reconcile the provider lookup before refunding or releasing anything.</p>
        </div>
        <div class="alert-actions">
          <a class="btn btn--primary btn--small" href="#/ops">Reconcile in ops ${icon("arrow")}</a>
        </div>
      </div>
      ${renderReceipt(payment, refund)}
    `;
  }

  if (payment.status === "SUCCEEDED") {
    return `
      <div class="divider"></div>
      <div class="alert">
        <span class="alert-icon">${icon("check")}</span>
        <div class="alert-copy">
          <strong>${payment.note === "BOOKING_EXPIRED_AT_CONFIRM" ? "Captured after booking expiry" : "Payment captured"}</strong>
          <p>${payment.note === "BOOKING_EXPIRED_AT_CONFIRM" ? "The provider captured after the booking deadline. The booking stays expired and this payment requires a full refund." : "The booking is confirmed and the seats remain sold. Replaying the same idempotency key returns this result."}</p>
        </div>
        <div class="alert-actions">
          <button class="btn btn--quiet btn--small" type="button" data-action="replay-payment" data-payment-id="${payment.id}">${icon("copy")}Replay key</button>
          ${refundRequired(payment) || !refund ? `<button class="btn btn--danger btn--small" type="button" data-action="refund-payment" data-payment-id="${payment.id}">${icon("rotate")}Refund ${money(payment.amountMinor, payment.currency)}</button>` : ""}
        </div>
      </div>
      ${renderReceipt(payment, refund)}
    `;
  }

  if (payment.status === "REFUNDED") {
    return `
      <div class="divider"></div>
      <div class="alert">
        <span class="alert-icon">${icon("rotate")}</span>
        <div class="alert-copy">
          <strong>Full refund completed</strong>
          <p>The payment is refunded and no second refund is allowed. The booking remains ${booking.status === "CONFIRMED" ? "confirmed" : booking.status.toLowerCase().replaceAll("_", " ")} and the seat is not re-sold.</p>
        </div>
        <div class="alert-actions">
          <button class="btn btn--quiet btn--small" type="button" data-action="replay-payment" data-payment-id="${payment.id}">${icon("copy")}Replay key</button>
        </div>
      </div>
      ${renderReceipt(payment, refund)}
    `;
  }

  return "";
}

function renderReceipt(payment, refund) {
  if (!payment) return "";
  return `
    <div class="receipt">
      <div class="receipt-row"><span>Payment</span><code>${esc(payment.id)}</code></div>
      <div class="receipt-row"><span>Status</span>${statusBadge("payment", payment.status)}</div>
      <div class="receipt-row"><span>Amount</span><strong>${money(payment.amountMinor, payment.currency)}</strong></div>
      <div class="receipt-row"><span>Provider reference</span><code>${esc(payment.providerRef || "pending")}</code></div>
      <div class="receipt-row"><span>Idempotency-Key</span><code>${esc(payment.idempotencyKey)}</code></div>
      ${payment.reconciledAt ? `<div class="receipt-row"><span>Reconciled</span><strong>${formatDateTime(payment.reconciledAt)}</strong></div>` : ""}
      ${refund ? `<div class="receipt-row"><span>Refund</span><strong>${money(refund.amountMinor, refund.currency)}</strong></div><div class="receipt-row"><span>Refunded at</span><strong>${formatDateTime(refund.refundedAt)}</strong></div>` : ""}
    </div>
  `;
}

function renderBookingReceipt(booking, payment, refund, compact = false) {
  const event = getEvent(booking.eventId);
  return `
    <section class="${compact ? "panel" : "panel"}" aria-labelledby="receipt-heading-${compact ? "side" : "main"}">
      <div class="panel-head">
        <div>
          <h2 id="receipt-heading-${compact ? "side" : "main"}">${compact ? "Order summary" : "Receipt"}</h2>
          <p>${esc(event?.title || "Event")} · ${esc(booking.reference)}</p>
        </div>
        ${statusBadge("booking", booking.status)}
      </div>
      <div class="receipt">
        ${booking.items
          .map(
            (item) => `
              <div class="receipt-row">
                <span>Seat ${esc(item.seatCode)}</span>
                <strong>${money(item.priceMinor, booking.currency)}</strong>
              </div>
            `,
          )
          .join("")}
        <div class="receipt-row"><span>Total</span><strong>${money(booking.totalMinor, booking.currency)}</strong></div>
        ${payment ? `<div class="receipt-row"><span>Payment</span>${statusBadge("payment", payment.status)}</div>` : `<div class="receipt-row"><span>Payment</span><span class="muted">Not started</span></div>`}
        ${refund ? `<div class="receipt-row"><span>Refund</span><strong>${money(refund.amountMinor, refund.currency)}</strong></div>` : ""}
      </div>
      ${
        booking.status === "PENDING_PAYMENT"
          ? `<p class="pay-footnote">${icon("clock")}Seats release at ${formatTime(booking.expiresAt)} unless payment is confirmed.</p>`
          : ""
      }
    </section>
  `;
}

function renderTimeline(booking, payment, refund) {
  const items = [
    {
      tone: "ok",
      title: "Seats held",
      detail: `${booking.items.map((item) => item.seatCode).join(", ")} · ${formatTime(booking.createdAt)}`,
    },
    {
      tone: booking.status === "EXPIRED" ? "danger" : "ok",
      title: "Booking created",
      detail: `${booking.reference} · ${formatTime(booking.createdAt)}`,
    },
  ];

  if (payment) {
    const tone =
      payment.status === "SUCCEEDED" || payment.status === "REFUNDED"
        ? "ok"
        : payment.status === "UNKNOWN"
          ? "warn"
          : payment.status === "FAILED"
            ? "danger"
            : "";
    items.push({
      tone,
      title: `Payment ${PAYMENT_STATUS[payment.status]?.label || payment.status}`,
      detail: `${money(payment.amountMinor, payment.currency)} · ${payment.providerRef || "no provider ref"}`,
    });

    if (payment.reconciledAt) {
      items.push({
        tone: payment.status === "FAILED" ? "danger" : "ok",
        title: "Reconciliation resolved",
        detail: `${payment.reconciliationVerdict || "Reviewed"} · ${formatTime(payment.reconciledAt)}`,
      });
    }
  }

  if (refund) {
    items.push({
      tone: "ok",
      title: "Full refund completed",
      detail: `${money(refund.amountMinor, refund.currency)} · ${formatTime(refund.refundedAt)}`,
    });
  }

  if (booking.status === "EXPIRED") {
    items.push({
      tone: "danger",
      title: "Booking expired",
      detail: `${formatTime(booking.expiredAt)} · seats released`,
    });
  }

  return `
    <section class="lifecycle" aria-labelledby="lifecycle-heading">
      <div class="panel-head">
        <div>
          <h2 id="lifecycle-heading">Lifecycle</h2>
          <p>Every transition is local, timestamped, and refresh-safe.</p>
        </div>
        <span class="status status--neutral">${icon("activity")}audit</span>
      </div>
      <div class="timeline">
        ${items
          .map(
            (item) => `
              <div class="timeline-item ${item.tone ? `timeline-item--${item.tone}` : ""}">
                <span class="timeline-dot">${icon("check")}</span>
                <div class="timeline-copy">
                  <strong>${esc(item.title)}</strong>
                  <span>${esc(item.detail)}</span>
                </div>
              </div>
            `,
          )
          .join("")}
      </div>
    </section>
  `;
}

function renderTechMap() {
  return `
    <section class="tech-map" aria-labelledby="tech-heading">
      <h3 id="tech-heading">Backend mapping</h3>
      <p class="muted">The UI mirrors these routes, without calling them.</p>
      <div class="receipt">
        <div class="receipt-row"><span>Hold</span><code>POST /api/v1/holds</code></div>
        <div class="receipt-row"><span>Booking</span><code>POST /api/v1/bookings</code></div>
        <div class="receipt-row"><span>Payment</span><code>POST /api/v1/payments</code></div>
        <div class="receipt-row"><span>Provider lookup</span><code>GET /api/v1/payments/{id}</code></div>
        <div class="receipt-row"><span>Reconcile</span><code>POST /api/v1/admin/reconciliation/{paymentId}/review</code></div>
        <div class="receipt-row"><span>Refund</span><code>POST /api/v1/admin/payments/{paymentId}/refund</code></div>
      </div>
      <p class="pay-footnote">${icon("info")}Auth, admin roles, and real provider credentials are intentionally absent from this static demo.</p>
    </section>
  `;
}

function renderBookings() {
  const filters = [
    { key: "all", label: "All", count: state.bookings.length },
    { key: "PENDING_PAYMENT", label: "Pending", count: state.bookings.filter((booking) => booking.status === "PENDING_PAYMENT").length },
    { key: "CONFIRMED", label: "Confirmed", count: state.bookings.filter((booking) => booking.status === "CONFIRMED").length },
    { key: "EXPIRED", label: "Expired", count: state.bookings.filter((booking) => booking.status === "EXPIRED").length },
  ];
  const filtered =
    ui.bookingsFilter === "all"
      ? state.bookings
      : state.bookings.filter((booking) => booking.status === ui.bookingsFilter);

  return `
    <div class="view shell section">
      <div class="page-head">
        <div>
          <div class="breadcrumb"><a href="#/">Events</a>${icon("chevron")}<span>Bookings</span></div>
          <h1>Your bookings</h1>
          <p>Pending payments expire. Confirmed bookings stay put. Refunds never release the seat.</p>
        </div>
        <div class="page-head-actions">
          <a class="btn btn--secondary btn--small" href="#/">Browse events ${icon("arrow")}</a>
        </div>
      </div>

      <div class="bookings-toolbar">
        <div class="filter-chips" role="group" aria-label="Filter bookings">
          ${filters
            .map(
              (filter) => `
                <button class="chip" type="button" data-action="filter-bookings" data-filter="${filter.key}" aria-pressed="${ui.bookingsFilter === filter.key}">
                  ${filter.label} <span>${filter.count}</span>
                </button>
              `,
            )
            .join("")}
        </div>
        <span class="muted mono">${state.bookings.length} total</span>
      </div>

      ${
        filtered.length
          ? `<div class="booking-list">${filtered
              .map((booking) => {
                const event = getEvent(booking.eventId);
                const payment = paymentForBooking(booking.id);
                return `
                  <article class="booking-card">
                    <div class="booking-card-main">
                      <span class="booking-card-ref">${esc(booking.reference)}</span>
                      <div class="booking-card-copy">
                        <h3>${esc(event?.title || "Unknown event")}</h3>
                        <div class="booking-card-meta">
                          <span>${icon("calendar")}${event ? formatDateTime(event.startsAt) : "—"}</span>
                          <span>${icon("seat")}${booking.items.map((item) => esc(item.seatCode)).join(", ")}</span>
                          <span>${icon("clock")}${formatDateTime(booking.createdAt)}</span>
                        </div>
                      </div>
                    </div>
                    <div class="booking-card-side">
                      <span class="booking-card-total"><strong>${money(booking.totalMinor, booking.currency)}</strong><span>${booking.items.length} seat${booking.items.length === 1 ? "" : "s"}</span></span>
                      ${statusBadge("booking", booking.status)}
                      ${payment ? statusBadge("payment", payment.status) : ""}
                      <a class="btn btn--secondary btn--small" href="#/booking/${booking.id}">${booking.status === "PENDING_PAYMENT" ? "Resume" : "View"} ${icon("arrow")}</a>
                    </div>
                  </article>
                `;
              })
              .join("")}</div>`
          : renderEmpty(
              "No bookings in this view",
              state.bookings.length
                ? "Try another filter, or reset the demo to start from the seeded state."
                : "Choose a seat and hold it for ten minutes to create your first booking.",
              `<a class="btn btn--primary" href="#/">Browse events ${icon("arrow")}</a>`,
            )
      }
    </div>
  `;
}

function renderOps() {
  const filters = [
    { key: "all", label: "All payments", count: state.payments.length },
    { key: "UNKNOWN", label: "Unknown", count: state.payments.filter((payment) => payment.status === "UNKNOWN").length },
    { key: "SUCCEEDED", label: "Captured", count: state.payments.filter((payment) => payment.status === "SUCCEEDED").length },
    { key: "REFUNDED", label: "Refunded", count: state.payments.filter((payment) => payment.status === "REFUNDED").length },
    { key: "FAILED", label: "Failed", count: state.payments.filter((payment) => payment.status === "FAILED").length },
  ];
  const filtered =
    ui.opsFilter === "all"
      ? state.payments
      : state.payments.filter((payment) => payment.status === ui.opsFilter);
  const capturedTotal = state.payments
    .filter((payment) => ["SUCCEEDED", "REFUNDED"].includes(payment.status))
    .reduce((total, payment) => total + payment.amountMinor, 0);
  const refundedTotal = state.refunds.reduce((total, refund) => total + refund.amountMinor, 0);
  const actionsNeeded =
    unresolvedPayments().length + state.payments.filter((payment) => refundRequired(payment)).length;

  return `
    <div class="view shell section">
      <div class="page-head">
        <div>
          <div class="breadcrumb"><a href="#/">Events</a>${icon("chevron")}<span>Payment operations</span></div>
          <h1>Payment operations</h1>
          <p>Simulated back-office view. Resolve UNKNOWN outcomes and issue one full refund per captured payment.</p>
        </div>
        <div class="page-head-actions">
          <span class="badge">${icon("shield")}Operator view · simulated</span>
        </div>
      </div>

      <div class="metric-grid">
        <div class="metric"><span>Captured volume</span><strong>${money(capturedTotal)}</strong><small>Successfully authorized mock payments</small></div>
        <div class="metric metric--warn"><span>Needs attention</span><strong>${actionsNeeded}</strong><small>UNKNOWN or expiry-captured payments</small></div>
        <div class="metric"><span>Refunded volume</span><strong>${money(refundedTotal)}</strong><small>Full refunds, one per payment</small></div>
        <div class="metric"><span>Audit events</span><strong>${state.audit.length}</strong><small>Local, capped at 100</small></div>
      </div>

      <div class="callout">
        ${icon("info")}
        <div>
          <strong>Reconciliation does not move money.</strong>
          <span class="muted">It resolves an UNKNOWN outcome into a captured or declined state. If capture happened after booking expiry, the payment is flagged for refund.</span>
        </div>
      </div>

      <div class="ops-toolbar">
        <div class="filter-chips" role="group" aria-label="Filter payments">
          ${filters
            .map(
              (filter) => `
                <button class="chip" type="button" data-action="filter-ops" data-filter="${filter.key}" aria-pressed="${ui.opsFilter === filter.key}">
                  ${filter.label} <span>${filter.count}</span>
                </button>
              `,
            )
            .join("")}
        </div>
        <button class="btn btn--quiet btn--small" type="button" data-action="about">${icon("code")}How this maps</button>
      </div>

      ${
        filtered.length
          ? `<div class="table-card">
              <div class="table-scroll">
                <table class="ops-table">
                  <thead>
                    <tr>
                      <th scope="col">Payment</th>
                      <th scope="col">Booking</th>
                      <th scope="col">Amount</th>
                      <th scope="col">Status</th>
                      <th scope="col">Created</th>
                      <th scope="col">Actions</th>
                    </tr>
                  </thead>
                  <tbody>
                    ${filtered
                      .map((payment) => {
                        const booking = getBooking(payment.bookingId);
                        const refund = refundForPayment(payment.id);
                        const canRefund = payment.status === "SUCCEEDED" && !refund;
                        return `
                          <tr>
                            <td>
                              <span class="ops-ref">${esc(shortId(payment.id))}</span>
                              <span class="mono muted">${esc(payment.idempotencyKey.slice(0, 18))}…</span>
                              ${payment.note ? `<span class="ops-note">${esc(noteText(payment.note))}</span>` : ""}
                            </td>
                            <td>
                              ${booking ? `<a class="link" href="#/booking/${booking.id}">${esc(booking.reference)}</a>` : `<span class="muted">—</span>`}
                            </td>
                            <td class="ops-amount">${money(payment.amountMinor, payment.currency)}</td>
                            <td>${statusBadge("payment", payment.status)}</td>
                            <td class="mono muted">${formatDateTime(payment.createdAt)}</td>
                            <td>
                              <div class="ops-actions">
                                ${payment.status === "UNKNOWN" ? `<button class="btn btn--primary btn--small" type="button" data-action="reconcile-payment" data-payment-id="${payment.id}">Reconcile</button>` : ""}
                                ${canRefund ? `<button class="btn btn--danger btn--small" type="button" data-action="refund-payment" data-payment-id="${payment.id}">Refund</button>` : ""}
                                ${refund ? `<span class="status status--info">Refunded</span>` : ""}
                                ${booking ? `<a class="btn btn--quiet btn--small" href="#/booking/${booking.id}">View</a>` : ""}
                              </div>
                            </td>
                          </tr>
                        `;
                      })
                      .join("")}
                  </tbody>
                </table>
              </div>
            </div>`
          : renderEmpty(
              "No payments in this view",
              "Run a mock payment from a pending booking to populate the operator queue.",
              `<a class="btn btn--primary" href="#/bookings">Open bookings ${icon("arrow")}</a>`,
            )
      }

      ${renderAuditPanel()}
    </div>
  `;
}

function renderAuditPanel() {
  return `
    <section class="audit-panel" aria-labelledby="audit-heading">
      <div class="audit-head">
        <div>
          <h2 id="audit-heading">Recent activity</h2>
          <p>Local audit trail. Newest first.</p>
        </div>
        <span class="badge">${icon("activity")}${state.audit.length} events</span>
      </div>
      <div class="audit-list">
        ${state.audit
          .slice(0, 14)
          .map((item) => {
            const toneClass = ["ok", "warn", "danger"].includes(item.tone) ? `audit-icon--${item.tone}` : "";
            return `
              <div class="audit-item">
                <span class="audit-icon ${toneClass}">${icon(item.icon || "activity")}</span>
                <div class="audit-copy">
                  <strong>${esc(item.title)}</strong>
                  <span>${esc(item.detail)}</span>
                </div>
                <span class="audit-time">${formatTime(item.at)}</span>
              </div>
            `;
          })
          .join("")}
      </div>
    </section>
  `;
}

function renderEmpty(title, body, actions) {
  return `
    <div class="empty-state">
      <div>
        <span class="empty-visual">${icon("ticket")}</span>
        <h2>${esc(title)}</h2>
        <p>${esc(body)}</p>
        ${actions || ""}
      </div>
    </div>
  `;
}

function openDialog({ eyebrow = "TicketPay demo", title, body, actions = "" }) {
  if (modal.open) modal.close();
  modalCard.innerHTML = `
    <div class="modal-head">
      <div>
        <span class="eyebrow eyebrow--plain">${esc(eyebrow)}</span>
        <h2 id="modal-title">${esc(title)}</h2>
      </div>
      <button class="icon-button" type="button" data-action="close-dialog" aria-label="Close dialog">${icon("x")}</button>
    </div>
    <div class="modal-body">${body}</div>
    <div class="modal-actions">${actions}</div>
  `;
  modal.showModal();
}

function closeDialog() {
  if (modal.open) modal.close();
}

function showAbout() {
  openDialog({
    eyebrow: "Backend mapping",
    title: "What is real, and what is simulated",
    body: `
      <p><strong>This page is a static simulation.</strong> It runs entirely in the browser. No Spring Boot process is deployed, no API is called, and no card is charged.</p>
      <p>The state machine follows the backend contract:</p>
      <ul>
        <li><code>POST /api/v1/holds</code> creates a ten-minute seat hold.</li>
        <li><code>POST /api/v1/bookings</code> consumes holds into a ten-minute pending booking.</li>
        <li><code>POST /api/v1/payments</code> is idempotent; one payment is allowed per booking.</li>
        <li><code>GET /api/v1/payments/{id}</code> resolves a provider lookup; UNKNOWN is not terminal.</li>
        <li><code>POST /api/v1/admin/reconciliation/{paymentId}/review</code> records an operator verdict.</li>
        <li><code>POST /api/v1/admin/payments/{paymentId}/refund</code> allows one full refund per capture.</li>
      </ul>
      <p>Deliberate demo choices: immediate simulated refunds bypass the backend's settlement-age guard, and a refresh resumes an authorizing payment from stored state. Production auth, roles, database migrations, provider credentials, and a real API are not included.</p>
      <p class="modal-note">The real backend remains a Spring Boot project with PostgreSQL and Flyway. This demo is separate and deployable as static assets.</p>
    `,
    actions: `<button class="btn btn--primary" type="button" data-action="close-dialog">Got it</button>`,
  });
}

function showResetDialog() {
  openDialog({
    eyebrow: "Demo controls",
    title: "Reset the simulation?",
    body: `
      <p>This clears bookings, payments, refunds, holds, selections, and the local audit trail, then restores the two seeded events.</p>
      <p class="modal-note">The real backend is untouched. This only resets this browser's demo state.</p>
    `,
    actions: `
      <button class="btn btn--quiet" type="button" data-action="close-dialog">Cancel</button>
      <button class="btn btn--danger" type="button" data-action="confirm-reset">${icon("refresh")}Reset demo</button>
    `,
  });
}

function showRefundDialog(paymentId) {
  const payment = getPayment(paymentId);
  const booking = payment ? getBooking(payment.bookingId) : null;
  if (!payment || payment.status !== "SUCCEEDED" || refundForPayment(payment.id)) {
    toast("This payment cannot be refunded again.", "warn");
    return;
  }

  openDialog({
    eyebrow: "Payment operations",
    title: `Refund ${money(payment.amountMinor, payment.currency)}?`,
    body: `
      <p>This creates <strong>one full refund</strong> for payment <code>${esc(payment.id)}</code>${booking ? ` on booking <strong>${esc(booking.reference)}</strong>` : ""}.</p>
      <p>The booking remains confirmed and the seat stays sold. The backend does not return the seat to inventory after a refund.</p>
      <p class="modal-note">No real money moves. The demo allows an immediate simulated refund; the backend's settlement-age guard is not simulated.</p>
    `,
    actions: `
      <button class="btn btn--quiet" type="button" data-action="close-dialog">Cancel</button>
      <button class="btn btn--danger" type="button" data-action="confirm-refund" data-payment-id="${payment.id}">${icon("rotate")}Confirm full refund</button>
    `,
  });
}

function showReconcileDialog(paymentId) {
  const payment = getPayment(paymentId);
  const booking = payment ? getBooking(payment.bookingId) : null;
  if (!payment || payment.status !== "UNKNOWN") {
    toast("Only UNKNOWN payments need reconciliation.", "warn");
    return;
  }

  openDialog({
    eyebrow: "Payment operations",
    title: "Resolve the unknown outcome",
    body: `
      <p>Payment <code>${esc(payment.id)}</code>${booking ? ` for <strong>${esc(booking.reference)}</strong>` : ""} timed out. Reconcile against the provider lookup before taking action.</p>
      <ul>
        <li><strong>Confirm capture</strong> marks the payment succeeded. If the booking is still pending, it confirms; if it already expired, the payment is flagged for a full refund.</li>
        <li><strong>Confirm decline</strong> marks the payment failed and leaves the booking to expire normally.</li>
        <li><strong>Keep unknown</strong> changes nothing and leaves the queue item open.</li>
      </ul>
      <p class="modal-note">This records a verdict. It does not create, move, or reverse money.</p>
    `,
    actions: `
      <button class="btn btn--quiet" type="button" data-action="close-dialog">Keep unknown</button>
      <button class="btn btn--danger" type="button" data-action="resolve-payment" data-payment-id="${payment.id}" data-verdict="decline">Confirm decline</button>
      <button class="btn btn--primary" type="button" data-action="resolve-payment" data-payment-id="${payment.id}" data-verdict="success">${icon("check")}Confirm capture</button>
    `,
  });
}

function toast(message, tone = "ok") {
  if (!toastRegion) return;
  const node = document.createElement("div");
  node.className = `toast${tone === "warn" ? " toast--warn" : tone === "danger" ? " toast--danger" : ""}`;
  node.setAttribute("role", "status");
  node.innerHTML = `${icon(tone === "danger" ? "x" : tone === "warn" ? "alert" : "check")}<span>${esc(message)}</span>`;
  toastRegion.append(node);
  window.setTimeout(() => {
    node.classList.add("toast--leaving");
    window.setTimeout(() => node.remove(), 240);
  }, 4200);
}

function settleSuccessfulPayment(payment, { reconciled = false } = {}) {
  expireState();
  const booking = getBooking(payment.bookingId);
  const now = new Date().toISOString();

  payment.status = "SUCCEEDED";
  payment.resolvedAt = now;
  payment.providerRef = payment.providerRef || `mock_${randomRef(12)}`;
  payment.note = null;

  if (reconciled) {
    payment.reconciledAt = now;
    payment.reconciliationVerdict =
      booking?.status === "EXPIRED" ? "PROVIDER_SUCCESS_BOOKING_EXPIRED" : "PROVIDER_SUCCESS";
  }

  if (!booking) return payment;

  if (booking.status === "PENDING_PAYMENT") {
    booking.status = "CONFIRMED";
    booking.confirmedAt = now;
    booking.paymentId = payment.id;
    const event = getEvent(booking.eventId);
    for (const item of booking.items) {
      const seat = event?.seats.find((candidate) => candidate.id === item.eventSeatId);
      if (!seat) continue;
      seat.status = "SOLD";
      seat.heldBy = null;
      seat.holdExpiresAt = null;
      seat.bookingId = booking.id;
      seat.paymentId = payment.id;
    }
  } else if (booking.status === "EXPIRED") {
    payment.note = "BOOKING_EXPIRED_AT_CONFIRM";
  }

  return payment;
}

function settleDeclinedPayment(payment, { reconciled = false } = {}) {
  const now = new Date().toISOString();
  payment.status = "FAILED";
  payment.resolvedAt = now;
  payment.providerRef = payment.providerRef || `mock_${randomRef(12)}`;
  payment.note = reconciled ? "PROVIDER_DECLINED" : "INSUFFICIENT_FUNDS";
  if (reconciled) {
    payment.reconciledAt = now;
    payment.reconciliationVerdict = "PROVIDER_DECLINED";
  }
  return payment;
}

function schedulePaymentResolution(paymentId, delayMs = 1600) {
  window.setTimeout(() => resolvePayment(paymentId), Math.max(0, delayMs));
}

function resumeAuthorizingPayments() {
  for (const payment of state.payments) {
    if (payment.status !== "AUTHORIZING") continue;
    const resolveAt = Date.parse(payment.resolveAt || "");
    schedulePaymentResolution(
      payment.id,
      Number.isFinite(resolveAt) ? resolveAt - Date.now() : 400,
    );
  }
}

function resolvePayment(paymentId) {
  const payment = getPayment(paymentId);
  if (!payment || payment.status !== "AUTHORIZING") return;

  expireState();
  const booking = getBooking(payment.bookingId);
  const outcome = payment.outcome || "success";

  if (outcome === "success") {
    settleSuccessfulPayment(payment);
    if (payment.note === "BOOKING_EXPIRED_AT_CONFIRM") {
      pushAudit(
        "PAYMENT_SUCCEEDED",
        "Captured after booking expiry",
        `${money(payment.amountMinor, payment.currency)} captured · booking ${booking?.reference || "unknown"} expired · refund required.`,
        {
          tone: "warn",
          bookingId: payment.bookingId,
          paymentId: payment.id,
          eventId: booking?.eventId,
        },
      );
      toast("Payment captured after expiry. A full refund is now required.", "warn");
    } else {
      pushAudit(
        "PAYMENT_SUCCEEDED",
        "Payment captured",
        `${money(payment.amountMinor, payment.currency)} · booking ${booking?.reference || "unknown"} confirmed.`,
        {
          tone: "ok",
          bookingId: payment.bookingId,
          paymentId: payment.id,
          eventId: booking?.eventId,
        },
      );
      toast("Payment captured and booking confirmed.");
    }
  } else if (outcome === "decline") {
    settleDeclinedPayment(payment);
    pushAudit(
      "PAYMENT_DECLINED",
      "Payment declined",
      `${money(payment.amountMinor, payment.currency)} · provider declined. No retry is allowed for this booking.`,
      {
        tone: "danger",
        bookingId: payment.bookingId,
        paymentId: payment.id,
        eventId: booking?.eventId,
      },
    );
    toast("Payment declined. This booking cannot retry.", "danger");
  } else {
    const now = new Date().toISOString();
    payment.status = "UNKNOWN";
    payment.resolvedAt = now;
    payment.note = "TIMEOUT";
    payment.providerRef = payment.providerRef || `mock_${randomRef(12)}`;
    pushAudit(
      "PAYMENT_UNKNOWN",
      "Provider outcome unknown",
      `${money(payment.amountMinor, payment.currency)} · timeout. Reconcile before acting.`,
      {
        tone: "warn",
        bookingId: payment.bookingId,
        paymentId: payment.id,
        eventId: booking?.eventId,
      },
    );
    toast("Provider timed out. The payment is UNKNOWN, not failed.", "warn");
  }

  ui.paymentInFlight = false;
  persist();
  render();
}

function createMockPayment(bookingId) {
  const booking = getBooking(bookingId);
  if (!booking) {
    toast("Booking not found.", "danger");
    return;
  }

  expireState();
  if (booking.status !== "PENDING_PAYMENT") {
    toast("This booking can no longer accept a payment.", "warn");
    render();
    return;
  }
  if (paymentForBooking(booking.id)) {
    toast("One payment is allowed per booking.", "warn");
    return;
  }

  const selected = document.querySelector('input[name="payment-outcome"]:checked');
  const outcome = selected?.value || "success";
  const now = Date.now();
  const payment = {
    id: uid("pay"),
    bookingId: booking.id,
    amountMinor: booking.totalMinor,
    currency: booking.currency,
    status: "AUTHORIZING",
    idempotencyKey: booking.idempotencyKey,
    providerRef: null,
    note: null,
    outcome,
    createdAt: new Date(now).toISOString(),
    resolveAt: new Date(now + 1600).toISOString(),
    resolvedAt: null,
    reconciledAt: null,
    reconciliationVerdict: null,
  };

  booking.paymentId = payment.id;
  state.payments.push(payment);
  ui.paymentInFlight = true;
  pushAudit(
    "PAYMENT_AUTHORIZING",
    "Payment authorization started",
    `${money(payment.amountMinor, payment.currency)} · idempotency key attached.`,
    {
      tone: "info",
      bookingId: booking.id,
      paymentId: payment.id,
      eventId: booking.eventId,
    },
  );

  persist();
  render();
  schedulePaymentResolution(payment.id, 1600);
}

function doReconcile(paymentId, verdict) {
  const payment = getPayment(paymentId);
  const booking = payment ? getBooking(payment.bookingId) : null;
  if (!payment || payment.status !== "UNKNOWN") {
    toast("That payment no longer needs reconciliation.", "warn");
    closeDialog();
    render();
    return;
  }

  if (verdict === "success") {
    settleSuccessfulPayment(payment, { reconciled: true });
    const expired = payment.note === "BOOKING_EXPIRED_AT_CONFIRM";
    pushAudit(
      "RECONCILIATION_SUCCESS",
      expired ? "Capture confirmed after expiry" : "Capture confirmed",
      expired
        ? `${money(payment.amountMinor, payment.currency)} · refund required because the booking had expired.`
        : `${money(payment.amountMinor, payment.currency)} · booking ${booking?.reference || "unknown"} confirmed.`,
      {
        tone: expired ? "warn" : "ok",
        bookingId: payment.bookingId,
        paymentId: payment.id,
        eventId: booking?.eventId,
      },
    );
    toast(expired ? "Capture confirmed. Refund is required." : "Capture confirmed.", expired ? "warn" : "ok");
  } else if (verdict === "decline") {
    settleDeclinedPayment(payment, { reconciled: true });
    pushAudit(
      "RECONCILIATION_DECLINED",
      "Provider decline confirmed",
      `${money(payment.amountMinor, payment.currency)} · no capture. Booking remains pending until expiry.`,
      {
        tone: "danger",
        bookingId: payment.bookingId,
        paymentId: payment.id,
        eventId: booking?.eventId,
      },
    );
    toast("Decline confirmed. No money was captured.", "warn");
  } else {
    toast("Unknown payments can only be confirmed or declined.", "warn");
    return;
  }

  persist();
  closeDialog();
  render();
}

function doRefund(paymentId) {
  const payment = getPayment(paymentId);
  const booking = payment ? getBooking(payment.bookingId) : null;
  if (!payment || payment.status !== "SUCCEEDED") {
    toast("Only a captured payment can be refunded.", "warn");
    closeDialog();
    return;
  }
  if (refundForPayment(payment.id)) {
    toast("This payment already has a refund.", "warn");
    closeDialog();
    return;
  }

  const now = new Date().toISOString();
  const refund = {
    id: uid("ref"),
    paymentId: payment.id,
    bookingId: payment.bookingId,
    amountMinor: payment.amountMinor,
    currency: payment.currency,
    status: "COMPLETED",
    reason: "Full refund requested in demo",
    refundedAt: now,
  };
  state.refunds.push(refund);
  payment.status = "REFUNDED";
  payment.refundedAt = now;
  payment.reconciliationVerdict = "REFUND_COMPLETED";
  pushAudit(
    "REFUND_COMPLETED",
    "Full refund completed",
    `${money(payment.amountMinor, payment.currency)} · booking ${booking?.reference || "unknown"} stays confirmed; seat stays sold.`,
    {
      tone: "ok",
      bookingId: payment.bookingId,
      paymentId: payment.id,
      eventId: booking?.eventId,
    },
  );

  persist();
  closeDialog();
  render();
  toast("Full refund recorded. The seat was not returned to inventory.");
}

function doReplay(paymentId) {
  const payment = getPayment(paymentId);
  if (!payment) {
    toast("Payment not found.", "danger");
    return;
  }
  if (payment.status === "AUTHORIZING") {
    toast("The original provider call is still in progress.", "warn");
    return;
  }

  state.replays += 1;
  pushAudit(
    "IDEMPOTENT_REPLAY",
    "Idempotency replay",
    `Same key returned ${PAYMENT_STATUS[payment.status]?.label || payment.status}; no new payment was created.`,
    {
      tone: "info",
      bookingId: payment.bookingId,
      paymentId: payment.id,
      eventId: getBooking(payment.bookingId)?.eventId,
    },
  );
  persist();
  render();
  toast(`Same key returned ${PAYMENT_STATUS[payment.status]?.label || payment.status}. No second charge.`);
}

function toggleSeat(eventId, seatId) {
  const event = getEvent(eventId);
  const seat = event?.seats.find((candidate) => candidate.id === seatId);
  if (!event || !seat) {
    toast("Seat not found.", "danger");
    return;
  }
  if (seat.status !== "AVAILABLE") {
    toast("That seat is no longer available.", "warn");
    render();
    return;
  }

  const selected = state.selections[event.id] || [];
  const holds = activeHolds(event.id);
  const index = selected.indexOf(seat.seatCode);

  if (index >= 0) {
    selected.splice(index, 1);
  } else {
    if (selected.length + holds.length >= MAX_SEATS) {
      toast(`Maximum ${MAX_SEATS} seats per booking.`, "warn");
      return;
    }
    selected.push(seat.seatCode);
  }

  state.selections[event.id] = selected;
  persist();
  render({ focusSelector: `[data-seat-id="${seat.id}"]` });
}

function acquireHolds(eventId) {
  const event = getEvent(eventId);
  if (!event) return;

  const selected = state.selections[event.id] || [];
  const existing = activeHolds(event.id);
  if (!selected.length) {
    toast("Select at least one seat first.", "warn");
    return;
  }
  if (selected.length + existing.length > MAX_SEATS) {
    toast(`Maximum ${MAX_SEATS} seats per booking.`, "warn");
    return;
  }

  const seats = selected
    .map((code) => event.seats.find((seat) => seat.seatCode === code))
    .filter((seat) => seat && seat.status === "AVAILABLE");

  if (!seats.length) {
    state.selections[event.id] = [];
    persist();
    render();
    toast("Those seats were taken. Choose again.", "warn");
    return;
  }

  const expiresAt = new Date(Date.now() + HOLD_TTL_MS).toISOString();
  const newHolds = seats.map((seat) => {
    const hold = {
      id: uid("hold"),
      eventId: event.id,
      eventSeatId: seat.id,
      createdAt: new Date().toISOString(),
      expiresAt,
    };
    seat.status = "HELD";
    seat.heldBy = "you";
    seat.holdExpiresAt = expiresAt;
    return hold;
  });

  state.holds.push(...newHolds);
  state.selections[event.id] = [];
  pushAudit(
    "HOLD_CREATED",
    `${newHolds.length} seat${newHolds.length === 1 ? "" : "s"} held`,
    `${event.title} · ${seats.map((seat) => seat.seatCode).join(", ")} · expires ${formatTime(expiresAt)}.`,
    {
      tone: "warn",
      eventId: event.id,
    },
  );
  persist();
  render();
  toast(`${newHolds.length} seat${newHolds.length === 1 ? "" : "s"} held for 10 minutes.`);
}

function releaseHold(holdId) {
  const hold = state.holds.find((candidate) => candidate.id === holdId);
  if (!hold) {
    toast("Hold not found.", "warn");
    return;
  }
  const event = getEvent(hold.eventId);
  const seat = event?.seats.find((candidate) => candidate.id === hold.eventSeatId);
  state.holds = state.holds.filter((candidate) => candidate.id !== holdId);

  if (seat && !seat.bookingId && seat.status === "HELD") {
    seat.status = "AVAILABLE";
    seat.heldBy = null;
    seat.holdExpiresAt = null;
  }

  pushAudit(
    "HOLD_RELEASED",
    `Hold released for ${seat?.seatCode || "seat"}`,
    `${event?.title || "Event"} · seat returned to inventory.`,
    { tone: "neutral", eventId: event?.id },
  );
  persist();
  render();
  toast("Hold released. The seat is available again.");
}

function createBookingFromHolds(eventId) {
  const event = getEvent(eventId);
  if (!event) return;
  expireState();

  const holds = activeHolds(event.id);
  if (!holds.length) {
    toast("No active holds for this event.", "warn");
    render();
    return;
  }

  const items = [];
  const consumedSeatIds = [];

  for (const hold of holds) {
    const seat = event.seats.find((candidate) => candidate.id === hold.eventSeatId);
    if (!seat || seat.status !== "HELD" || seat.heldBy !== "you" || seat.bookingId) continue;
    items.push({ eventSeatId: seat.id, seatCode: seat.seatCode, priceMinor: event.priceMinor });
    consumedSeatIds.push(seat.id);
  }

  if (!items.length) {
    state.holds = state.holds.filter((hold) => hold.eventId !== event.id);
    persist();
    render();
    toast("Those holds are no longer usable. Select seats again.", "warn");
    return;
  }

  items.sort((left, right) => Number(left.seatCode.slice(1)) - Number(right.seatCode.slice(1)));
  const now = Date.now();
  const booking = {
    id: uid("book"),
    reference: `BK-${randomRef(8)}`,
    eventId: event.id,
    userId: state.user.id,
    status: "PENDING_PAYMENT",
    items,
    totalMinor: items.length * event.priceMinor,
    currency: event.currency,
    createdAt: new Date(now).toISOString(),
    expiresAt: new Date(now + BOOKING_TTL_MS).toISOString(),
    confirmedAt: null,
    expiredAt: null,
    cancelledAt: null,
    idempotencyKey: `demo_key_${randomRef(24)}`,
    paymentId: null,
  };

  booking.items.forEach((item) => {
    const seat = event.seats.find((candidate) => candidate.id === item.eventSeatId);
    if (!seat) return;
    seat.status = "HELD";
    seat.heldBy = "booking";
    seat.holdExpiresAt = booking.expiresAt;
    seat.bookingId = booking.id;
  });

  state.bookings.unshift(booking);
  state.holds = state.holds.filter((hold) => !consumedSeatIds.includes(hold.eventSeatId));
  state.selections[event.id] = [];
  pushAudit(
    "BOOKING_CREATED",
    `Booking ${booking.reference} created`,
    `${event.title} · ${items.map((item) => item.seatCode).join(", ")} · expires ${formatTime(booking.expiresAt)}.`,
    {
      tone: "warn",
      bookingId: booking.id,
      eventId: event.id,
    },
  );

  persist();
  navigate(`#/booking/${booking.id}`, { scrollTop: true });
  toast("Booking created. Complete the mock payment before the timer ends.");
}

async function copyText(text) {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      toast("Idempotency key copied.");
      return;
    }
  } catch {
    // Fall back to a temporary textarea.
  }

  const area = document.createElement("textarea");
  area.value = text;
  area.setAttribute("readonly", "");
  area.style.position = "fixed";
  area.style.opacity = "0";
  document.body.append(area);
  area.select();
  let copied = false;
  try {
    copied = document.execCommand("copy");
  } catch {
    copied = false;
  }
  area.remove();
  toast(copied ? "Idempotency key copied." : "Copy failed. Select the key manually.", copied ? "ok" : "warn");
}

function resetDemo() {
  state = createInitialState();
  ui.bookingsFilter = "all";
  ui.opsFilter = "all";
  ui.paymentInFlight = false;
  storageWarned = false;
  lastRoute = "";
  persist();
  closeDialog();
  window.history.replaceState(null, "", "#/");
  render({ scrollTop: true });
  toast("Demo reset to the seeded state.");
}

function handleAction(action, trigger) {
  switch (action) {
    case "toggle-seat":
      toggleSeat(trigger.dataset.eventId, trigger.dataset.seatId);
      break;
    case "hold-selected":
      acquireHolds(trigger.dataset.eventId);
      break;
    case "release-hold":
      releaseHold(trigger.dataset.holdId);
      break;
    case "create-booking":
      createBookingFromHolds(trigger.dataset.eventId);
      break;
    case "pay":
      createMockPayment(trigger.dataset.bookingId);
      break;
    case "replay-payment":
      doReplay(trigger.dataset.paymentId);
      break;
    case "refund-payment":
      showRefundDialog(trigger.dataset.paymentId);
      break;
    case "confirm-refund":
      doRefund(trigger.dataset.paymentId);
      break;
    case "reconcile-payment":
      showReconcileDialog(trigger.dataset.paymentId);
      break;
    case "resolve-payment":
      doReconcile(trigger.dataset.paymentId, trigger.dataset.verdict);
      break;
    case "copy-key":
      copyText(trigger.dataset.key || "");
      break;
    case "filter-bookings":
      ui.bookingsFilter = trigger.dataset.filter || "all";
      render({ focusSelector: `[data-action="filter-bookings"][data-filter="${ui.bookingsFilter}"]` });
      break;
    case "filter-ops":
      ui.opsFilter = trigger.dataset.filter || "all";
      render({ focusSelector: `[data-action="filter-ops"][data-filter="${ui.opsFilter}"]` });
      break;
    case "about":
      showAbout();
      break;
    case "reset":
      showResetDialog();
      break;
    case "confirm-reset":
      resetDemo();
      break;
    case "close-dialog":
      closeDialog();
      break;
    default:
      break;
  }
}

function init() {
  if (!window.location.hash) {
    window.history.replaceState(null, "", "#/");
  }

  document.addEventListener("click", (event) => {
    const trigger = event.target.closest("[data-action]");
    if (!trigger || trigger.disabled) return;
    Promise.resolve(handleAction(trigger.dataset.action, trigger)).catch(() => {
      toast("The demo hit an unexpected state. Reset to recover.", "danger");
    });
  });

  window.addEventListener("hashchange", () => render({ scrollTop: true }));
  window.addEventListener("storage", (event) => {
    if (event.key !== STORAGE_KEY) return;
    state = loadState();
    render();
    toast("Demo state synced from another tab.");
  });

  modal.addEventListener("click", (event) => {
    if (event.target === modal) closeDialog();
  });
  modal.addEventListener("close", () => {
    modalCard.innerHTML = "";
  });

  resumeAuthorizingPayments();
  render();

  window.setInterval(() => {
    updateCountdowns();
    if (expireState()) {
      render();
      toast("A hold or booking timer ended. Seats were updated.", "warn");
    }
  }, 1000);
}

init();
